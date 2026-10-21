package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchController implements AutoCloseable {
  public record IngestRequest(
      String repository,
      String revision,
      String idempotencyKey,
      String path,
      Boolean archive,
      Boolean complete,
      Set<String> deletions) {
    public IngestRequest {
      if (repository == null
          || !repository.matches("[a-zA-Z0-9_-]{1,64}")
          || revision == null
          || revision.isBlank()
          || revision.length() > 128
          || idempotencyKey == null
          || idempotencyKey.isBlank()
          || idempotencyKey.length() > 128
          || path == null
          || path.isBlank()
          || path.length() > 4096
          || deletions != null && deletions.size() > 100_000)
        throw new IllegalArgumentException("ingestion field bounds");
      archive = Boolean.TRUE.equals(archive);
      complete = Boolean.TRUE.equals(complete);
      if (deletions == null) deletions = Set.of();
      else {
        for (String deletion : deletions) SnapshotManifest.normalize(deletion);
        deletions = Set.copyOf(deletions);
      }
    }
  }

  public record SuggestRequest(String prefix) {}

  private final SearchEngine engine;
  private final AuthorizationFilter auth;
  private final Path sources;
  private final IngestionTasks tasks = new IngestionTasks();

  private final ConcurrentMap<String, AtomicLong> tenantSearches = new ConcurrentHashMap<>();

  public SearchController(SearchEngine engine, AuthorizationFilter auth, Environment env)
      throws IOException {
    this.engine = engine;
    this.auth = auth;
    sources = Path.of(env.getProperty("indexforge.sources", ".runtime/sources"));
    Files.createDirectories(sources);
  }

  private AuthorizationFilter.Access context(Jwt jwt, boolean write) {
    if (jwt == null) throw new SecurityException("access denied");
    var a = auth.current(jwt.getSubject());
    if (!a.tenant().equals(jwt.getClaimAsString("tenant"))
        || (write && !Arrays.asList(jwt.getClaimAsString("scope").split(" ")).contains("ingest")))
      throw new SecurityException("access denied");
    return a;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @PostMapping("/v1/search")
  SearchEngine.Response search(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SearchEngine.Request request)
      throws IOException {
    var a = context(jwt, false);
    tenantSearches.computeIfAbsent(a.tenant(), ignored -> new AtomicLong()).incrementAndGet();
    return engine.search(a.principal(), request);
  }

  @PostMapping("/v1/suggest")
  List<String> suggest(@AuthenticationPrincipal Jwt jwt, @RequestBody SuggestRequest request)
      throws IOException {
    return engine.suggest(context(jwt, false).principal(), request.prefix());
  }

  @PostMapping("/v1/ingestions")
  ResponseEntity<Map<String, String>> ingest(
      @AuthenticationPrincipal Jwt jwt, @RequestBody IngestRequest request) throws IOException {
    var a = context(jwt, true);
    auth.require(a, a.tenant(), request.repository());
    if (request.path() == null || request.revision() == null || request.idempotencyKey() == null)
      throw new IllegalArgumentException("missing ingestion field");
    Path tenantRoot = sources.resolve(a.tenant()),
        repositoryRoot = tenantRoot.resolve(request.repository());
    if (Files.isSymbolicLink(tenantRoot) || Files.isSymbolicLink(repositoryRoot))
      throw new IOException("source binding symlinks prohibited");
    SafeSourceReader reader =
        new SafeSourceReader(
            repositoryRoot, SafeSourceReader.Limits.defaults(), engine.contentStore());
    SnapshotManifest manifest =
        request.archive()
            ? reader.archive(
                a.tenant(),
                request.repository(),
                request.revision(),
                Path.of(request.path()),
                () -> false)
            : reader.directory(
                a.tenant(),
                request.repository(),
                request.revision(),
                Path.of(request.path()),
                request.complete(),
                request.deletions(),
                () -> false);
    var job = engine.register(a, request.idempotencyKey(), manifest);
    return enqueue(a, job);
  }

  private ResponseEntity<Map<String, String>> enqueue(
      AuthorizationFilter.Access access, IngestionJob.Registered job) {
    if (!engine.completed(job.id()))
      tasks.submit(access.tenant(), job.id(), () -> engine.resume(access, job.id()));
    return ResponseEntity.accepted().body(Map.of("id", job.id(), "state", state(job.id())));
  }

  private String state(String id) {
    // A completion receipt is authoritative even if cancellation raced with publication,
    // an in-memory outcome was evicted, or the service restarted.
    return engine.completed(id) ? "COMPLETE" : tasks.state(id).name();
  }

  @PostMapping("/v1/ingestions/{id}/resume")
  ResponseEntity<Map<String, String>> resume(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id) throws IOException {
    var access = context(jwt, true);
    return enqueue(access, engine.job(access, id));
  }

  @GetMapping("/v1/ingestions/{id}")
  Map<String, String> job(@AuthenticationPrincipal Jwt jwt, @PathVariable String id)
      throws IOException {
    engine.job(context(jwt, false), id);
    return Map.of("id", id, "state", state(id));
  }

  @DeleteMapping("/v1/ingestions/{id}")
  Map<String, String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id)
      throws IOException {
    engine.job(context(jwt, true), id);
    tasks.cancel(id);
    return Map.of("id", id, "state", state(id));
  }

  @PostMapping("/v1/reindexes")
  ResponseEntity<Map<String, String>> reindex(@AuthenticationPrincipal Jwt jwt) throws IOException {
    var access = context(jwt, true);
    return enqueue(access, engine.registerReindex(access));
  }

  @GetMapping("/v1/metrics")
  Map<String, Long> metrics(@AuthenticationPrincipal Jwt jwt) {
    var access = context(jwt, false);
    if (!access.administrator()) throw new SecurityException("access denied");
    var counts = tasks.counts(access.tenant());
    var searches = tenantSearches.get(access.tenant());
    return Map.of(
        "searches",
        searches == null ? 0L : searches.get(),
        "jobFailures",
        counts.failures(),
        "queuedJobs",
        (long) counts.queued());
  }

  @Override
  public void close() throws IOException {
    tasks.close();
  }
}
