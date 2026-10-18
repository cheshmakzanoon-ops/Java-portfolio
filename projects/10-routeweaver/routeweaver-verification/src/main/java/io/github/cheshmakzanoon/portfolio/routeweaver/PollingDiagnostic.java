package io.github.cheshmakzanoon.portfolio.routeweaver;

import com.zaxxer.hikari.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Shared-host JDBC call-count diagnosis; timings are not quiet-host performance certification. */
public final class PollingDiagnostic {
  private PollingDiagnostic() {}

  public static void run() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        System.getenv()
            .getOrDefault("ROUTEWEAVER_DB_URL", "jdbc:postgresql://127.0.0.1:19001/routeweaver"));
    config.setUsername(System.getenv().getOrDefault("ROUTEWEAVER_DB_USER", "routeweaver"));
    config.setPassword(
        System.getenv().getOrDefault("ROUTEWEAVER_DB_PASSWORD", "routeweaver-local-disposable"));
    config.setMaximumPoolSize(8);
    config.setConnectionTimeout(5000);
    try (HikariDataSource database = new HikariDataSource(config)) {
      Store store = new Store(database);
      store.initialize();
      try (var c = database.getConnection();
          var statement = c.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT count(*) FROM solve_job WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')")) {
        rows.next();
        if (rows.getInt(1) != 0)
          throw new IllegalStateException(
              "polling diagnostic requires an idle owned database and no service worker");
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
      Model.Problem original = Fixtures.problem(100, 5, 4202, "overconstrained");
      Model.Problem p =
          new Model.Problem(
              original.tenant(),
              "routeweaver-poll-diagnostic",
              original.version(),
              original.serviceZone(),
              original.depot(),
              original.planningInstant(),
              original.matrix(),
              original.jobs(),
              original.vehicles(),
              original.completed(),
              original.previous(),
              original.objective(),
              original.audit());
      store.create(p);
      List<Map<String, Object>> results = new ArrayList<>();
      for (int run = 0; run < 3; run++) {
        Model.Score expected = null;
        for (boolean sampled : new boolean[] {false, true}) {
          var submitted =
              store.submit(
                  "demo",
                  p.id(),
                  new Store.SolveRequest(1, "poll-" + run + "-" + sampled, 42, 30000, 100000),
                  System.getenv()
                      .getOrDefault("ROUTEWEAVER_SOURCE_REVISION", "working-tree-unverified"),
                  17);
          var job = store.claim("owned-polling-diagnostic", 15);
          if (job == null || !job.id().equals(submitted.id()))
            throw new IllegalStateException("another worker raced the diagnostic");
          ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
          heartbeat.scheduleWithFixedDelay(() -> store.heartbeat(job, 15), 3, 3, TimeUnit.SECONDS);
          try {
            AtomicLong reads = new AtomicLong();
            BooleanSupplier remote =
                () -> {
                  reads.incrementAndGet();
                  return store.cancellation(job);
                };
            BooleanSupplier cancellation =
                sampled ? new CancellationPoll(remote, System::nanoTime) : remote;
            long started = System.nanoTime(), deadline = started + Duration.ofSeconds(30).toNanos();
            Model.Validation value =
                new Heuristics()
                    .baseline(
                        p, () -> System.nanoTime() >= deadline || cancellation.getAsBoolean());
            long elapsed = System.nanoTime() - started;
            if (!value.valid() || elapsed >= Duration.ofSeconds(30).toNanos())
              throw new IllegalStateException(
                  "diagnostic baseline did not complete validly within its safety budget");
            if (!store.record(job, value) || !store.finish(job, null))
              throw new IllegalStateException("diagnostic lost write authority");
            if (!sampled) expected = value.score();
            else if (!value.score().equals(expected))
              throw new AssertionError("sampling changed a noncancelled deterministic baseline");
            results.add(
                Map.of(
                    "run",
                    run,
                    "sampled",
                    sampled,
                    "jdbcCancellationReads",
                    reads.get(),
                    "elapsedNanos",
                    elapsed,
                    "score",
                    value.score(),
                    "inputHash",
                    p.canonicalHash()));
          } finally {
            heartbeat.shutdownNow();
          }
        }
      }
      System.out.println(
          Json.write(
              Map.of(
                  "schemaVersion",
                  1,
                  "project",
                  "routeweaver",
                  "source",
                  System.getenv()
                      .getOrDefault("ROUTEWEAVER_SOURCE_REVISION", "working-tree-unverified"),
                  "boundary",
                  "pooled JDBC cancellation and deterministic 100-job baseline; shared host; diagnosis only",
                  "maximumPoolSize",
                  8,
                  "samplingIntervalMillis",
                  100,
                  "timestamp",
                  Instant.now().toString(),
                  "runs",
                  results)));
    }
  }
}
