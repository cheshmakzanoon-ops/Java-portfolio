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

  private final Map<String, java.util.concurrent.locks.ReentrantLock> writers =
      new ConcurrentHashMap<>();
  private final QueryPlanner.Mode defaultMode;
  private final RankingProfile rankingProfile;
  private final Path root;
  private final DataDirectoryLock dataLock;
  private final ContentAddressedStore cas;
  private final IngestionJob jobs;
  private final Map<String, GenerationPublisher> publishers = new ConcurrentHashMap<>();
  private final AuthorizationFilter authorization;
  private final SearchCursor cursors;
  private final Map<String, Response> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Response> eldest) {
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
    this.root = root;
    this.defaultMode = Objects.requireNonNull(defaultMode);
    this.rankingProfile = Objects.requireNonNull(rankingProfile);
    Files.createDirectories(root);
    dataLock = new DataDirectoryLock(root);
    try {
      this.authorization = authorization;
      cas = new ContentAddressedStore(root.resolve("cas"));
      jobs = new IngestionJob(root.resolve("jobs"));
      Files.createDirectories(root.resolve("index"));
      try (var tenants = Files.list(root.resolve("index"))) {
        for (Path tenant : tenants.filter(Files::isDirectory).toList()) {
          String name = tenant.getFileName().toString();
          if (!name.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IOException("invalid tenant directory");
          GenerationPublisher publisher = new GenerationPublisher(tenant);
          publishers.put(name, publisher);
          reconcile(publisher);
        }
      }
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
      cursors = new SearchCursor(Files.readAllBytes(secret), Clock.systemUTC());
    } catch (IOException | RuntimeException failure) {
      for (var publisher : publishers.values()) publisher.close();
      dataLock.close();
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

  private void reconcile(GenerationPublisher publisher) throws IOException {
    var committed = publisher.committedJob();
    if (committed != null)
      jobs.complete(committed.id(), committed.manifestHash(), committed.generation());
  }

  public long cacheHits() {
    return cacheHits.get();
  }

  public ContentAddressedStore contentStore() {
    return cas;
  }

  public synchronized GenerationPublisher generations(String tenant) throws IOException {
    if (!tenant.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("tenant");
    GenerationPublisher p = publishers.get(tenant);
    if (p == null) {
      p = new GenerationPublisher(root.resolve("index").resolve(tenant));
      publishers.put(tenant, p);
    }
    return p;
  }

  public IngestionJob.Registered register(
      AuthorizationFilter.Access a, String key, SnapshotManifest m) throws IOException {
    authorization.require(a, m.tenantId(), m.repositoryId());
    return jobs.register(key, m);
  }

  public boolean completed(String id) {
    try {
      return id.matches("[a-f0-9]{64}") && jobs.completed(id);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  public IngestionJob.Registered ingest(
      AuthorizationFilter.Access a,
      String key,
      SnapshotManifest m,
      GenerationBuilder.Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    authorization.require(a, m.tenantId(), m.repositoryId());
    IngestionJob.Registered job = jobs.register(key, m);
    return executeRegistered(a, job, fault, cancelled);
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
    return executeRegistered(a, job(a, id), fault, cancelled);
  }

  private IngestionJob.Registered executeRegistered(
      AuthorizationFilter.Access a,
      IngestionJob.Registered job,
      GenerationBuilder.Fault fault,
      BooleanSupplier cancelled)
      throws IOException {
    var writer =
        writers.computeIfAbsent(
            a.tenant(), ignored -> new java.util.concurrent.locks.ReentrantLock());
    try {
      writer.lockInterruptibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("cancelled", interrupted);
    }
    try {
      SnapshotManifest m = job.manifest();
      GenerationPublisher publisher = generations(a.tenant());
      reconcile(publisher);
      if (jobs.completed(job.id())) return job;
      GenerationBuilder builder =
          new GenerationBuilder(root.resolve("index").resolve(a.tenant()), cas);
      var built = builder.build(m, publisher.sources(), fault, cancelled, job.id());
      authorization.require(a, m.tenantId(), m.repositoryId());
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new IOException("cancelled");
      publisher.publish(built, fault);
      reconcile(publisher);
      return job;
    } finally {
      writer.unlock();
    }
  }

  public IngestionJob.Registered job(AuthorizationFilter.Access a, String id) throws IOException {
    var j = jobs.get(id);
    authorization.require(a, j.manifest().tenantId(), j.manifest().repositoryId());
    return j;
  }

  public Response search(String principal, Request request) throws IOException {
    return search(principal, request, defaultMode);
  }

  public Response search(String principal, Request request, QueryPlanner.Mode mode)
      throws IOException {
    QueryAst ast = QueryAst.parse(request.text());
    String scoringVersion =
        mode == QueryPlanner.Mode.BASELINE
            ? "bm25-content-v1"
            : QueryPlanner.VERSION + "-" + rankingProfile.name();
    String digest =
        Digests.sha256(
            request.text()
                + "\n"
                + new TreeMap<>(request.filters())
                + "\n"
                + request.pageSize()
                + "\n"
                + mode
                + "\n"
                + scoringVersion);
    for (int retry = 0; retry < 3; retry++) {
      AuthorizationFilter.Access a = authorization.current(principal);
      SearchCursor.Position position =
          request.cursor() == null ? null : cursors.decode(request.cursor(), a, digest);
      try (GenerationLease lease =
          generations(a.tenant()).acquire(position == null ? null : position.generation())) {
        if (mode != QueryPlanner.Mode.BASELINE && mode != QueryPlanner.Mode.NO_SIMILARITY)
          lease
              .searcher()
              .setSimilarity(
                  new org.apache.lucene.search.similarities.BM25Similarity(
                      rankingProfile.k1, rankingProfile.b));
        String cacheKey =
            a.toString()
                + "/"
                + new TreeSet<>(a.repositories())
                + "/"
                + lease.id()
                + "/"
                + digest
                + "/"
                + (position == null ? 0 : position.offset());
        Response cached = cache.get(cacheKey);
        if (cached != null && authorization.current(a)) {
          cacheHits.incrementAndGet();
          return cached;
        }
        BooleanQuery.Builder qb =
            new BooleanQuery.Builder()
                .add(
                    new QueryPlanner(rankingProfile).plan(ast, mode, request.text()),
                    BooleanClause.Occur.MUST);
        for (var f : request.filters().entrySet())
          qb.add(new TermQuery(new Term(f.getKey(), f.getValue())), BooleanClause.Occur.FILTER);
        Query authorized = authorization.apply(qb.build(), a);
        var ranked =
            new Ranker(rankingProfile)
                .rank(
                    new CandidateRetriever().retrieve(lease, authorized),
                    lease,
                    request.text(),
                    mode);
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
        String next = end < ranked.size() ? cursors.encode(a, digest, lease.id(), end) : null;
        Response response =
            new Response(
                lease.id(),
                scoringVersion,
                count,
                "EQUAL_TO",
                List.copyOf(hits),
                Map.copyOf(facets),
                next);
        if (authorization.current(a)) {
          cache.put(cacheKey, response);
          return response;
        }
      }
    }
    throw new SecurityException("authorization changed; retry");
  }

  public List<String> suggest(String principal, String prefix) throws IOException {
    if (prefix == null || !prefix.matches("[\\p{L}\\p{N}_$]{1,64}"))
      throw new IllegalArgumentException("prefix");
    for (int retry = 0; retry < 3; retry++) {
      var a = authorization.current(principal);
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
    for (GenerationPublisher p : publishers.values()) p.close();
    dataLock.close();
  }
}
