package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class DurabilityIT {
  static PostgreSQLContainer postgres;
  static KafkaContainer kafka;
  static PostgresStore store;

  @BeforeAll
  static void start() {
    postgres =
        new PostgreSQLContainer(
                DockerImageName.parse(
                        "postgres:18.3-alpine@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("pulse")
            .withUsername("pulse")
            .withPassword("pulse-local");
    kafka =
        new KafkaContainer(
                DockerImageName.parse(
                        "apache/kafka:4.2.1@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0")
                    .asCompatibleSubstituteFor("apache/kafka"))
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
    postgres.start();
    kafka.start();
    store =
        new PostgresStore(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    store.migrate();
  }

  @AfterAll
  static void stop() {
    if (kafka != null) kafka.stop();
    if (postgres != null) postgres.stop();
  }

  @Test
  void realProcessCrashesBeforeAndAfterCommitAndDuringOutbox() throws Exception {
    for (String crash : List.of("late-before", "late-after")) {
      String run = crash.replace('-', '_'), topic = "pulse-" + run;
      createTopic(topic, 2);
      createTopic(topic + "-results", 2);
      assertEquals(0, worker(run, topic, "seed"));
      assertEquals(74, worker(run, topic, crash));
      var first = store.windows(run, "t", "a", "TUMBLING", 100, Long.MIN_VALUE).getFirst();
      assertEquals(crash.equals("late-before") ? 30 : 35, first.sum());
      try (var c = store.connection();
          var p =
              c.prepareStatement(
                  "SELECT count(*) FROM seen_event WHERE run_id=? AND event_id='five'")) {
        p.setString(1, run);
        try (var r = p.executeQuery()) {
          r.next();
          assertEquals(crash.equals("late-before") ? 0 : 1, r.getInt(1));
        }
      }
      awaitLease(run);
      assertEquals(0, worker(run, topic, "recover"));
      assertEquals(74, worker(run, topic, "outbox-crash"));
      awaitLease(run);
      assertEquals(0, worker(run, topic, "outbox-retry"));
      // At least one durable revision appears twice after the killed outbox delivery.
      List<String> keys = resultKeys(topic + "-results");
      assertTrue(
          keys.size() > new HashSet<>(keys).size(), "outbox duplicate must be externally visible");
      try (var c = store.connection();
          var p =
              c.prepareStatement("SELECT count(*) FROM outbox WHERE run_id=? AND NOT delivered")) {
        p.setString(1, run);
        try (var r = p.executeQuery()) {
          r.next();
          assertEquals(0, r.getInt(1));
        }
      }
    }
  }

  @Test
  void outboxTransactionRenewsLeaseAfterSlowDeliveryWhileRetainingOwnershipLock() throws Exception {
    String run = "slow-outbox";
    var limits = new Engine.Limits(1, 10, 100, 100, 100);
    var owner = store.acquire(run, Duration.ofSeconds(2));
    var initial = store.restore(run, "slow-topic", limits, 0);
    var next =
        Engine.transition(
            initial,
            List.of(
                Engine.Input.of(0, 0, ReplayWorker.event(run, "one", "a", 0, 7, false)),
                Engine.Input.of(0, 1, ReplayWorker.event(run, "progress", "a", 65_000, 0, true))),
            List.of(),
            65_000);
    store.commit(owner, "slow-topic", initial, next);
    var deliveries = new java.util.concurrent.atomic.AtomicInteger();
    int delivered =
        store.deliverOutbox(
            owner,
            ignored -> {
              if (deliveries.getAndIncrement() == 0) {
                try {
                  Thread.sleep(2500);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
              }
            },
            100);
    assertTrue(delivered > 0, "exercise an actual outbox callback beyond the two-second lease");
    assertThrows(SQLException.class, () -> store.acquire(run, Duration.ofSeconds(2)));
    assertDoesNotThrow(() -> store.commit(owner, "slow-topic", next, next));
    store.release(owner);
  }

  @Test
  void fencedOwnerRollbackQuotasAndTopicIdentity() throws Exception {
    String run = "fenced";
    var limits = new Engine.Limits(1, 1, 1, 3, 0);
    var owner = store.acquire(run, Duration.ofSeconds(2));
    var s = store.restore(run, "topic-uuid", limits, 0);
    var one =
        Engine.transition(
            s,
            List.of(Engine.Input.of(0, 0, ReplayWorker.event(run, "a", "a", 0, 10, false))),
            List.of(),
            0);
    store.commit(owner, "topic-uuid", s, one);
    assertThrows(SQLException.class, () -> store.acquire(run, Duration.ofSeconds(1)));
    awaitLease(run);
    var newer = store.acquire(run, Duration.ofSeconds(30));
    assertThrows(SQLException.class, () -> store.commit(owner, "topic-uuid", one, one));
    assertThrows(SQLException.class, () -> store.restore(run, "recreated-topic", limits, 0));
    var restarted = store.restore(run, "topic-uuid", limits, 0);
    var duplicate =
        Engine.transition(
            restarted,
            List.of(Engine.Input.of(0, 7, ReplayWorker.event(run, "a", "a", 0, 10, false))),
            List.of(),
            0);
    assertEquals(8, duplicate.offsets.get(0));
    assertEquals(1, duplicate.duplicates);
    var conflict =
        Engine.transition(
            duplicate,
            List.of(Engine.Input.of(0, 8, ReplayWorker.event(run, "a", "a", 0, 999, false))),
            List.of(),
            0);
    store.commit(newer, "topic-uuid", restarted, conflict);
    assertEquals("CONFLICTING_EVENT_ID", store.quarantine(run, "t", 0, 100).getFirst().reason());
    // Key admission and pane quotas are durable rejected records, never silent eviction.
    var rejected =
        Engine.transition(
            conflict,
            List.of(Engine.Input.of(0, 9, ReplayWorker.event(run, "b", "b", 0, 1, false))),
            List.of(),
            0);
    store.commit(newer, "topic-uuid", conflict, rejected);
    assertEquals("KEY_QUOTA", store.quarantine(run, "t", 0, 100).getLast().reason());
    var full =
        Engine.transition(
            rejected,
            List.of(Engine.Input.of(0, 10, ReplayWorker.event(run, "c", "a", 60_000, 1, false))),
            List.of(),
            60_000);
    assertEquals("PANE_QUOTA", full.quarantine.getFirst().reason());
    store.commit(newer, "topic-uuid", rejected, full);
    var quota =
        Engine.transition(
            full,
            List.of(Engine.Input.of(0, 11, ReplayWorker.event(run, "d", "a", 0, 1, false))),
            List.of(),
            60_000);
    assertEquals("RUN_EVENT_QUOTA", quota.quarantine.getFirst().reason());
    store.commit(newer, "topic-uuid", full, quota);
    assertEquals(1, quota.panes.size());
    assertEquals(12, store.restore(run, "topic-uuid", limits, 60_000).offsets.get(0));
    store.release(newer);
    var failedStore =
        new PostgresStore("jdbc:postgresql://127.0.0.1:1/unavailable", "pulse", "unused");
    assertThrows(SQLException.class, () -> failedStore.commit(newer, "topic-uuid", quota, quota));
    assertEquals(12, store.restore(run, "topic-uuid", limits, 60_000).offsets.get(0));
  }

  @Test
  void actualTopicRecreationAndPartitionGrowthFailStartup() throws Exception {
    String topic = "pulse-identity";
    createTopic(topic, 1);
    String original = KafkaPump.topicIdentity(kafka.getBootstrapServers(), topic, 1);
    var limits = new Engine.Limits(1, 10, 100, 100, 0);
    try (DurableSession session =
        new DurableSession(store, "topic_identity", original, limits, 0, Duration.ofSeconds(30))) {
      session.apply(new KafkaPump.Batch(List.of(), List.of(), 0));
    }
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin.deleteTopics(List.of(topic)).all().get(20, TimeUnit.SECONDS);
    }
    createTopic(topic, 1);
    String recreated = KafkaPump.topicIdentity(kafka.getBootstrapServers(), topic, 1);
    assertNotEquals(original, recreated);
    SQLException changed =
        assertThrows(
            SQLException.class,
            () ->
                new DurableSession(
                    store, "topic_identity", recreated, limits, 0, Duration.ofSeconds(30)));
    assertTrue(changed.getMessage().contains("TOPIC_ID_CHANGED"));
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .createPartitions(Map.of(topic, NewPartitions.increaseTo(2)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    assertThrows(
        IllegalStateException.class,
        () -> KafkaPump.topicIdentity(kafka.getBootstrapServers(), topic, 1));
  }

  @Test
  void malformedRecordTypesAreDurablyQuarantinedWithoutConsumingEventIdentity() throws Exception {
    String run = "wire_type", topic = "pulse-wire-type";
    createTopic(topic, 1);
    var event = ReplayWorker.event(run, "one", "a", 0, 7, false);
    var json = new String(EventCodec.encode(event), java.nio.charset.StandardCharsets.UTF_8);
    try (var producer = ReplayWorker.producer(kafka.getBootstrapServers())) {
      for (String invalid : List.of("null", "{}", "[]")) {
        var payload =
            json.replace("\"recordType\":\"MEASUREMENT\"", "\"recordType\":" + invalid)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        producer
            .send(
                new org.apache.kafka.clients.producer.ProducerRecord<>(topic, event.key(), payload))
            .get(20, TimeUnit.SECONDS);
      }
      ReplayWorker.send(producer, topic, event);
    }
    var limits = new Engine.Limits(1, 10, 100, 100, 0);
    var failure = new AtomicReference<Throwable>();
    String topicId = KafkaPump.topicIdentity(kafka.getBootstrapServers(), topic, 1);
    try (var session = new DurableSession(store, run, topicId, limits, 0, Duration.ofSeconds(30));
        var pump =
            new KafkaPump(
                kafka.getBootstrapServers(), topic, "wire-type-test", 1, 2, 10, failure::set)) {
      pump.start(session.checkpoint());
      pump.awaitReady(Duration.ofSeconds(15));
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (session.checkpoint().getOrDefault(0, 0L) < 4 && System.nanoTime() < deadline) {
        var batch = pump.take(Duration.ofMillis(100));
        if (batch != null) {
          session.apply(batch);
          pump.committed(session.checkpoint());
        }
      }
      assertNull(failure.get());
      assertEquals(4, session.checkpoint().get(0));
      assertEquals(1, session.state().seen.size());
      assertEquals(7, session.state().panes.values().iterator().next().sum());
    }
    var quarantine = store.quarantine(run, "!unattributed", 0, 100);
    assertEquals(3, quarantine.size());
    assertTrue(quarantine.stream().allMatch(row -> row.reason().equals("MALFORMED_JSON")));
    try (var recovered =
        new DurableSession(store, run, topicId, limits, 0, Duration.ofSeconds(30))) {
      assertEquals(4, recovered.checkpoint().get(0));
      assertEquals(1, recovered.state().seen.size());
      assertEquals(7, recovered.state().panes.values().iterator().next().sum());
    }
  }

  @Test
  void brokerRetentionGapIsVisibleAndBoundedQueueSurvivesDatabasePause() throws Exception {
    String topic = "pulse-retention";
    createTopic(topic, 1);
    try (var producer = ReplayWorker.producer(kafka.getBootstrapServers())) {
      for (int i = 0; i < 20; i++)
        ReplayWorker.send(
            producer, topic, ReplayWorker.event("retention", "e" + i, "a", 0, i, false));
    }
    var tp = new TopicPartition(topic, 0);
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .deleteRecords(Map.of(tp, RecordsToDelete.beforeOffset(10)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (var pump =
        new KafkaPump(
            kafka.getBootstrapServers(), topic, "retention-test", 1, 2, 1, failure::set)) {
      pump.start(Map.of(0, 0L));
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (failure.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
      assertNotNull(failure.get());
      assertTrue(failure.get().getMessage().contains("SOURCE_POSITION_UNAVAILABLE"));
    }
    AtomicReference<Throwable> queueFailure = new AtomicReference<>();
    try (var pump =
        new KafkaPump(
            kafka.getBootstrapServers(), topic, "backpressure-test", 1, 2, 1, queueFailure::set)) {
      pump.start(Map.of(0, 10L));
      Thread.sleep(1500);
      assertTrue(pump.depth() <= 2);
      assertTrue(pump.depth() > 0);
      assertNull(queueFailure.get());
      int read = 0;
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (read < 10 && System.nanoTime() < deadline) {
        var b = pump.take(Duration.ofMillis(100));
        if (b != null) read += b.inputs().size();
      }
      assertEquals(10, read);
      assertNull(queueFailure.get());
    }
  }

  @Test
  void diagnosticGrowthHasHardDurableQuotaAndRollback() throws Exception {
    var limits = new Engine.Limits(1, 1, 1, 1, 0);
    try (DurableSession session =
        new DurableSession(
            store, "diagnostic_quota", "diagnostic-topic", limits, 0, Duration.ofSeconds(30))) {
      List<Engine.Input> invalid = new ArrayList<>();
      for (int i = 0; i < 7; i++)
        invalid.add(new Engine.Input(0, i, i + 1, "bad", null, "MALFORMED_JSON"));
      assertThrows(
          SQLException.class, () -> session.apply(new KafkaPump.Batch(invalid, List.of(), 0)));
      assertTrue(session.checkpoint().isEmpty());
      try (var c = store.connection();
          var p =
              c.prepareStatement(
                  "SELECT count(*) FROM quarantine WHERE run_id='diagnostic_quota'");
          var r = p.executeQuery()) {
        r.next();
        assertEquals(0, r.getLong(1));
      }
    }
  }

  @Test
  void actualDatabaseOutagePoisonsSessionWithoutPublishingCandidate() throws Exception {
    var limits = new Engine.Limits(1, 10, 100, 100, 0);
    try (DurableSession session =
        new DurableSession(store, "db_outage", "outage-topic", limits, 0, Duration.ofSeconds(60))) {
      var one =
          new KafkaPump.Batch(
              List.of(
                  Engine.Input.of(0, 0, ReplayWorker.event("db_outage", "one", "a", 0, 1, false))),
              List.of(),
              0);
      session.apply(one);
      var committed = session.state();
      var two =
          new KafkaPump.Batch(
              List.of(
                  Engine.Input.of(0, 1, ReplayWorker.event("db_outage", "two", "a", 0, 2, false))),
              List.of(),
              0);
      var client = org.testcontainers.DockerClientFactory.instance().client();
      client.pauseContainerCmd(postgres.getContainerId()).exec();
      try {
        assertThrows(SQLException.class, () -> session.apply(two));
        assertSame(committed, session.state());
        assertEquals(1, session.checkpoint().get(0));
        assertThrows(IllegalStateException.class, () -> session.apply(two));
      } finally {
        client.unpauseContainerCmd(postgres.getContainerId()).exec();
      }
    }
    try (DurableSession recovered =
        new DurableSession(store, "db_outage", "outage-topic", limits, 0, Duration.ofSeconds(30))) {
      assertEquals(1, recovered.checkpoint().get(0));
      recovered.apply(
          new KafkaPump.Batch(
              List.of(
                  Engine.Input.of(0, 1, ReplayWorker.event("db_outage", "two", "a", 0, 2, false))),
              List.of(),
              0));
      assertEquals(3, recovered.state().panes.values().iterator().next().sum());
    }
  }

  @Test
  void protectedApiUsesRealLocalIssuerAndEnforcesTenantAndLimits() throws Exception {
    var owner = store.acquire("security", Duration.ofSeconds(10));
    var initial = new Engine.State("security", new Engine.Limits(1, 10, 100, 100, 100000), 0);
    var state =
        Engine.transition(
            initial,
            List.of(
                Engine.Input.of(0, 0, ReplayWorker.event("security", "one", "a", 0, 5, false)),
                Engine.Input.of(
                    0, 1, ReplayWorker.event("security", "progress", "a", 100000, 0, true))),
            List.of(),
            100000);
    store.commit(owner, "security-topic", initial, state);
    store.release(owner);
    try (LocalIssuer issuer = new LocalIssuer();
        var app =
            new org.springframework.boot.builder.SpringApplicationBuilder(PulseApplication.class)
                .properties(
                    Map.ofEntries(
                        Map.entry("pulse.db.url", postgres.getJdbcUrl()),
                        Map.entry("pulse.db.user", postgres.getUsername()),
                        Map.entry("pulse.db.password", postgres.getPassword()),
                        Map.entry("pulse.consume", "false"),
                        Map.entry("pulse.security", "protected"),
                        Map.entry("pulse.oidc.issuer", issuer.url()),
                        Map.entry("pulse.oidc.jwks", issuer.url() + "/jwks"),
                        Map.entry("pulse.oidc.audience", "pulse-api"),
                        Map.entry("server.port", "0")))
                .run("--server.port=0")) {
      String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
      var client = java.net.http.HttpClient.newHttpClient();
      String path = "/v1/runs/security/windows?tenant=t&sensor=a&type=TUMBLING";
      assertEquals(401, get(client, base + path, null));
      for (String invalid :
          List.of(
              "signature",
              "issuer",
              "audience",
              "expired",
              "no-exp",
              "no-sub",
              "numeric-sub",
              "array-sub",
              "blank-sub",
              "no-audience",
              "no-tenant",
              "tenant-type",
              "nbf",
              "nbf-type"))
        assertEquals(401, get(client, base + path, issuer.token(invalid)), invalid);
      for (String forbidden : List.of("scope", "tenant"))
        assertEquals(403, get(client, base + path, issuer.token(forbidden)), forbidden);
      String tokenResponse =
          client
              .send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create(issuer.url() + "/token?fixture=valid"))
                      .GET()
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString())
              .body();
      String token =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(tokenResponse)
              .path("access_token")
              .textValue();
      assertEquals(200, get(client, base + path, token));
      assertEquals(400, get(client, base + path + "&limit=1001", token));
      assertEquals(403, get(client, base + "/v1/runs/security/status?tenant=other", token));
      var bodyRequest =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
              .header("Authorization", "Bearer " + token)
              .method(
                  "GET",
                  java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                      () -> new java.io.ByteArrayInputStream(new byte[100_000])))
              .build();
      assertEquals(
          413,
          client
              .send(bodyRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
              .statusCode());
      assertEquals(200, get(client, base + "/health", null));
    }
  }

  static int get(java.net.http.HttpClient client, String uri, String token) throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri))
            .timeout(Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return client
        .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  static void createTopic(String name, int partitions) throws Exception {
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .createTopics(List.of(new NewTopic(name, partitions, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    }
  }

  static int worker(String run, String topic, String phase) throws Exception {
    Path logs = Path.of("target", "crash-logs");
    Files.createDirectories(logs);
    Path log = logs.resolve(run + "-" + phase + ".log");
    List<String> cmd =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                ReplayWorker.class.getName(),
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                kafka.getBootstrapServers(),
                run,
                topic,
                phase));
    ProcessBuilder builder =
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().put("PULSE_DB_PASSWORD", postgres.getPassword());
    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("subprocess timeout: " + log);
    }
    int exit = process.exitValue();
    if (exit != 0 && exit != 74) System.err.println(Files.readString(log));
    return exit;
  }

  static void awaitLease(String run) throws Exception {
    long deadline = System.nanoTime() + ReplayWorker.SOURCE_LEASE.plusSeconds(5).toNanos();
    while (System.nanoTime() < deadline)
      try (var c = store.connection();
          var p =
              c.prepareStatement(
                  "SELECT lease_until<=clock_timestamp() FROM run_owner WHERE run_id=?")) {
        p.setString(1, run);
        try (var r = p.executeQuery()) {
          r.next();
          if (r.getBoolean(1)) return;
        }
        Thread.sleep(20);
      }
    throw new AssertionError("lease did not expire");
  }

  static List<String> resultKeys(String topic) throws Exception {
    var props = new HashMap<String, Object>();
    props.put("bootstrap.servers", kafka.getBootstrapServers());
    props.put("group.id", UUID.randomUUID().toString());
    props.put("enable.auto.commit", false);
    props.put("key.deserializer", StringDeserializer.class);
    props.put("value.deserializer", StringDeserializer.class);
    try (var consumer = new KafkaConsumer<String, String>(props)) {
      List<TopicPartition> ps = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
      consumer.assign(ps);
      consumer.seekToBeginning(ps);
      var end = consumer.endOffsets(ps);
      List<String> keys = new ArrayList<>();
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (System.nanoTime() < deadline) {
        for (var r : consumer.poll(Duration.ofMillis(100))) keys.add(r.key());
        if (ps.stream().allMatch(p -> consumer.position(p) >= end.get(p))) return keys;
      }
      throw new AssertionError("result consumer timeout");
    }
  }
}
