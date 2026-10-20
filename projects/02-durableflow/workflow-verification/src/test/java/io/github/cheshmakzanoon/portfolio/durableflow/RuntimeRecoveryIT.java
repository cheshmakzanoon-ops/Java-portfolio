package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.State;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeRecoveryIT {
  PostgreSQLContainer pg;
  WorkflowStore store;
  final String tenant = "recovery";

  @BeforeAll
  void database() {
    pg =
        new PostgreSQLContainer(
            DockerImageName.parse(
                    "postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
                .asCompatibleSubstituteFor("postgres"));
    pg.start();
    store = open();
    store.migrate();
  }

  @BeforeEach
  void reset() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE workflow_definition,workflow_instance CASCADE");
    }
  }

  @AfterAll
  void close() {
    if (store != null) store.close();
    if (pg != null) pg.stop();
  }

  WorkflowStore open() {
    return new WorkflowStore(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  Connection connect() throws SQLException {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  Definition timer(String name) {
    return new Definition(
        name, 1, List.of(Fixtures.node("wait", Kind.TIMER, List.of(), null, null, null, 0)));
  }

  UUID start(Definition definition, String key) {
    return store.start(tenant, store.deploy(tenant, definition), key, Map.of());
  }

  @Test
  void fullHistoryFailureIsIsolatedAndDeferralSurvivesRestart() throws Exception {
    Definition definition =
        new Definition(
            "blocked",
            1,
            List.of(
                Fixtures.node(
                    "charge", Kind.ACTIVITY, List.of(), "charge", Safety.NON_IDEMPOTENT, null, 0),
                Fixtures.node("approval", Kind.SIGNAL, List.of("charge"), null, null, null, 60)));
    UUID blocked = start(definition, "blocked");
    var claim = store.claim(tenant, "expired", 1, 60).getFirst();
    store.dispatch(claim);
    // Same legal LATE_EVENT rows that distinct premature signal requests produce;
    // bulk insertion keeps this capacity fixture deterministic and fast.
    try (Connection connection = connect()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO workflow_history(instance_id,sequence,type,task,value,result) SELECT ?,n,'LATE_EVENT','','late signal:approval','[]' FROM generate_series(3,10000)n")) {
        statement.setObject(1, blocked);
        assertEquals(9998, statement.executeUpdate());
      }
      try (PreparedStatement statement =
          connection.prepareStatement("UPDATE workflow_instance SET version=10000 WHERE id=?")) {
        statement.setObject(1, blocked);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          connection.prepareStatement(
              "UPDATE activity_task SET lease_until=clock_timestamp()-interval '1 second' WHERE instance_id=?")) {
        statement.setObject(1, blocked);
        statement.executeUpdate();
      }
    }
    UUID healthy = start(timer("healthy"), "healthy");
    var result = store.tickReport(10);
    assertEquals(1, result.processed());
    assertEquals(
        List.of(new WorkflowStore.SchedulerFailure(blocked, "HISTORY_CAPACITY")),
        result.failures());
    assertEquals(State.COMPLETED, store.get(tenant, healthy).state());
    assertEquals(10000, store.history(tenant, blocked).size());
    store.close();
    store = open();
    UUID later = start(timer("later"), "later");
    assertEquals(1, store.tickReport(1).processed());
    assertEquals(State.COMPLETED, store.get(tenant, later).state());
    try (Connection connection = connect();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT scheduler_failures,last_scheduler_error,scheduler_retry_at>clock_timestamp() FROM workflow_instance WHERE id=?")) {
      statement.setObject(1, blocked);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(1, rows.getLong(1));
        assertEquals("HISTORY_CAPACITY", rows.getString(2));
        assertTrue(rows.getBoolean(3));
      }
    }
  }

  @Test
  void lockedInstanceDoesNotBlockAnotherTimerOrReceiveDuplicateFiring() throws Exception {
    UUID locked = start(timer("locked"), "locked"),
        healthy = start(timer("independent"), "independent");
    try (Connection connection = connect();
        PreparedStatement statement =
            connection.prepareStatement("SELECT id FROM workflow_instance WHERE id=? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setObject(1, locked);
      statement.executeQuery().close();
      var result = store.tickReport(10);
      assertEquals(1, result.processed());
      assertTrue(result.failures().isEmpty());
      assertEquals(State.COMPLETED, store.get(tenant, healthy).state());
      connection.rollback();
    }
    assertEquals(1, store.tick(10));
    assertEquals(0, store.tick(10));
    assertEquals(
        1,
        store.history(tenant, locked).stream()
            .filter(event -> event.type() == Interpreter.EventType.TIMER_FIRED)
            .count());
  }

  @Test
  void successfulCommandAfterRollbackCannotInheritStaleSchedulingDeferral() throws Exception {
    UUID id = start(timer("advanced-after-rollback"), "advanced-after-rollback");
    long before = store.get(tenant, id).version();
    AtomicInteger acquisitions = new AtomicInteger();
    DataSource source =
        (DataSource)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                  if (!method.getName().equals("getConnection"))
                    throw new UnsupportedOperationException(method.getName());
                  // Discovery, failed instance transition, then deferral. The third
                  // acquisition occurs only after rollback has closed its connection.
                  if (acquisitions.incrementAndGet() == 3) {
                    assertEquals(before, store.get(tenant, id).version());
                    store.cancel(tenant, id);
                    assertEquals(before + 1, store.get(tenant, id).version());
                  }
                  return connect();
                });
    var scheduler =
        new SchedulerBatch(
            new JdbcTransactions(source, () -> {}),
            (connection, instance) -> {
              assertEquals(id, instance);
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "UPDATE workflow_instance SET version=version+1 WHERE id=?")) {
                statement.setObject(1, instance);
                assertEquals(1, statement.executeUpdate());
              }
              throw new IllegalStateException("injected transition failure");
            });
    var report = scheduler.tick(1);
    assertEquals(0, report.processed());
    assertEquals(
        List.of(new WorkflowStore.SchedulerFailure(id, "INSTANCE_TRANSITION_FAILED")),
        report.failures());
    assertEquals(3, acquisitions.get());
    assertEquals(State.CANCELLED, store.get(tenant, id).state());
    assertEquals(before + 1, store.get(tenant, id).version());
    try (Connection connection = connect();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT scheduler_failures,last_scheduler_error,scheduler_retry_at='-infinity' FROM workflow_instance WHERE id=?")) {
      statement.setObject(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(0, rows.getLong(1));
        assertNull(rows.getString(2));
        assertTrue(rows.getBoolean(3));
      }
    }
  }

  @Test
  void claimStorageOutageRecoversWithoutDispatchingAnUnacknowledgedLease() throws Exception {
    CountDownLatch refused = new CountDownLatch(1), applied = new CountDownLatch(1);
    AtomicBoolean outage = new AtomicBoolean(true);
    AtomicReference<Thread> owner = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicInteger effects = new AtomicInteger();
    HttpServer provider = receiptProvider(effects, applied);
    UUID id = start(charge("claim-recovery"), "claim-recovery");
    try (WorkflowStore recovering =
            new WorkflowStore(
                pg.getJdbcUrl(),
                pg.getUsername(),
                pg.getPassword(),
                () -> {
                  if (Thread.currentThread() == owner.get() && outage.get()) {
                    refused.countDown();
                    throw new PersistenceFailure(
                        new SQLException("injected storage outage", "08006"));
                  }
                });
        Worker worker =
            new Worker(
                recovering,
                URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/effects"))) {
      Thread runner =
          new Thread(
              () -> {
                try {
                  worker.run(tenant, "claim-recovery-worker");
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      owner.set(runner);
      runner.start();
      try {
        assertTrue(refused.await(5, TimeUnit.SECONDS));
        assertEquals(0, effects.get());
        assertUndispatched(id);
        outage.set(false);
        assertTrue(applied.await(10, TimeUnit.SECONDS));
        worker.close();
        runner.join(5000);
        assertFalse(runner.isAlive());
        assertNull(failure.get());
        assertEquals(1, effects.get());
        assertEquals(State.COMPLETED, store.get(tenant, id).state());
        assertEquals(1, store.get(tenant, id).values().get("charge").values().size());
      } finally {
        runner.interrupt();
        worker.close();
        runner.join(5000);
      }
    } finally {
      provider.stop(0);
    }
  }

  @Test
  void claimBackoffIsInterruptibleAndContractErrorsRemainTerminal() throws Exception {
    CountDownLatch refused = new CountDownLatch(1), stopped = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    UUID id = start(charge("claim-shutdown"), "claim-shutdown");
    try (WorkflowStore unavailable =
            new WorkflowStore(
                pg.getJdbcUrl(),
                pg.getUsername(),
                pg.getPassword(),
                () -> {
                  refused.countDown();
                  throw new PersistenceFailure(
                      new SQLException("injected storage outage", "08006"));
                });
        Worker worker = new Worker(unavailable, URI.create("http://127.0.0.1:1/effects"))) {
      Thread runner =
          new Thread(
              () -> {
                try {
                  worker.run(tenant, "claim-shutdown-worker");
                } catch (InterruptedException expected) {
                  Thread.currentThread().interrupt();
                } catch (Throwable error) {
                  failure.set(error);
                } finally {
                  worker.close();
                  interrupted.set(Thread.currentThread().isInterrupted());
                  stopped.countDown();
                }
              });
      runner.start();
      try {
        assertTrue(refused.await(5, TimeUnit.SECONDS));
        runner.interrupt();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(interrupted.get());
        assertUndispatched(id);
      } finally {
        runner.interrupt();
        runner.join(5000);
      }
    }
    try (Worker worker = new Worker(store, URI.create("http://127.0.0.1:1/effects"))) {
      assertThrows(IllegalArgumentException.class, () -> worker.run(tenant, "invalid worker id"));
      assertUndispatched(id);
    }
    PersistenceFailure terminal =
        new PersistenceFailure(new SQLException("schema missing", "42P01"));
    try (WorkflowStore invalid =
            new WorkflowStore(
                pg.getJdbcUrl(),
                pg.getUsername(),
                pg.getPassword(),
                () -> {
                  throw terminal;
                });
        Worker worker = new Worker(invalid, URI.create("http://127.0.0.1:1/effects"))) {
      assertSame(
          terminal,
          assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(3),
              () ->
                  assertThrows(
                      PersistenceFailure.class, () -> worker.run(tenant, "terminal-worker"))));
      assertUndispatched(id);
    }
  }

  @Test
  void schedulerStorageOutageRecoversAndTerminalStorageErrorsPropagate() throws Exception {
    CountDownLatch refused = new CountDownLatch(1), recovered = new CountDownLatch(2);
    AtomicBoolean outage = new AtomicBoolean(true);
    AtomicReference<Thread> owner = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    UUID id = start(timer("scheduler-recovery"), "scheduler-recovery");
    try (WorkflowStore recovering =
        new WorkflowStore(
            pg.getJdbcUrl(),
            pg.getUsername(),
            pg.getPassword(),
            () -> {
              if (Thread.currentThread() != owner.get()) return;
              if (outage.get()) {
                refused.countDown();
                throw new PersistenceFailure(new SQLException("injected storage outage", "08006"));
              }
              recovered.countDown();
            })) {
      Thread runner =
          new Thread(
              () -> {
                try {
                  RuntimeMain.runScheduler(recovering);
                } catch (InterruptedException expected) {
                  Thread.currentThread().interrupt();
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      owner.set(runner);
      runner.start();
      try {
        assertTrue(refused.await(5, TimeUnit.SECONDS));
        assertEquals(State.WAITING, store.get(tenant, id).state());
        outage.set(false);
        assertTrue(recovered.await(10, TimeUnit.SECONDS));
        assertEquals(State.COMPLETED, store.get(tenant, id).state());
        runner.interrupt();
        runner.join(5000);
        assertFalse(runner.isAlive());
        assertNull(failure.get());
        assertEquals(
            1,
            store.history(tenant, id).stream()
                .filter(event -> event.type() == Interpreter.EventType.TIMER_FIRED)
                .count());
      } finally {
        runner.interrupt();
        runner.join(5000);
      }
    }
    PersistenceFailure terminal =
        new PersistenceFailure(new SQLException("schema missing", "42P01"));
    try (WorkflowStore invalid =
        new WorkflowStore(
            pg.getJdbcUrl(),
            pg.getUsername(),
            pg.getPassword(),
            () -> {
              throw terminal;
            })) {
      assertSame(
          terminal,
          assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(3),
              () ->
                  assertThrows(PersistenceFailure.class, () -> RuntimeMain.runScheduler(invalid))));
    }
  }

  @Test
  void sqlFailuresAfterTheInstanceLockRollbackWithoutBecomingDomainDeferrals() throws Exception {
    UUID id = start(timer("sql-transition-failure"), "sql-transition-failure");
    long before = store.get(tenant, id).version();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(pg.getJdbcUrl());
    source.setUser(pg.getUsername());
    source.setPassword(pg.getPassword());
    for (String state : List.of("08006", "42P01")) {
      SchedulerBatch scheduler =
          new SchedulerBatch(
              new JdbcTransactions(source, () -> {}),
              (connection, instance) -> {
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "UPDATE timer SET status='FIRED' WHERE instance_id=?")) {
                  statement.setObject(1, instance);
                  assertEquals(1, statement.executeUpdate());
                }
                throw new SQLException("injected SQL failure", state);
              });
      PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> scheduler.tick(1));
      assertEquals(state.equals("08006"), failure.retryable());
      assertEquals(before, store.get(tenant, id).version());
      assertEquals(State.WAITING, store.get(tenant, id).state());
      try (Connection connection = connect();
          PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT i.scheduler_failures,i.last_scheduler_error,t.status FROM workflow_instance i JOIN timer t ON t.instance_id=i.id WHERE i.id=?")) {
        statement.setObject(1, id);
        try (ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(0, rows.getLong(1));
          assertNull(rows.getString(2));
          assertEquals("WAITING", rows.getString(3));
        }
      }
    }
  }

  private Definition charge(String name) {
    return new Definition(
        name,
        1,
        List.of(
            Fixtures.node(
                "charge", Kind.ACTIVITY, List.of(), "charge", Safety.NON_IDEMPOTENT, null, 0)));
  }

  private HttpServer receiptProvider(AtomicInteger effects, CountDownLatch applied)
      throws Exception {
    HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    provider.createContext(
        "/effects",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          effects.incrementAndGet();
          byte[] receipt = "receipt-recovered".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, receipt.length);
          exchange.getResponseBody().write(receipt);
          exchange.close();
          applied.countDown();
        });
    provider.start();
    return provider;
  }

  private void assertUndispatched(UUID id) throws SQLException {
    try (Connection connection = connect();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT status,attempt,generation,dispatch_intent FROM activity_task WHERE instance_id=?")) {
      statement.setObject(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals("READY", rows.getString(1));
        assertEquals(0, rows.getInt(2));
        assertEquals(0, rows.getLong(3));
        assertFalse(rows.getBoolean(4));
        assertFalse(rows.next());
      }
    }
    assertEquals(
        0,
        store.history(tenant, id).stream()
            .filter(event -> event.type() == Interpreter.EventType.DISPATCHED)
            .count());
  }

  @Test
  void interruptedShutdownDrainsAnAcknowledgedEffectBeforeClosingLeaseResources() throws Exception {
    AtomicInteger effects = new AtomicInteger();
    CountDownLatch applied = new CountDownLatch(1), release = new CountDownLatch(1);
    HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    provider.createContext(
        "/effects",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          effects.incrementAndGet();
          applied.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new AssertionError("receipt release timed out");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          byte[] receipt = "receipt-committed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, receipt.length);
          exchange.getResponseBody().write(receipt);
          exchange.close();
        });
    provider.start();
    UUID id =
        start(
            new Definition(
                "drain",
                1,
                List.of(
                    Fixtures.node(
                        "charge",
                        Kind.ACTIVITY,
                        List.of(),
                        "charge",
                        Safety.NON_IDEMPOTENT,
                        null,
                        0))),
            "drain");
    Worker worker =
        new Worker(
            store, URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/effects"));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner =
        new Thread(
            () -> {
              try {
                worker.run(tenant, "drain-worker");
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    runner.start();
    try (ExecutorService closer = Executors.newSingleThreadExecutor()) {
      assertTrue(applied.await(10, TimeUnit.SECONDS));
      Future<Boolean> closed =
          closer.submit(
              () -> {
                Thread.currentThread().interrupt();
                worker.close();
                return Thread.currentThread().isInterrupted();
              });
      release.countDown();
      assertTrue(closed.get(10, TimeUnit.SECONDS));
      runner.join(5000);
      assertFalse(runner.isAlive());
      assertNull(failure.get());
      assertEquals(1, effects.get());
      assertEquals(State.COMPLETED, store.get(tenant, id).state());
      assertEquals(
          List.of(Data.Value.text("receipt-committed")),
          store.get(tenant, id).values().get("charge").values());
    } finally {
      release.countDown();
      worker.close();
      provider.stop(0);
    }
  }
}
