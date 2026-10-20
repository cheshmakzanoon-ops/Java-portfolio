package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PostgresIT {
  @Container
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          org.testcontainers.utility.DockerImageName.parse(
                  "postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
              .asCompatibleSubstituteFor("postgres"));

  static PGSimpleDataSource ds;
  Store store;

  @BeforeAll
  static void init() {
    ds = new PGSimpleDataSource();
    ds.setURL(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    new Store(ds).initialize();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = ds.getConnection();
        var s = c.createStatement()) {
      s.execute(
          "TRUNCATE plan_event,published_plan,solution_candidate,solve_job,active_problem,problem_snapshot CASCADE");
    }
    store = new Store(ds);
    store.create(problem("demo"));
  }

  static Problem problem(String tenant) {
    Instant n = Instant.parse("2026-10-17T11:00:00Z");
    return new Problem(
        tenant,
        "p",
        1,
        ZoneId.of("America/Halifax"),
        0,
        n,
        new Matrix(
            "m",
            List.of(
                List.of(new Arc(0, 0), new Arc(10, 100)),
                List.of(new Arc(10, 100), new Arc(0, 0)))),
        List.of(new Job("j", 1, 1000, 10, n, n.plusSeconds(1000), Set.of(), 1)),
        List.of(
            new Vehicle(
                "v", 2000, Set.of(), n, n.plusSeconds(3600), 0, n, Set.of(), 0, List.of(), false)),
        List.of(),
        Map.of(),
        new Objective("o", 1, 1, 100),
        List.of());
  }

  Store.JobView submit(String key) {
    return store.submit(
        "demo", "p", new Store.SolveRequest(1, key, 42, 1000, 30), "tested-source", 17);
  }

  Store.JobView completed(String key) {
    var queued = submit(key);
    var worker = store.claim("worker", 15);
    assertEquals(queued.id(), worker.id());
    assertTrue(store.record(worker, new Heuristics().baseline(problem("demo"))));
    assertTrue(store.finish(worker, null));
    return store.job("demo", worker.id());
  }

  @Test
  void immutableSnapshotIdempotencyAndCrossTenant() {
    var q = submit("one");
    assertEquals(q.id(), submit("one").id());
    assertThrows(
        Store.Conflict.class,
        () ->
            store.submit(
                "demo", "p", new Store.SolveRequest(1, "one", 43, 1000, 30), "tested-source", 17));
    assertThrows(Store.Conflict.class, () -> store.create(problem("demo")));
    assertThrows(Store.Missing.class, () -> store.job("another", q.id()));
    assertThrows(Store.Missing.class, () -> store.load("another", "p", 1));
    assertEquals(problem("demo").canonicalHash(), store.load("demo", "p", 1).canonicalHash());
  }

  @Test
  void admissionBoundsAndQueuedCancellation() {
    for (int i = 0; i < 17; i++) submit("q" + i);
    assertThrows(Store.Busy.class, () -> submit("overflow"));
    var q = submit("q0");
    assertEquals("CANCELLED", store.cancel("demo", q.id()).state());
    assertEquals("CANCELLED", store.cancel("demo", q.id()).state());
    assertNotNull(submit("new-capacity"));
  }

  @Test
  void cancellationSerializesBeforeCompletionAndIncumbentRetained() {
    var q = submit("cancel");
    var worker = store.claim("w", 15);
    assertTrue(store.record(worker, new Heuristics().baseline(problem("demo"))));
    assertEquals("CANCEL_REQUESTED", store.cancel("demo", q.id()).state());
    assertTrue(store.cancellation(worker));
    assertTrue(store.finish(worker, null));
    var cancelled = store.job("demo", q.id());
    assertEquals("CANCELLED", cancelled.state());
    assertNotNull(cancelled.incumbent());
    assertNull(store.plan("demo", "p").solution());
    assertNotNull(store.publish("demo", "p", new Store.PublishRequest(q.id(), 1, 0)).solution());
  }

  @Test
  void completionFirstMakesCancellationIdempotent() {
    var done = completed("done");
    assertEquals("COMPLETED", store.cancel("demo", done.id()).state());
    assertFalse(store.finish(done, null));
  }

  @Test
  void leaseRecoveryFencesExpiredWorkerAndStoresHistory() throws Exception {
    submit("recover");
    var stale = store.claim("old", 15);
    expire(stale);
    assertEquals(1, store.recover());
    Store restored = new Store(ds);
    var fresh = restored.claim("new", 15);
    assertTrue(fresh.fence() > stale.fence());
    Validation v = new Heuristics().baseline(problem("demo"));
    assertFalse(restored.record(stale, v));
    assertFalse(restored.finish(stale, null));
    assertTrue(restored.record(fresh, v));
    assertTrue(restored.finish(fresh, null));
    assertNotNull(restored.job("demo", fresh.id()).incumbent());
    try (var c = ds.getConnection();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT count(*) FROM solution_candidate WHERE NOT authoritative")) {
      assertTrue(r.next());
      assertEquals(1, r.getInt(1));
    }
  }

  @Test
  void cancelledExpiredLeaseRecoversTerminal() throws Exception {
    var queued = submit("cancel-recover");
    var claimed = store.claim("old", 15);
    store.cancel("demo", queued.id());
    expire(claimed);
    store.recover();
    assertEquals("CANCELLED", new Store(ds).job("demo", queued.id()).state());
    assertNull(store.claim("new", 15));
  }

  @Test
  void concurrentPublishHasOneWinnerAndStaleVersionRejected() throws Exception {
    var done = completed("publish");
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        results.add(
            threads.submit(
                () -> {
                  start.await();
                  try {
                    store.publish("demo", "p", new Store.PublishRequest(done.id(), 1, 0));
                    return true;
                  } catch (Store.Conflict expected) {
                    return false;
                  }
                }));
      start.countDown();
      int winners = 0;
      for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
      assertEquals(1, winners);
      assertEquals(1, store.plan("demo", "p").revision());
      Problem revised =
          store.replan(
              "demo",
              "p",
              1,
              1,
              problem("demo").planningInstant(),
              List.of(new Replanner.Observation("v", 0, false, 1, false)),
              List.of());
      assertEquals(2, revised.version());
      assertThrows(
          Store.Conflict.class,
          () -> store.publish("demo", "p", new Store.PublishRequest(done.id(), 1, 1)));
      assertEquals(1, store.plan("demo", "p").problemVersion());
    } finally {
      threads.shutdownNow();
    }
  }

  @Test
  void invalidCandidateNeverReplacesValidatedIncumbent() {
    submit("invalid");
    var worker = store.claim("w", 15);
    Validation good = new Heuristics().baseline(problem("demo"));
    assertTrue(store.record(worker, good));
    Candidate bad = Model.candidate(problem("demo"), List.of(new Route("v", List.of("j", "j"))));
    assertFalse(
        store.record(
            worker, new Validation(bad, new Score(0, 0, 0, 0), List.of(), List.of(), Map.of())));
    assertEquals(good.score(), store.job("demo", worker.id()).incumbent().score());
  }

  @Test
  void protectedHttpUsesLocalOidcAndBoundsRawChunkedBody() throws Exception {
    try (LocalIssuer issuer = new LocalIssuer();
        var context =
            new org.springframework.boot.builder.SpringApplicationBuilder(
                    RouteWeaverApplication.class)
                .run(
                    "--server.port=0",
                    "--server.address=127.0.0.1",
                    "--routeweaver.auth-mode=protected",
                    "--routeweaver.issuer=" + issuer.issuer,
                    "--spring.datasource.url=" + PG.getJdbcUrl(),
                    "--spring.datasource.username=" + PG.getUsername(),
                    "--spring.datasource.password=" + PG.getPassword())) {
      int port =
          ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)
                  context)
              .getWebServer()
              .getPort();
      String base = "http://127.0.0.1:" + port;
      var client = java.net.http.HttpClient.newHttpClient();
      var get =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/plans/p"))
              .header("Authorization", "Bearer " + issuer.token(Map.of()))
              .GET()
              .build();
      assertEquals(
          200, client.send(get, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
      var cross =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/plans/p"))
              .header("Authorization", "Bearer " + issuer.token(Map.of("tenant", "another")))
              .GET()
              .build();
      assertEquals(
          404, client.send(cross, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
      var scope =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/problems"))
              .header(
                  "Authorization", "Bearer " + issuer.token(Map.of("scope", "routeweaver.read")))
              .header("Content-Type", "application/json")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(problem("other"))))
              .build();
      assertEquals(
          403, client.send(scope, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
      var expired =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/plans/p"))
              .header(
                  "Authorization",
                  "Bearer "
                      + issuer.token(
                          Map.of("exp", Instant.now().minusSeconds(300).getEpochSecond())))
              .GET()
              .build();
      assertEquals(
          401,
          client.send(expired, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
      var oversized =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/problems"))
              .header("Authorization", "Bearer " + issuer.token(Map.of()))
              .header("Content-Type", "application/json")
              .POST(
                  java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                      () -> new java.io.ByteArrayInputStream(new byte[Security.BODY_LIMIT + 1])))
              .build();
      assertEquals(
          413,
          client.send(oversized, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }

  @Test
  void databaseRefusesMutationOfImmutableInputAndCandidateSnapshots() throws Exception {
    var done = completed("immutable");
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE problem_snapshot SET snapshot='{}' WHERE tenant='demo'"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE solution_candidate SET validated='{}' WHERE tenant='demo'"));
    }
    assertEquals(problem("demo").canonicalHash(), store.load("demo", "p", 1).canonicalHash());
    assertTrue(store.job("demo", done.id()).incumbent().valid());
  }

  @Test
  void realConcurrentCancelAndCompletionAlwaysSerializeToLegalTerminal() throws Exception {
    for (int iteration = 0; iteration < 5; iteration++) {
      var queued = submit("race-cancel-" + iteration);
      var worker = store.claim("worker", 15);
      assertTrue(store.record(worker, new Heuristics().baseline(problem("demo"))));
      var threads = Executors.newFixedThreadPool(2);
      try {
        CountDownLatch start = new CountDownLatch(1);
        var cancel =
            threads.submit(
                () -> {
                  start.await();
                  return store.cancel("demo", queued.id());
                });
        var finish =
            threads.submit(
                () -> {
                  start.await();
                  return store.finish(worker, null);
                });
        start.countDown();
        cancel.get(10, TimeUnit.SECONDS);
        finish.get(10, TimeUnit.SECONDS);
        var observed = store.job("demo", queued.id());
        assertTrue(Set.of("COMPLETED", "CANCELLED").contains(observed.state()));
        assertNotNull(observed.incumbent());
        assertFalse(store.finish(worker, null));
      } finally {
        threads.shutdownNow();
      }
    }
  }

  @Test
  void completionAfterInputSupersededRemainsHistorical() {
    var initial = completed("initial");
    store.publish("demo", "p", new Store.PublishRequest(initial.id(), 1, 0));
    submit("old-worker");
    var running = store.claim("worker", 15);
    store.record(running, new Heuristics().baseline(problem("demo")));
    store.replan(
        "demo",
        "p",
        1,
        1,
        problem("demo").planningInstant(),
        List.of(new Replanner.Observation("v", 0, false, 1, false)),
        List.of());
    assertTrue(store.finish(running, null));
    assertEquals("COMPLETED", store.job("demo", running.id()).state());
    assertThrows(
        Store.Conflict.class,
        () -> store.publish("demo", "p", new Store.PublishRequest(running.id(), 1, 1)));
    assertEquals(1, store.plan("demo", "p").revision());
    assertEquals(2, store.active("demo", "p"));
  }

  @Test
  void backupRestorePreservesImmutableInputsValidatedPlanAndDurableQueue() throws Exception {
    Store.JobView done = completed("backup-completed");
    Store.PlanView published =
        store.publish("demo", "p", new Store.PublishRequest(done.id(), 1, 0));
    Store.JobView queued = submit("backup-queued");
    String restoredName = "routeweaver_restore_" + UUID.randomUUID().toString().replace("-", "");
    var dump =
        PG.execInContainer(
            "pg_dump",
            "-U",
            PG.getUsername(),
            "-d",
            PG.getDatabaseName(),
            "--no-owner",
            "--no-acl");
    assertEquals(0, dump.getExitCode(), dump.getStderr());
    var created = PG.execInContainer("createdb", "-U", PG.getUsername(), restoredName);
    assertEquals(0, created.getExitCode(), created.getStderr());
    try {
      PG.copyFileToContainer(
          org.testcontainers.images.builder.Transferable.of(dump.getStdout()),
          "/tmp/routeweaver-restore.sql");
      var imported =
          PG.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              PG.getUsername(),
              "-d",
              restoredName,
              "-f",
              "/tmp/routeweaver-restore.sql");
      assertEquals(0, imported.getExitCode(), imported.getStderr());
      PGSimpleDataSource restoredDataSource = new PGSimpleDataSource();
      restoredDataSource.setURL(
          PG.getJdbcUrl().replace("/" + PG.getDatabaseName(), "/" + restoredName));
      restoredDataSource.setUser(PG.getUsername());
      restoredDataSource.setPassword(PG.getPassword());
      Store restored = new Store(restoredDataSource);
      Problem input = restored.load("demo", "p", 1);
      assertEquals(problem("demo").canonicalHash(), input.canonicalHash());
      assertEquals(published, restored.plan("demo", "p"));
      assertTrue(
          new Validator()
              .validate(input, restored.plan("demo", "p").solution().candidate())
              .valid());
      assertEquals("QUEUED", restored.job("demo", queued.id()).state());
      assertEquals(
          queued.id(),
          restored
              .submit(
                  "demo",
                  "p",
                  new Store.SolveRequest(1, "backup-queued", 42, 1000, 30),
                  "tested-source",
                  17)
              .id());
      try (var c = restoredDataSource.getConnection();
          var statement = c.createStatement()) {
        var refused =
            assertThrows(
                java.sql.SQLException.class,
                () -> statement.execute("UPDATE problem_snapshot SET snapshot=snapshot"));
        assertEquals("55000", refused.getSQLState());
        assertTrue(refused.getMessage().contains("immutable"));
      }
    } finally {
      var dropped = PG.execInContainer("dropdb", "-U", PG.getUsername(), restoredName);
      assertEquals(0, dropped.getExitCode(), dropped.getStderr());
    }
  }

  @Test
  void dispatcherFencesSourceMismatchAndResumesOnlyMatchingSourceWithoutMislabeledCandidates()
      throws Exception {
    var old =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "expired-old-source", 42, 2000, 30),
            "source-v1",
            17);
    var oldWorker = store.claim("old-v1", 15);
    assertEquals(old.id(), oldWorker.id());
    var baseline = new Heuristics().baseline(problem("demo"));
    assertTrue(store.record(oldWorker, baseline));
    var same =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "expired-same-source", 42, 2000, 30),
            "source-v2",
            17);
    var sameWorker = store.claim("previous-v2", 15);
    assertEquals(same.id(), sameWorker.id());
    assertTrue(store.record(sameWorker, baseline));
    var queued =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "queued-old-source", 42, 2000, 30),
            "source-v1",
            17);
    expire(oldWorker);
    expire(sameWorker);
    try (Dispatcher dispatcher = new Dispatcher(store, "source-v2")) {
      Store.JobView oldAfter = awaitTerminal(old.id());
      assertEquals("FAILED", oldAfter.state());
      assertTrue(
          oldAfter
              .failure()
              .startsWith("SOURCE_REVISION_MISMATCH submitted=source-v1 current=source-v2"));
      assertEquals(baseline, oldAfter.incumbent());
      assertEquals("source-v1", oldAfter.sourceRevision());
      assertTrue(oldAfter.fence() > oldWorker.fence());
      var oldTrace = store.trace("demo", old.id(), 0, 1000);
      assertEquals(
          1, oldTrace.size(), "new binary must not emit candidates labeled with old source");
      assertEquals(oldWorker.fence(), oldTrace.getFirst().fence());
      assertFalse(store.finish(oldWorker, null));
      assertThrows(
          Store.Conflict.class,
          () -> store.publish("demo", "p", new Store.PublishRequest(old.id(), 1, 0)));
      Store.JobView queuedAfter = awaitTerminal(queued.id());
      assertEquals("FAILED", queuedAfter.state());
      assertTrue(queuedAfter.failure().contains("SOURCE_REVISION_MISMATCH"));
      assertNull(queuedAfter.incumbent());
      assertTrue(store.trace("demo", queued.id(), 0, 1000).isEmpty());
      Store.JobView sameAfter = awaitTerminal(same.id());
      assertEquals("COMPLETED", sameAfter.state());
      assertEquals("source-v2", sameAfter.sourceRevision());
      assertTrue(sameAfter.fence() > sameWorker.fence());
      assertNotNull(sameAfter.incumbent());
      assertTrue(sameAfter.incumbent().score().compareTo(baseline.score()) <= 0);
      assertTrue(
          new Validator().validate(problem("demo"), sameAfter.incumbent().candidate()).valid());
      assertTrue(
          store.trace("demo", same.id(), 0, 1000).stream()
              .anyMatch(point -> point.authoritative() && point.fence() == sameAfter.fence()));
      var replacement =
          store.submit(
              "demo",
              "p",
              new Store.SolveRequest(1, "replacement-new-source", 42, 2000, 30),
              "source-v2",
              17);
      assertEquals("COMPLETED", awaitTerminal(replacement.id()).state());
    }
  }

  @Test
  void authoritativeCandidateIdentityComesFromTheLockedJobNotTheCaller() {
    Problem original = problem("demo");
    Problem other =
        new Problem(
            original.tenant(),
            "other",
            1,
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
    store.create(other);
    submit("forged-identity");
    Store.JobView worker = store.claim("owner", 15);
    Map<String, Object> changed = Json.read(Json.write(worker), Map.class);
    changed.put("problemId", "other");
    Store.JobView forged = Json.read(Json.write(changed), Store.JobView.class);
    assertFalse(store.record(forged, new Heuristics().baseline(other)));
    assertNull(store.job("demo", worker.id()).incumbent());
    assertTrue(store.record(worker, new Heuristics().baseline(original)));
    assertEquals(
        original.canonicalHash(),
        store.job("demo", worker.id()).incumbent().candidate().inputHash());
  }

  @Test
  void eventWriteFailureRollsBackEveryProblemCreationWrite() {
    Store broken =
        new Store(
            intercept(
                (sql, after) -> {
                  if (!after && sql.startsWith("INSERT INTO plan_event"))
                    throw new java.sql.SQLException("injected event storage failure");
                }));
    assertThrows(IllegalStateException.class, () -> broken.create(problem("rollback")));
    assertThrows(Store.Missing.class, () -> store.load("rollback", "p", 1));
    assertThrows(Store.Missing.class, () -> store.active("rollback", "p"));
    assertThrows(Store.Missing.class, () -> store.plan("rollback", "p"));
    assertNotNull(store.create(problem("rollback")));
  }

  @Test
  void migrationIsRepeatableAndDatabaseRejectsCrossJobIncumbentsAndIllegalStates()
      throws Exception {
    Store.JobView first = completed("first-for-fk");
    Store.JobView second = completed("second-for-fk");
    new Store(ds).initialize();
    new Store(ds).initialize();
    assertEquals(first.incumbent(), store.job("demo", first.id()).incumbent());
    try (var c = ds.getConnection();
        var statement = c.createStatement()) {
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE solve_job SET best_candidate='"
                      + first.bestCandidate()
                      + "' WHERE id='"
                      + second.id()
                      + "'"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate(
                  "UPDATE solve_job SET state='RUNNING',lease_until=NULL WHERE id='"
                      + second.id()
                      + "'"));
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.executeUpdate("UPDATE published_plan SET revision=-1 WHERE tenant='demo'"));
    }
  }

  @Test
  void rowLockBarriersForceBothCancellationCompletionOrders() throws Exception {
    for (boolean cancelFirst : new boolean[] {true, false}) {
      var queued = submit("ordered-" + cancelFirst);
      var worker = store.claim("owner", 15);
      store.record(worker, new Heuristics().baseline(problem("demo")));
      CountDownLatch locked = new CountDownLatch(1),
          release = new CountDownLatch(1),
          secondEntered = new CountDownLatch(1);
      Store gated =
          new Store(
              intercept(
                  (sql, after) -> {
                    if (after
                        && sql.startsWith("SELECT state FROM solve_job")
                        && Thread.currentThread().getName().equals("decision-first")) {
                      locked.countDown();
                      assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                  }));
      try (ExecutorService first =
              Executors.newSingleThreadExecutor(
                  Thread.ofPlatform().name("decision-first").factory());
          ExecutorService second = Executors.newSingleThreadExecutor()) {
        Future<?> a =
            first.submit(
                () -> {
                  if (cancelFirst) gated.cancel("demo", queued.id());
                  else gated.finish(worker, null);
                });
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        Future<?> b =
            second.submit(
                () -> {
                  secondEntered.countDown();
                  if (cancelFirst) gated.finish(worker, null);
                  else gated.cancel("demo", queued.id());
                });
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
        assertFalse(b.isDone(), "second decision cannot pass the held row lock");
        release.countDown();
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
      assertEquals(cancelFirst ? "CANCELLED" : "COMPLETED", store.job("demo", queued.id()).state());
      assertNotNull(store.job("demo", queued.id()).incumbent());
      assertNull(store.plan("demo", "p").solution());
    }
  }

  @Test
  void gracefulShutdownFencesAndRequeuesInterruptedWorkWithoutPublishing() throws Exception {
    var queued =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "close", 42, 30000, 100000),
            "shutdown-source",
            17);
    CountDownLatch entered = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Store.JobView> claim =
        new java.util.concurrent.atomic.AtomicReference<>();
    Dispatcher dispatcher =
        new Dispatcher(
            store,
            "shutdown-source",
            (job, deadline, stop) -> {
              claim.set(job);
              store.record(job, new Heuristics().baseline(problem("demo")));
              entered.countDown();
              while (!stop.getAsBoolean()) Thread.onSpinWait();
            },
            Duration.ofMillis(10),
            Duration.ofMillis(50));
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
    } finally {
      dispatcher.close();
    }
    assertTrue(dispatcher.terminated());
    var after = store.job("demo", queued.id());
    assertEquals("QUEUED", after.state());
    assertTrue(after.fence() > claim.get().fence());
    assertNotNull(after.incumbent());
    assertFalse(store.finish(claim.get(), null));
    assertNull(store.plan("demo", "p").solution());
  }

  @Test
  void heartbeatFailureIsVisibleAndDoesNotLeakTheSingleWorkerSlot() throws Exception {
    var first =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "heartbeat-fault", 42, 30000, 100000),
            "heartbeat-source",
            17);
    var injected = new java.util.concurrent.atomic.AtomicBoolean();
    Store unreliable =
        new Store(
            intercept(
                (sql, after) -> {
                  if (!after
                      && sql.startsWith("UPDATE solve_job SET lease_until=")
                      && injected.compareAndSet(false, true))
                    throw new java.sql.SQLException("injected heartbeat outage");
                }));
    try (Dispatcher dispatcher =
        new Dispatcher(
            unreliable,
            "heartbeat-source",
            (job, deadline, stop) -> {
              unreliable.record(job, new Heuristics().baseline(problem("demo")));
              if (job.id().equals(first.id())) while (!stop.getAsBoolean()) Thread.onSpinWait();
            },
            Duration.ofMillis(10),
            Duration.ofMillis(20))) {
      var failed = awaitTerminal(first.id());
      assertEquals("FAILED", failed.state());
      assertEquals("LEASE_HEARTBEAT_FAILURE", failed.failure());
      var second =
          store.submit(
              "demo",
              "p",
              new Store.SolveRequest(1, "after-heartbeat-fault", 42, 1000, 10),
              "heartbeat-source",
              17);
      assertEquals("COMPLETED", awaitTerminal(second.id()).state());
    }
  }

  @Test
  void closeDuringClaimNeverStartsAnUnownedTaskOrLeavesARunningLease() throws Exception {
    var queued =
        store.submit(
            "demo",
            "p",
            new Store.SolveRequest(1, "close-claim", 42, 30000, 100000),
            "close-source",
            17);
    CountDownLatch claimed = new CountDownLatch(1), release = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger executions =
        new java.util.concurrent.atomic.AtomicInteger();
    Store gated =
        new Store(
            intercept(
                (sql, after) -> {
                  if (after && sql.startsWith("UPDATE solve_job SET state='RUNNING'")) {
                    claimed.countDown();
                    boolean interrupted = false;
                    while (true) {
                      try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        break;
                      } catch (InterruptedException expected) {
                        interrupted = true;
                      }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                  }
                }));
    Dispatcher dispatcher =
        new Dispatcher(
            gated,
            "close-source",
            (job, deadline, stop) -> executions.incrementAndGet(),
            Duration.ofMillis(10),
            Duration.ofMillis(50));
    try (ExecutorService closer = Executors.newSingleThreadExecutor()) {
      assertTrue(claimed.await(5, TimeUnit.SECONDS));
      Future<?> closed = closer.submit(dispatcher::close);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!dispatcher.isClosing() && System.nanoTime() < deadline) Thread.onSpinWait();
      assertTrue(dispatcher.isClosing());
      release.countDown();
      closed.get(20, TimeUnit.SECONDS);
      assertTrue(dispatcher.terminated());
      assertEquals(0, executions.get());
      assertEquals("QUEUED", store.job("demo", queued.id()).state());
    } finally {
      release.countDown();
      dispatcher.close();
    }
  }

  @Test
  void shutdownAcknowledgesCancellationAndCannotReleaseANewerGeneration() throws Exception {
    var queued = submit("shutdown-cancel");
    var claim = store.claim("owner", 15);
    store.record(claim, new Heuristics().baseline(problem("demo")));
    store.cancel("demo", queued.id());
    assertTrue(store.release(claim, "SERVICE_SHUTDOWN_REQUEUED"));
    var after = store.job("demo", queued.id());
    assertEquals("CANCELLED", after.state());
    assertNotNull(after.finishedAt());
    assertNotNull(after.incumbent());
    assertFalse(store.finish(claim, null));
    submit("shutdown-expired");
    var stale = store.claim("old", 15);
    expire(stale);
    store.recover();
    var current = store.claim("new", 15);
    assertFalse(store.release(stale, "SERVICE_SHUTDOWN_REQUEUED"));
    assertEquals("RUNNING", store.job("demo", current.id()).state());
    assertTrue(store.release(current, "SERVICE_SHUTDOWN_REQUEUED"));
  }

  @FunctionalInterface
  interface SqlHook {
    void execute(String sql, boolean after) throws Exception;
  }

  static javax.sql.DataSource intercept(SqlHook hook) {
    return (javax.sql.DataSource)
        java.lang.reflect.Proxy.newProxyInstance(
            PostgresIT.class.getClassLoader(),
            new Class<?>[] {javax.sql.DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(ds, method, args);
              if (!method.getName().equals("getConnection")) return result;
              return java.lang.reflect.Proxy.newProxyInstance(
                  PostgresIT.class.getClassLoader(),
                  new Class<?>[] {java.sql.Connection.class},
                  (connection, call, values) -> {
                    Object prepared = invoke(result, call, values);
                    if (!call.getName().equals("prepareStatement")) return prepared;
                    String sql = (String) values[0];
                    return java.lang.reflect.Proxy.newProxyInstance(
                        PostgresIT.class.getClassLoader(),
                        new Class<?>[] {java.sql.PreparedStatement.class},
                        (statement, operation, parameters) -> {
                          boolean executes = operation.getName().startsWith("execute");
                          if (executes) hook.execute(sql, false);
                          Object answer = invoke(prepared, operation, parameters);
                          if (executes) hook.execute(sql, true);
                          return answer;
                        });
                  });
            });
  }

  static Object invoke(Object receiver, java.lang.reflect.Method method, Object[] args)
      throws Throwable {
    try {
      return method.invoke(receiver, args);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  Store.JobView awaitTerminal(java.util.UUID id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      Store.JobView job = store.job("demo", id);
      if (Set.of("COMPLETED", "CANCELLED", "FAILED").contains(job.state())) return job;
      Thread.sleep(25);
    }
    throw new AssertionError("dispatcher did not reach a bounded terminal state for " + id);
  }

  void expire(Store.JobView job) throws Exception {
    try (var c = ds.getConnection();
        var s =
            c.prepareStatement(
                "UPDATE solve_job SET lease_until=clock_timestamp()-interval '1 second' WHERE tenant=? AND id=?")) {
      s.setString(1, job.tenant());
      s.setObject(2, job.id());
      s.executeUpdate();
    }
  }
}
