package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

public final class SearchEngine implements AutoCloseable {
  public record Request(String text, int pageSize, String cursor, Map<String, String> filters) {
    public Request {
      if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("page size");
      filters = filters == null ? Map.of() : Map.copyOf(filters);
      if (filters.size() > 8
          || filters.entrySet().stream()
              .anyMatch(
                  e ->
                      !Set.of("repository", "language", "kind", "coordinates", "version")
                              .contains(e.getKey())
                          || e.getValue().length() > 256))
        throw new IllegalArgumentException("filter");
    }
  }

  public record Hit(
      String key,
      String path,
      String repository,
      String sha256,
      float score,
      String snippet,
      String explanation) {}

  public record Response(
      String generationId,
      String scoringVersion,
      long totalHits,
      String relation,
      List<Hit> results,
      Map<String, Integer> facets,
      String cursor) {}

  private final QueryPlanner.Mode defaultMode;
  private final RankingProfile rankingProfile;
  private final RankingConfiguration ranking;
  private final boolean structuralIndex;
  private final String capturedRankingIdentity;
  private final IngestionCoordinator ingestion;
  private final ContentAddressedStore cas;
  private final AuthorizationFilter authorization;
  private final SearchCursor cursors;
  private final SearchRankingPipeline pipeline;

  private record CacheKey(
      AuthorizationFilter.Access access, String generation, String digest, int offset) {}

  /** Cache content and continuation position, never a time-limited signed cursor. */
  private record CachedPage(
      String generation,
      String scoringVersion,
      long count,
      String relation,
      List<Hit> hits,
      Map<String, Integer> facets,
      Integer nextOffset) {
    Response response(SearchCursor cursors, AuthorizationFilter.Access access, String digest) {
      String next =
          nextOffset == null ? null : cursors.encode(access, digest, generation, nextOffset);
      return new Response(generation, scoringVersion, count, relation, hits, facets, next);
    }
  }

  private final Map<CacheKey, CachedPage> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<CacheKey, CachedPage>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedPage> eldest) {
              return size() > 128;
            }
          });
  private final java.util.concurrent.atomic.AtomicLong cacheHits =
      new java.util.concurrent.atomic.AtomicLong();

  public SearchEngine(Path root, AuthorizationFilter authorization) throws IOException {
    this(
        root,
        authorization,
        QueryPlanner.Mode.valueOf(System.getProperty("indexforge.ranking-mode", "BASELINE")));
  }

  public SearchEngine(Path root, AuthorizationFilter authorization, QueryPlanner.Mode defaultMode)
      throws IOException {
    this(root, authorization, defaultMode, RankingProfile.current());
  }

  public SearchEngine(
      Path root,
      AuthorizationFilter authorization,
      QueryPlanner.Mode defaultMode,
      RankingProfile rankingProfile)
      throws IOException {
    this(root, authorization, defaultMode, rankingProfile, RankingConfiguration.system());
  }

  public SearchEngine(
      Path root,
      AuthorizationFilter authorization,
      QueryPlanner.Mode defaultMode,
      RankingProfile rankingProfile,
      RankingConfiguration ranking)
      throws IOException {
    this(root, authorization, defaultMode, rankingProfile, ranking, Clock.systemUTC());
  }

  SearchEngine(
      Path root,
      AuthorizationFilter authorization,
      QueryPlanner.Mode defaultMode,
      RankingProfile rankingProfile,
      RankingConfiguration ranking,
      Clock clock)
      throws IOException {
    Objects.requireNonNull(clock);
    this.ranking = Objects.requireNonNull(ranking);
    structuralIndex =
        ranking.strategy().structural() || Boolean.getBoolean("indexforge.structural-index");
    this.defaultMode = Objects.requireNonNull(defaultMode);
    this.rankingProfile = Objects.requireNonNull(rankingProfile);
    capturedRankingIdentity = ranking.identity(rankingProfile);
    this.authorization = Objects.requireNonNull(authorization);
    pipeline = new SearchRankingPipeline(rankingProfile, ranking, authorization);
    ingestion = new IngestionCoordinator(root, authorization, structuralIndex);
    cas = ingestion.contentStore();
    try {
      Path secret = root.resolve("cursor.key");
      if (!Files.exists(secret)) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        DurableFiles.atomicWrite(secret, key);
        try {
          Files.setPosixFilePermissions(
              secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
      }
      cursors = new SearchCursor(Files.readAllBytes(secret), clock);
    } catch (IOException | RuntimeException failure) {
      try {
        ingestion.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /** Export only matching authorized-document features, never raw collection statistics. */
  private static String safeExplanation(Explanation explanation, float score, String features) {
    Set<String> matches = new TreeSet<>();
    collectMatches(explanation, matches);
    String body =
        "Authorized match; score="
            + score
            + "; matched features="
            + String.join(", ", matches)
            + features;
    return body.length() <= 4096 ? body : body.substring(0, 4070) + "\n[explanation truncated]";
  }

  private static void collectMatches(Explanation explanation, Set<String> matches) {
    if (!explanation.isMatch() || matches.size() >= 64) return;
    String description = explanation.getDescription();
    if (description.startsWith("weight(")) {
      int end = description.indexOf(" in ");
      if (end > 7) matches.add(description.substring(7, Math.min(end, 263)));
    }
    for (Explanation detail : explanation.getDetails()) collectMatches(detail, matches);
  }

  public long cacheHits() {
    return cacheHits.get();
  }

  public ContentAddressedStore contentStore() {
    return cas;
  }

  public GenerationPublisher generations(String tenant) throws IOException {
    return ingestion.generations(tenant);
  }

  public IngestionJob.Registered register(
      AuthorizationFilter.Access access, String key, SnapshotManifest manifest) throws IOException {
    return ingestion.register(access, key, manifest);
  }

  public boolean completed(String id) {
    return ingestion.completed(id);
  }

  public IngestionJob.Registered ingest(
      AuthorizationFilter.Access access,
      String key,
      SnapshotManifest manifest,
      GenerationBuilder.Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    return ingestion.execute(access, ingestion.register(access, key, manifest), fault, cancelled);
  }

  public IngestionJob.Registered resume(AuthorizationFilter.Access access, String id)
      throws IOException {
    return resume(
        access, id, GenerationBuilder.Fault.none(), () -> Thread.currentThread().isInterrupted());
  }

  public IngestionJob.Registered resume(
      AuthorizationFilter.Access a,
      String id,
      GenerationBuilder.Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    return ingestion.execute(a, job(a, id), fault, cancelled);
  }

  public IngestionJob.Registered job(AuthorizationFilter.Access access, String id)
      throws IOException {
    return ingestion.job(access, id);
  }

  public Response search(String principal, Request request) throws IOException {
    return search(principal, request, defaultMode);
  }

  public Response search(String principal, Request request, QueryPlanner.Mode mode)
      throws IOException {
    return search(authorization.current(principal), request, mode);
  }

  /**
   * Preserve the authenticated principal and tenant across admission and permission-refresh
   * retries. Repository grants and their version still come from the current server-side mapping; a
   * caller's captured grants never authorize retrieval. HTTP adapters must use this entry point
   * instead of discarding the verified tenant and passing only a principal.
   */
  public Response search(AuthorizationFilter.Access authenticated, Request request)
      throws IOException {
    return search(authenticated, request, defaultMode);
  }

  public Response search(
      AuthorizationFilter.Access authenticated, Request request, QueryPlanner.Mode mode)
      throws IOException {
    Objects.requireNonNull(authenticated);
    QueryAst ast = QueryAst.parse(request.text());
    String scoringVersion = scoringVersion(mode);
    String digest = SearchRequestIdentity.digest(request, mode, scoringVersion);
    for (int retry = 0; retry < 3; retry++) {
      AuthorizationFilter.Access a = currentInTenant(authenticated);
      SearchCursor.Position position =
          request.cursor() == null ? null : cursors.decode(request.cursor(), a, digest);
      try (GenerationLease lease =
          generations(a.tenant()).acquire(position == null ? null : position.generation())) {
        CacheKey cacheKey =
            new CacheKey(a, lease.id(), digest, position == null ? 0 : position.offset());
        CachedPage cached = cache.get(cacheKey);
        if (cached != null) {
          Response response = cached.response(cursors, a, digest);
          if (authorization.current(a)) {
            cacheHits.incrementAndGet();
            return response;
          }
        }
        Query authorized = authorizedQuery(ast, request, mode, a);
        var ranked =
            pipeline.rank(
                pipeline.candidates(lease, authorized, request.text(), mode),
                lease,
                request.text(),
                mode,
                a);
        int start = position == null ? 0 : position.offset(),
            end = Math.min(ranked.size(), start + request.pageSize());
        if (start > ranked.size()) throw new IllegalArgumentException("cursor offset");
        List<Hit> hits = new ArrayList<>();
        for (var c : ranked.subList(start, end)) {
          var d = lease.reader().storedFields().document(c.document());
          hits.add(
              new Hit(
                  c.key(),
                  d.get("path"),
                  d.get("repository"),
                  d.get("sha256"),
                  c.score(),
                  SnippetBuilder.snippet(d.get("content"), request.text()),
                  safeExplanation(
                      lease.searcher().explain(authorized, c.document()),
                      c.score(),
                      c.features())));
        }
        long count = lease.searcher().count(authorized);
        Map<String, Integer> facets = new TreeMap<>();
        for (String repo : new TreeSet<>(a.repositories())) {
          Query q =
              new BooleanQuery.Builder()
                  .add(authorized, BooleanClause.Occur.MUST)
                  .add(new TermQuery(new Term("repository", repo)), BooleanClause.Occur.FILTER)
                  .build();
          int n = lease.searcher().count(q);
          if (n > 0) facets.put(repo, n);
        }
        CachedPage page =
            new CachedPage(
                lease.id(),
                scoringVersion,
                count,
                "EQUAL_TO",
                List.copyOf(hits),
                Map.copyOf(facets),
                end < ranked.size() ? end : null);
        Response response = page.response(cursors, a, digest);
        if (authorization.current(a)) {
          cache.put(cacheKey, page);
          return response;
        }
      }
    }
    throw new SecurityException("authorization changed; retry");
  }

  private AuthorizationFilter.Access currentInTenant(AuthorizationFilter.Access authenticated) {
    var current = authorization.current(authenticated.principal());
    if (!authenticated.tenant().equals(current.tenant()))
      throw new SecurityException("access denied");
    // The final current(access) check includes tenant and version. A failed recheck retries
    // through this boundary, so neither a fresh response nor a cache hit can switch tenants.
    return current;
  }

  public String rankingIdentity() {
    return capturedRankingIdentity;
  }

  private String scoringVersion(QueryPlanner.Mode mode) {
    return mode == QueryPlanner.Mode.BASELINE
        ? "bm25-content-v2/" + RankingCodeIdentity.SHA256
        : QueryPlanner.VERSION + "-" + rankingIdentity();
  }

  private Query authorizedQuery(
      QueryAst ast, Request request, QueryPlanner.Mode mode, AuthorizationFilter.Access access) {
    var query =
        new BooleanQuery.Builder()
            .add(
                new QueryPlanner(rankingProfile, ranking.strategy())
                    .plan(ast, mode, request.text()),
                BooleanClause.Occur.MUST);
    for (var filter : request.filters().entrySet())
      query.add(
          new TermQuery(new Term(filter.getKey(), filter.getValue())), BooleanClause.Occur.FILTER);
    return authorization.apply(query.build(), access);
  }

  public record AuditCandidate(String key, String path, double[] features) {
    public AuditCandidate {
      features = SearchFeatureSchema.checked(features);
    }

    @Override
    public double[] features() {
      return features.clone();
    }
  }

  public record CandidateAudit(
      String generation,
      String rankingIdentity,
      String featureSchema,
      List<AuditCandidate> candidates) {
    public CandidateAudit {
      candidates = List.copyOf(candidates);
    }
  }

  /** Local evaluation seam: actual serving candidate pool, never accepts relevance labels. */
  public CandidateAudit auditCandidates(String principal, Request request, QueryPlanner.Mode mode)
      throws IOException {
    return auditCandidates(principal, request, mode, false).base();
  }

  public record SourceAudit(CandidateAudit base, List<String> usageKeys, String usageReason) {
    public SourceAudit {
      usageKeys = List.copyOf(usageKeys);
    }
  }

  /** Label-blind source-study seam: usage identities/status remain metadata, never features. */
  public SourceAudit auditSourceCandidates(
      String principal, Request request, QueryPlanner.Mode mode) throws IOException {
    if (!ranking.strategy().structural())
      throw new IllegalArgumentException("source audit requires structural deployment");
    return auditCandidates(principal, request, mode, true);
  }

  private SourceAudit auditCandidates(
      String principal, Request request, QueryPlanner.Mode mode, boolean includeUsage)
      throws IOException {
    if (request.cursor() != null)
      throw new IllegalArgumentException("audit requires a first candidate pool");
    QueryAst ast = QueryAst.parse(request.text());
    for (int retry = 0; retry < 3; retry++) {
      var access = authorization.current(principal);
      try (var lease = generations(access.tenant()).acquire()) {
        var base =
            pipeline.candidates(
                lease, authorizedQuery(ast, request, mode, access), request.text(), mode);
        var vectors = pipeline.features(base, lease, request.text(), mode, access);
        var usage =
            includeUsage
                ? pipeline.usage(base, lease, request.text(), mode, access)
                : new SourceUsageRanker.Decision(base, "SYNTAX_BYPASS");
        List<AuditCandidate> rows = new ArrayList<>();
        for (var vector : vectors)
          rows.add(
              new AuditCandidate(
                  vector.candidate().key(),
                  lease
                      .reader()
                      .storedFields()
                      .document(vector.candidate().document(), Set.of("path"))
                      .get("path"),
                  vector.values()));
        if (authorization.current(access))
          return new SourceAudit(
              new CandidateAudit(
                  lease.id(),
                  rankingIdentity(),
                  SearchFeatureSchema.version(ranking.strategy().dimensions()),
                  rows),
              usage.candidates().stream().map(CandidateRetriever.Candidate::key).toList(),
              usage.reason());
      }
    }
    throw new SecurityException("authorization changed; retry");
  }

  public List<String> suggest(String principal, String prefix) throws IOException {
    return suggest(authorization.current(principal), prefix);
  }

  /** Refresh permissions within the authenticated tenant, retaining the same boundary as search. */
  public List<String> suggest(AuthorizationFilter.Access authenticated, String prefix)
      throws IOException {
    Objects.requireNonNull(authenticated);
    if (prefix == null || !prefix.matches("[\\p{L}\\p{N}_$]{1,64}"))
      throw new IllegalArgumentException("prefix");
    for (int retry = 0; retry < 3; retry++) {
      var a = currentInTenant(authenticated);
      try (var lease = generations(a.tenant()).acquire()) {
        Query q =
            authorization.apply(
                new PrefixQuery(new Term("symbol", prefix.toLowerCase(Locale.ROOT))), a);
        List<String> out = new ArrayList<>();
        for (var c : new CandidateRetriever().retrieve(lease, q)) {
          var d = lease.reader().storedFields().document(c.document(), Set.of("symbol"));
          for (String symbol : d.getValues("symbol"))
            if (symbol.startsWith(prefix.toLowerCase(Locale.ROOT))
                && !out.contains(symbol)
                && out.size() < 20) out.add(symbol);
        }
        Collections.sort(out);
        if (authorization.current(a)) return List.copyOf(out);
      }
    }
    throw new SecurityException("authorization changed; retry");
  }

  public IngestionJob.Registered registerReindex(AuthorizationFilter.Access a) throws IOException {
    if (!a.administrator() || !authorization.current(a))
      throw new SecurityException("access denied");
    var first =
        generations(a.tenant()).sources().stream()
            .filter(source -> a.repositories().contains(source.repository()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("nothing to reindex"));
    // Empty partial input requests an exact rebuild of committed state; source revisions stay
    // unchanged.
    SnapshotManifest manifest =
        new SnapshotManifest(
            a.tenant(), first.repository(), "reindex-request", false, List.of(), Set.of());
    return register(a, "reindex-" + UUID.randomUUID(), manifest);
  }

  public void reindex(AuthorizationFilter.Access a) throws IOException {
    var job = registerReindex(a);
    resume(a, job.id(), GenerationBuilder.Fault.none(), () -> false);
  }

  @Override
  public void close() throws IOException {
    ingestion.close();
    cache.clear();
  }
}
