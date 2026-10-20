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
    }
  }

  public record SuggestRequest(String prefix) {}

  private final SearchEngine engine;
  private final AuthorizationFilter auth;
  private final Path sources;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          Thread.ofPlatform().name("indexforge-ingest-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  private record Pending(String tenant, Future<?> task) {}

  private static final class Metrics {
    final AtomicLong searches = new AtomicLong(), failures = new AtomicLong();
  }

  private final ConcurrentMap<String, Pending> futures = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Metrics> tenantMetrics = new ConcurrentHashMap<>();
  private final Map<String, String> states =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
              return size() > 512;
            }
          });

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
    counters(a.tenant()).searches.incrementAndGet();
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
                request.deletions() == null ? Set.of() : request.deletions(),
                () -> false);
    var job = engine.register(a, request.idempotencyKey(), manifest);
    return enqueue(a, job);
  }

  private ResponseEntity<Map<String, String>> enqueue(
      AuthorizationFilter.Access access, IngestionJob.Registered job) {
    synchronized (futures) {
      futures.entrySet().removeIf(entry -> entry.getValue().task().isDone());
      if (!futures.containsKey(job.id()) && !engine.completed(job.id())) {
        FutureTask<Void> task =
            new FutureTask<>(
                () -> {
                  states.put(job.id(), "RUNNING");
                  try {
                    engine.resume(access, job.id());
                    states.put(job.id(), "COMPLETE");
                  } catch (Exception failed) {
                    states.put(
                        job.id(), Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED");
                    counters(access.tenant()).failures.incrementAndGet();
                  }
                  return null;
                });
        states.put(job.id(), "QUEUED");
        try {
          workers.execute(task);
        } catch (RejectedExecutionException full) {
          states.put(job.id(), "REGISTERED");
          throw full;
        }
        futures.put(job.id(), new Pending(access.tenant(), task));
      }
    }
    return ResponseEntity.accepted()
        .body(
            Map.of(
                "id",
                job.id(),
                "state",
                engine.completed(job.id())
                    ? "COMPLETE"
                    : states.getOrDefault(job.id(), "REGISTERED")));
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
    return Map.of(
        "id",
        id,
        "state",
        engine.completed(id) ? "COMPLETE" : states.getOrDefault(id, "REGISTERED"));
  }

  @DeleteMapping("/v1/ingestions/{id}")
  Map<String, String> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id)
      throws IOException {
    engine.job(context(jwt, true), id);
    Pending pending = futures.get(id);
    if (pending != null && pending.task().cancel(true)) states.put(id, "CANCELLED");
    return Map.of("id", id, "state", states.getOrDefault(id, "REGISTERED"));
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
    Metrics counters = counters(access.tenant());
    long queued =
        futures.entrySet().stream()
            .filter(entry -> entry.getValue().tenant().equals(access.tenant()))
            .filter(entry -> !entry.getValue().task().isDone())
            .filter(entry -> "QUEUED".equals(states.get(entry.getKey())))
            .count();
    return Map.of(
        "searches",
        counters.searches.get(),
        "jobFailures",
        counters.failures.get(),
        "queuedJobs",
        queued);
  }

  private Metrics counters(String tenant) {
    return tenantMetrics.computeIfAbsent(tenant, ignored -> new Metrics());
  }

  @Override
  public void close() throws IOException {
    for (Pending pending : futures.values()) pending.task().cancel(true);
    for (Runnable queued : workers.shutdownNow())
      if (queued instanceof Future<?> future) future.cancel(false);
    try {
      if (!workers.awaitTermination(10, TimeUnit.SECONDS))
        throw new IOException("ingestion shutdown deadline exceeded");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("ingestion shutdown interrupted", interrupted);
    }
  }
}
