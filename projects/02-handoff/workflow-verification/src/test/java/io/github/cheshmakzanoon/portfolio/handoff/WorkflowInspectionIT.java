package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.handoff.Data.Value;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.Event;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.EventType;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.State;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Real PostgreSQL behavior; an explicitly supplied disposable database is an optional local
 * adapter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowInspectionIT {
  private PostgresTestDatabase database;
  private String url;
  private String username;
  private String password;
  private WorkflowStore store;
  private String tenant;

  @BeforeAll
  void database() {
    database = new PostgresTestDatabase();
    database.start();
    url = database.getJdbcUrl();
    username = database.getUsername();
    password = database.getPassword();
    store = new WorkflowStore(url, username, password);
    store.migrate();
  }

  @BeforeEach
  void isolatedTenant() {
    tenant = "inspection-" + UUID.randomUUID();
  }

  @AfterAll
  void close() {
    if (store != null) store.close();
    if (database != null) database.stop();
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(url, username, password);
  }

  private UUID waiting(String key) {
    Definition definition =
        new Definition(
            "inspection",
            1,
            List.of(
                new Node(
                    "approval",
                    Kind.SIGNAL,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    3600,
                    "approval",
                    null,
                    null)));
    return store.start(tenant, store.deploy(tenant, definition), key, Map.of());
  }

  private UUID completedWithLateEvents(int lateCount) {
    UUID instance = waiting("start");
    assertTrue(store.signal(tenant, instance, "approved", "approval", "yes"));
    for (int i = 0; i < lateCount; i++)
      assertFalse(store.signal(tenant, instance, "late-" + i, "approval", "late " + i));
    return instance;
  }

  @Test
  void viewerReadsDoNotWaitBehindAnUncommittedWriter() throws Exception {
    UUID instance = waiting("lock");
    try (Connection writer = connect();
        ExecutorService readers = Executors.newFixedThreadPool(3)) {
      writer.setAutoCommit(false);
      try (PreparedStatement update =
          writer.prepareStatement(
              "UPDATE workflow_instance SET last_scheduler_error='uncommitted' WHERE id=?")) {
        update.setObject(1, instance);
        assertEquals(1, update.executeUpdate());
      }
      // Each operation must finish while the write lock is still held. Releasing the
      // writer before awaiting the results would mask the original FOR UPDATE defect.
      Future<WorkflowStore.View> view = readers.submit(() -> store.get(tenant, instance));
      Future<List<Event>> history = readers.submit(() -> store.history(tenant, instance));
      Future<HistoryPage> page =
          readers.submit(() -> store.historyPage(tenant, instance, 0, null, 10));
      try {
        assertEquals(State.WAITING, view.get(3, TimeUnit.SECONDS).state());
        assertEquals(
            List.of(EventType.STARTED),
            history.get(3, TimeUnit.SECONDS).stream().map(Event::type).toList());
        assertEquals(1, page.get(3, TimeUnit.SECONDS).snapshotVersion());
      } finally {
        writer.rollback();
      }
    }
  }

  @Test
  void inspectionIsEnforcedReadOnlyByPostgresAndUsesOneRepeatableSnapshot() throws Exception {
    UUID instance = waiting("snapshot");
    var source = new PGSimpleDataSource();
    source.setURL(url);
    source.setUser(username);
    source.setPassword(password);
    var transactions =
        new JdbcTransactions(source, () -> fail("write hook was invoked by inspection"));
    transactions.inspect(
        connection -> {
          try (Statement query = connection.createStatement();
              ResultSet mode =
                  query.executeQuery(
                      "SELECT current_setting('transaction_read_only'),current_setting('transaction_isolation')")) {
            assertTrue(mode.next());
            assertEquals("on", mode.getString(1));
            assertEquals("repeatable read", mode.getString(2));
          }
          assertEquals(1, version(connection, instance));
          assertTrue(store.signal(tenant, instance, "accept", "approval", "yes"));
          assertEquals(1, version(connection, instance));
          try (PreparedStatement query =
              connection.prepareStatement(
                  "SELECT count(*) FROM workflow_history WHERE instance_id=?")) {
            query.setObject(1, instance);
            try (ResultSet events = query.executeQuery()) {
              assertTrue(events.next());
              assertEquals(1, events.getLong(1));
            }
          }
          return null;
        });
    assertEquals(2, store.get(tenant, instance).version());
    PersistenceFailure denied =
        assertThrows(
            PersistenceFailure.class,
            () ->
                transactions.inspect(
                    connection -> {
                      try (PreparedStatement update =
                          connection.prepareStatement(
                              "UPDATE workflow_instance SET last_scheduler_error='must-not-write' WHERE id=?")) {
                        update.setObject(1, instance);
                        update.executeUpdate();
                      }
                      return null;
                    }));
    assertEquals("25006", ((SQLException) denied.getCause()).getSQLState());
  }

  private long version(Connection connection, UUID instance) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement("SELECT version FROM workflow_instance WHERE id=?")) {
      query.setObject(1, instance);
      try (ResultSet result = query.executeQuery()) {
        assertTrue(result.next());
        return result.getLong(1);
      }
    }
  }

  @Test
  void firstPageFreezesHistoryWhileNewSignalsContinueToCommit() {
    UUID instance = completedWithLateEvents(7);
    List<Event> before = store.history(tenant, instance);
    HistoryPage first = store.historyPage(tenant, instance, 0, null, 3);
    assertEquals(9, first.snapshotVersion());
    assertEquals(List.of(1L, 2L, 3L), first.events().stream().map(Event::sequence).toList());
    assertFalse(store.signal(tenant, instance, "later", "approval", "outside snapshot"));
    List<Event> traversed = new ArrayList<>(first.events());
    HistoryPage page = first;
    while (page.hasMore()) {
      page = store.historyPage(tenant, instance, page.nextAfter(), first.snapshotVersion(), 2);
      assertEquals(first.snapshotVersion(), page.snapshotVersion());
      traversed.addAll(page.events());
    }
    assertEquals(before, traversed);
    assertEquals(9, page.nextAfter());
    assertEquals(10, store.historyPage(tenant, instance, 0, null, 200).events().size());
    assertEquals(first, store.historyPage(tenant, instance, 0, first.snapshotVersion(), 3));
  }

  @Test
  void arbitraryRetainedPrefixesAndExhaustedPagesHaveExplicitStableSemantics() {
    UUID instance = completedWithLateEvents(3);
    var prefix = store.historyPage(tenant, instance, 0, 2L, 200);
    assertEquals(
        List.of(EventType.STARTED, EventType.SIGNAL),
        prefix.events().stream().map(Event::type).toList());
    assertFalse(prefix.hasMore());
    var end = store.historyPage(tenant, instance, 2, 2L, 200);
    assertEquals(2, end.nextAfter());
    assertTrue(end.events().isEmpty());
    assertFalse(end.hasMore());
    var latestEnd = store.historyPage(tenant, instance, 5, null, 1);
    assertEquals(5, latestEnd.snapshotVersion());
    assertTrue(latestEnd.events().isEmpty());
  }

  @Test
  void paginationAlwaysRechecksTenantAndExecutionIdentity() {
    UUID instance = completedWithLateEvents(4);
    var first = store.historyPage(tenant, instance, 0, null, 1);
    for (String otherTenant : List.of("other-tenant", "demo")) {
      assertThrows(NoSuchElementException.class, () -> store.get(otherTenant, instance));
      assertThrows(NoSuchElementException.class, () -> store.history(otherTenant, instance));
      assertThrows(
          NoSuchElementException.class,
          () ->
              store.historyPage(
                  otherTenant, instance, first.nextAfter(), first.snapshotVersion(), 1));
    }
    assertThrows(
        NoSuchElementException.class,
        () -> store.historyPage(tenant, UUID.randomUUID(), 0, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> store.historyPage(tenant, instance, 0, 7L, 1));
    assertThrows(
        IllegalArgumentException.class, () -> store.historyPage(tenant, instance, 7, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> store.historyPage(tenant, instance, 4, 3L, 1));
    assertEquals(6, store.get(tenant, instance).version());
  }

  @Test
  void publicReadValidationDoesNotAcquireAConnection() {
    WorkflowStore closed = new WorkflowStore(url, username, password);
    closed.close();
    UUID instance = UUID.randomUUID();
    for (String invalid : Arrays.asList(null, "", "a_b", " ", "x".repeat(65))) {
      assertThrows(IllegalArgumentException.class, () -> closed.get(invalid, instance));
      assertThrows(IllegalArgumentException.class, () -> closed.history(invalid, instance));
      assertThrows(
          IllegalArgumentException.class, () -> closed.historyPage(invalid, instance, 0, null, 1));
    }
    assertThrows(IllegalArgumentException.class, () -> closed.get(tenant, null));
    assertThrows(IllegalArgumentException.class, () -> closed.history(tenant, null));
    assertThrows(
        IllegalArgumentException.class, () -> closed.historyPage(tenant, instance, -1, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> closed.historyPage(tenant, instance, 0, 10001L, 1));
    assertThrows(
        IllegalArgumentException.class, () -> closed.historyPage(tenant, instance, 0, null, 201));
  }

  @Test
  void readsPreserveTheCommittedLeaseBarrierAndDoNotScheduleMissingWork() throws Exception {
    Definition definition =
        new Definition(
            "barrier",
            1,
            List.of(
                Fixtures.node(
                    "charge",
                    Kind.ACTIVITY,
                    List.of(),
                    "charge",
                    Safety.NON_IDEMPOTENT,
                    "refund",
                    0)));
    UUID instance = store.start(tenant, store.deploy(tenant, definition), "start", Map.of());
    var claim = store.claim(tenant, "worker", 1, 60).getFirst();
    store.dispatch(claim);
    store.cancel(tenant, instance);
    var pending = store.get(tenant, instance);
    assertEquals(State.CANCEL_REQUESTED, pending.state());
    assertEquals(store.history(tenant, instance).getLast().sequence(), pending.version());
    store.complete(claim, "charged");
    assertEquals(State.COMPENSATING, store.get(tenant, instance).state());
    try (Connection connection = connect();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM activity_task WHERE instance_id=? AND logical_id='comp-charge'")) {
      delete.setObject(1, instance);
      assertEquals(1, delete.executeUpdate());
    }
    // Administrator corruption is not repaired by a read-only viewer request.
    var inspected = store.get(tenant, instance);
    assertEquals(State.COMPENSATING, inspected.state());
    assertEquals(List.of(Value.text("charged")), inspected.values().get("charge").values());
    assertTrue(store.claim(tenant, "other-worker", 1, 60).isEmpty());
  }

  @Test
  void readOnlyOperationsNeverInvokeTheMutationCommitHook() {
    UUID instance = completedWithLateEvents(1);
    try (WorkflowStore inspectionOnly =
        new WorkflowStore(
            url,
            username,
            password,
            () -> {
              throw new IllegalStateException("mutation hook");
            })) {
      assertEquals(State.COMPLETED, inspectionOnly.get(tenant, instance).state());
      assertEquals(3, inspectionOnly.history(tenant, instance).size());
      assertEquals(3, inspectionOnly.historyPage(tenant, instance, 0, null, 200).events().size());
      assertThrows(
          IllegalStateException.class,
          () -> inspectionOnly.signal(tenant, instance, "write", "approval", "must rollback"));
      assertEquals(3, inspectionOnly.get(tenant, instance).version());
    }
  }

  @Test
  void missingPersistedRowsAreNotReportedAsTheEndOfAValidSnapshot() throws Exception {
    UUID instance = completedWithLateEvents(5);
    try (Connection connection = connect();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM workflow_history WHERE instance_id=? AND sequence=4")) {
      delete.setObject(1, instance);
      assertEquals(1, delete.executeUpdate());
    }
    assertThrows(IllegalStateException.class, () -> store.history(tenant, instance));
    assertThrows(IllegalStateException.class, () -> store.get(tenant, instance));
    assertThrows(IllegalStateException.class, () -> store.historyPage(tenant, instance, 2, 7L, 3));
    assertEquals(2, store.historyPage(tenant, instance, 0, 2L, 2).events().size());
  }

  @Test
  void fullCapacityHistoryRemainsBoundedAndPageable() throws Exception {
    UUID instance = waiting("capacity");
    try (Connection connection = connect()) {
      connection.setAutoCommit(false);
      try (PreparedStatement append =
          connection.prepareStatement(
              "INSERT INTO workflow_history(instance_id,sequence,type,task,value,result) SELECT ?,n,'LATE_EVENT','','late signal:approval','[]' FROM generate_series(2,10000)n")) {
        append.setObject(1, instance);
        assertEquals(9999, append.executeUpdate());
      }
      try (PreparedStatement update =
          connection.prepareStatement("UPDATE workflow_instance SET version=10000 WHERE id=?")) {
        update.setObject(1, instance);
        assertEquals(1, update.executeUpdate());
      }
      connection.commit();
    }
    var first = store.historyPage(tenant, instance, 0, null, 200);
    assertEquals(10000, first.snapshotVersion());
    assertEquals(200, first.events().size());
    assertTrue(first.hasMore());
    var finalPage = store.historyPage(tenant, instance, 9800, first.snapshotVersion(), 200);
    assertEquals(200, finalPage.events().size());
    assertEquals(10000, finalPage.nextAfter());
    assertFalse(finalPage.hasMore());
    assertEquals(10000, store.history(tenant, instance).size());
    assertEquals(State.WAITING, store.get(tenant, instance).state());
  }

  @Test
  void httpPaginationValidatesBoundsRolesAndPreservesLegacyResponseShape() throws Exception {
    String previous = tenant;
    tenant = "demo";
    UUID instance = waiting("http-" + UUID.randomUUID());
    tenant = previous;
    SpringApplication application = new SpringApplication(Application.class);
    application.setDefaultProperties(
        Map.of(
            "server.port",
            "0",
            "server.address",
            "127.0.0.1",
            "spring.profiles.active",
            "dev",
            "DB_URL",
            url,
            "DB_USER",
            username,
            "DB_PASSWORD",
            password,
            "DEMO_PASSWORD",
            "inspection-disposable-only"));
    try (ConfigurableApplicationContext context =
            application.run("--server.port=0", "--server.address=127.0.0.1");
        HttpClient client = HttpClient.newHttpClient()) {
      int port =
          Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
      String base = "http://127.0.0.1:" + port + "/v1/executions/" + instance;
      var json = new ObjectMapper();
      var response = get(client, base + "/history/page?limit=1", "viewer");
      assertEquals(200, response.statusCode(), response.body());
      var parsed = json.readTree(response.body());
      assertEquals(1, parsed.get("snapshotVersion").asInt());
      assertEquals(1, parsed.get("nextAfter").asInt());
      assertFalse(parsed.get("hasMore").asBoolean());
      assertEquals("STARTED", parsed.get("events").get(0).get("type").asText());
      var legacy = get(client, base + "/history", "viewer");
      assertEquals(200, legacy.statusCode());
      assertTrue(json.readTree(legacy.body()).isArray());
      assertEquals(403, get(client, base + "/history/page", "operator").statusCode());
      assertEquals(403, get(client, base + "/history/page", "worker").statusCode());
      assertEquals(401, get(client, base + "/history/page", null).statusCode());
      for (String query :
          List.of(
              "limit=0",
              "limit=201",
              "after=-1",
              "after=2",
              "after=1&snapshotVersion=0",
              "snapshotVersion=2",
              "after=nope",
              "limit=1.5",
              "snapshotVersion=9223372036854775808")) {
        var bad = get(client, base + "/history/page?" + query, "viewer");
        assertEquals(400, bad.statusCode(), query + ": " + bad.body());
        assertTrue(
            bad.headers()
                .firstValue("Content-Type")
                .orElse("")
                .contains("application/problem+json"));
      }
      assertEquals(
          404,
          get(
                  client,
                  "http://127.0.0.1:"
                      + port
                      + "/v1/executions/"
                      + UUID.randomUUID()
                      + "/history/page",
                  "viewer")
              .statusCode());
    }
  }

  private HttpResponse<String> get(HttpClient client, String url, String role) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
    if (role != null)
      request.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (role + ":inspection-disposable-only").getBytes(StandardCharsets.UTF_8)));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
