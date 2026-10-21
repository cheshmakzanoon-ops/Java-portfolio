package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresWorkflowIT {
  PostgresTestDatabase pg;
  WorkflowStore store;
  String tenant;

  @BeforeAll
  void start() {
    pg = new PostgresTestDatabase();
    pg.start();
    store = new WorkflowStore(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    store.migrate();
  }

  @AfterAll
  void close() {
    if (store != null) store.close();
    if (pg != null) pg.stop();
  }

  @BeforeEach
  void tenant() {
    tenant = "t-" + UUID.randomUUID();
  }

  static Node node(
      String id,
      Kind kind,
      List<String> parents,
      Safety safety,
      String compensation,
      long seconds) {
    return new Node(
        id,
        kind,
        parents,
        safety == Safety.PURE && compensation == null ? "pure" : "reserve",
        safety == Safety.PURE && compensation != null ? Safety.IDEMPOTENT_WITH_KEY : safety,
        compensation,
        Type.TEXT,
        Type.TEXT,
        seconds,
        "approval",
        null,
        null);
  }

  Definition one(Safety safety) {
    return new Definition(
        "order", 1, List.of(node("reserve", Kind.ACTIVITY, List.of(), safety, null, 0)));
  }

  UUID instance(Definition d) {
    return store.start(tenant, store.deploy(tenant, d), "start", Map.of());
  }

  WorkflowStore.Claim claim() {
    return store.claim(tenant, "worker", 1, 60).getFirst();
  }

  void awaitReady() throws Exception {
    Thread.sleep(1400);
    store.tick(256);
    Thread.sleep(1400);
  }

  @Test
  void duplicateStartsSignalsAndTenantIsolation() throws Exception {
    Definition d =
        new Definition(
            "approval",
            1,
            List.of(node("signal", Kind.SIGNAL, List.of(), Safety.PURE, null, 3600)));
    String hash = store.deploy(tenant, d);
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<UUID>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++)
        futures.add(pool.submit(() -> store.start(tenant, hash, "same", Map.of())));
      UUID id = futures.getFirst().get();
      for (Future<UUID> f : futures) assertEquals(id, f.get());
      assertTrue(store.signal(tenant, id, "signal-1", "approval", "yes"));
      assertTrue(store.signal(tenant, id, "signal-1", "approval", "yes"));
      assertFalse(store.signal(tenant, id, "signal-2", "approval", "late"));
      assertEquals(
          1, store.history(tenant, id).stream().filter(e -> e.type() == EventType.SIGNAL).count());
      assertThrows(
          IllegalArgumentException.class,
          () -> store.signal(tenant, id, "signal-1", "approval", "changed"));
      assertThrows(NoSuchElementException.class, () -> store.get("different", id));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.start(tenant, hash, "same", Map.of("changed", "true")));
    }
  }

  @Test
  void leaseFencingAndHeartbeatLate() throws Exception {
    UUID id = instance(one(Safety.PURE));
    WorkflowStore.Claim first = claim();
    expire(first);
    store.tick(256);
    makeReady(first.instance());
    WorkflowStore.Claim second = claim();
    assertTrue(second.generation() > first.generation());
    long version = store.get(tenant, id).version();
    assertThrows(IllegalStateException.class, () -> store.complete(first, "stale"));
    assertThrows(IllegalStateException.class, () -> store.dispatch(first));
    assertThrows(IllegalStateException.class, () -> store.heartbeat(first, 10));
    assertEquals(version, store.get(tenant, id).version());
    store.dispatch(second);
    store.complete(second, "done");
    assertEquals(State.COMPLETED, store.get(tenant, id).state());
    assertThrows(IllegalStateException.class, () -> store.complete(second, "duplicate"));
  }

  @Test
  void workerKillAfterEffectIdempotencyAndUncertainty() throws Exception {
    for (Safety safety : List.of(Safety.IDEMPOTENT_WITH_KEY, Safety.NON_IDEMPOTENT)) {
      tenant = "t-" + UUID.randomUUID();
      UUID id = instance(one(safety));
      WorkflowStore.Claim first = store.claim(tenant, "worker", 1, 60).getFirst();
      Path log = Files.createTempDirectory("durableflow-provider").resolve("effects.log");
      try (FakeProvider provider = new FakeProvider(0, log)) {
        Path marker = log.resolveSibling("worker.marker");
        String serialized =
            Base64.getEncoder().encodeToString(new ObjectMapper().writeValueAsBytes(first));
        Process process =
            process(
                "worker", marker, serialized, "http://127.0.0.1:" + provider.port() + "/effects");
        awaitFile(marker, process);
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, provider.effectCount());
        expire(first);
        store.tick(256);
        makeReady(first.instance());
        assertThrows(IllegalStateException.class, () -> store.complete(first, "late"));
        if (safety == Safety.IDEMPOTENT_WITH_KEY) {
          WorkflowStore.Claim successor = claim();
          try (Worker worker =
              new Worker(store, URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
            store.complete(successor, worker.execute(successor, "duplicate"));
          }
          assertEquals(1, provider.effectCount());
          assertEquals(State.COMPLETED, store.get(tenant, id).state());
        } else {
          assertEquals(State.MANUAL_REVIEW, store.get(tenant, id).state());
          assertTrue(store.claim(tenant, "worker", 1, 10).isEmpty());
          assertEquals(1, provider.effectCount());
          store.resolve(
              tenant,
              id,
              "reserve",
              "APPLIED",
              "operator-test",
              "independent provider receipt inspected");
          assertEquals(State.COMPLETED, store.get(tenant, id).state());
          assertEquals(EventType.OPERATOR_RESOLVED, store.history(tenant, id).getLast().type());
        }
      }
    }
  }

  @Test
  void serverFailureAfterNonIdempotentEffectRequiresReconciliation() throws Exception {
    UUID id = instance(one(Safety.NON_IDEMPOTENT));
    WorkflowStore.Claim claim = claim();
    Path log = Files.createTempDirectory("durableflow-ambiguous-error").resolve("effects.log");
    try (FakeProvider provider = new FakeProvider(0, log);
        Worker worker =
            new Worker(store, URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
      assertThrows(java.io.IOException.class, () -> worker.execute(claim, "error-after-effect"));
      store.fail(claim, false, "provider response was ambiguous");
      assertEquals(1, provider.effectCount());
      assertEquals(State.MANUAL_REVIEW, store.get(tenant, id).state());
      assertTrue(store.claim(tenant, "successor", 1, 60).isEmpty());
      assertEquals(1, provider.effectCount());
    }
  }

  @Test
  void killedTransactionLeavesNeitherHistoryNorScheduling() throws Exception {
    String hash = store.deploy(tenant, one(Safety.PURE));
    Path marker = Files.createTempDirectory("durableflow-crash").resolve("before-commit");
    Process process = process("start", marker, tenant, hash);
    awaitFile(marker, process);
    process.destroyForcibly();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement("SELECT count(*) FROM workflow_instance WHERE tenant=?")) {
      s.setString(1, tenant);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        assertEquals(0, r.getLong(1));
      }
    }
    UUID id = store.start(tenant, hash, "crash-start", Map.of());
    WorkflowStore restarted =
        new WorkflowStore(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    assertEquals(1, restarted.claim(tenant, "restarted", 1, 10).size());
    assertEquals(EventType.STARTED, restarted.history(tenant, id).getFirst().type());
  }

  @Test
  void parallelCancellationCompensationFailureAndPinnedUpgrade() throws Exception {
    Definition d =
        new Definition(
            "fulfilment",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), Safety.PURE, "release", 0),
                node("left", Kind.ACTIVITY, List.of("root"), Safety.PURE, "refund", 0),
                node("right", Kind.ACTIVITY, List.of("root"), Safety.PURE, "unpublish", 0)));
    UUID id = instance(d);
    String hash = store.get(tenant, id).definitionHash();
    WorkflowStore.Claim root = claim();
    store.complete(root, "ok");
    List<WorkflowStore.Claim> parallel = store.claim(tenant, "parallel", 2, 60);
    assertEquals(2, parallel.size());
    store.complete(parallel.get(1), "ok");
    store.cancel(tenant, id);
    store.complete(parallel.get(0), "late but real effect");
    assertEquals(State.COMPENSATING, store.get(tenant, id).state());
    store.deploy(tenant, new Definition("fulfilment", 2, d.nodes()));
    assertEquals(hash, store.get(tenant, id).definitionHash());
    WorkflowStore.Claim undo = claim();
    assertTrue(undo.task().startsWith("comp-"));
    for (int i = 0; i < 4; i++) {
      store.fail(undo, true, "compensation rejected");
      if (i < 3) {
        Thread.sleep(1800);
        undo = claim();
      }
    }
    assertEquals(State.COMPENSATION_FAILED, store.get(tenant, id).state());
    store.retryCompensation(tenant, id, undo.task(), "operator", "provider fixed; retry approved");
    for (int remaining = 0; remaining < 3; remaining++) {
      WorkflowStore.Claim compensation = readyClaim();
      store.complete(compensation, "compensated");
    }
    assertEquals(State.CANCELLED, store.get(tenant, id).state());
  }

  @Test
  void timersAndSignalRaceSerializeAndDormantTimersDoNotAllocateThreads() throws Exception {
    Definition d =
        new Definition(
            "timer", 1, List.of(node("wait", Kind.SIGNAL, List.of(), Safety.PURE, null, 0)));
    UUID id = instance(d);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> signal = pool.submit(() -> store.signal(tenant, id, "s", "approval", "yes"));
      Future<Integer> timer = pool.submit(() -> store.tick(256));
      signal.get();
      timer.get();
    }
    List<Event> h = store.history(tenant, id);
    assertEquals(
        1,
        h.stream()
            .filter(e -> e.type() == EventType.SIGNAL || e.type() == EventType.TIMER_FIRED)
            .count());
    assertEquals(State.COMPLETED, store.get(tenant, id).state());
    String hash =
        store.deploy(
            tenant,
            new Definition(
                "dormant",
                1,
                List.of(node("wait", Kind.TIMER, List.of(), Safety.PURE, null, 3600))));
    int before = Thread.activeCount();
    for (int i = 0; i < 32; i++) store.start(tenant, hash, "timer-" + i, Map.of());
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO workflow_instance(id,tenant,definition_hash,semantics,input,start_key,request_hash,state) SELECT gen_random_uuid(),?,?,'1','{}','bulk-'||n,'bulk','WAITING' FROM generate_series(1,5000)n")) {
      s.setString(1, tenant);
      s.setString(2, hash);
      assertEquals(5000, s.executeUpdate());
      try (PreparedStatement t =
          c.prepareStatement(
              "INSERT INTO timer SELECT id,'wait',clock_timestamp()+interval '1 hour','WAITING' FROM workflow_instance WHERE tenant=? AND start_key LIKE 'bulk-%'")) {
        t.setString(1, tenant);
        assertEquals(5000, t.executeUpdate());
      }
    }
    assertEquals(0, store.tick(1000));
    assertTrue(Thread.activeCount() < before + 20);
  }

  void expire(WorkflowStore.Claim q) throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement(
                "UPDATE activity_task SET lease_until=clock_timestamp()-interval '1 second' WHERE instance_id=? AND logical_id=?")) {
      s.setObject(1, q.instance());
      s.setString(2, q.task());
      assertEquals(1, s.executeUpdate());
    }
  }

  void makeReady(UUID id) throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement(
                "UPDATE activity_task SET due_at=clock_timestamp()-interval '1 second' WHERE instance_id=? AND status='READY'")) {
      s.setObject(1, id);
      s.executeUpdate();
    }
  }

  WorkflowStore.Claim readyClaim() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var result = store.claim(tenant, "operator-resumed", 1, 60);
      if (!result.isEmpty()) return result.getFirst();
      Thread.sleep(100);
    }
    throw new AssertionError("no compensation ready");
  }

  Process process(String mode, Path marker, String arg1, String arg2) throws Exception {
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m",
            "-cp",
            System.getProperty("java.class.path"),
            CrashProcess.class.getName(),
            mode,
            pg.getJdbcUrl(),
            pg.getUsername(),
            pg.getPassword(),
            marker.toString(),
            arg1,
            arg2)
        .redirectErrorStream(true)
        .redirectOutput(marker.resolveSibling("process.log").toFile())
        .start();
  }

  void awaitFile(Path path, Process process) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!Files.exists(path) && process.isAlive() && System.nanoTime() < until) Thread.sleep(20);
    if (!Files.exists(path)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    assertTrue(Files.exists(path), () -> "child did not reach fault boundary: " + path);
  }

  @Test
  void protectedApiVerifiesRealOidcTokensAndTenantPermissions() throws Exception {
    var key =
        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("local-test-key").generate();
    var wrong =
        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("local-test-key").generate();
    var issuerServer =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 8);
    String issuer = "http://127.0.0.1:" + issuerServer.getAddress().getPort();
    String discovery =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "issuer",
                    issuer,
                    "jwks_uri",
                    issuer + "/keys",
                    "authorization_endpoint",
                    issuer + "/authorize",
                    "token_endpoint",
                    issuer + "/token",
                    "response_types_supported",
                    List.of("code"),
                    "subject_types_supported",
                    List.of("public"),
                    "id_token_signing_alg_values_supported",
                    List.of("RS256")));
    issuerServer.createContext(
        "/.well-known/openid-configuration",
        x -> {
          byte[] bytes = discovery.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, bytes.length);
          x.getResponseBody().write(bytes);
          x.close();
        });
    issuerServer.createContext(
        "/keys",
        x -> {
          byte[] bytes =
              new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK())
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, bytes.length);
          x.getResponseBody().write(bytes);
          x.close();
        });
    issuerServer.start();
    org.springframework.context.ConfigurableApplicationContext context = null;
    try {
      context =
          org.springframework.boot.SpringApplication.run(
              Application.class,
              "--server.port=0",
              "--server.address=127.0.0.1",
              "--DB_URL=" + pg.getJdbcUrl(),
              "--DB_USER=" + pg.getUsername(),
              "--DB_PASSWORD=" + pg.getPassword(),
              "--OIDC_ISSUER=" + issuer,
              "--OIDC_AUDIENCE=durableflow");
      int port =
          Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
      UUID id = instance(one(Safety.PURE));
      String endpoint = "http://127.0.0.1:" + port + "/v1/executions/" + id;
      try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient()) {
        assertEquals(401, status(client, endpoint, null));
        String valid = jwt(key, issuer, "durableflow", tenant, "viewer", 60);
        for (Object malformed : List.of(42L, List.of("synthetic-test"), "", "   "))
          assertEquals(
              401, status(client, endpoint, rawClaimsJwt(key, issuer, tenant, "sub", malformed)));
        assertEquals(401, status(client, endpoint, rawClaimsJwt(key, issuer, tenant, "sub", null)));
        for (Object malformed : List.of(42L, List.of(tenant), true))
          assertEquals(
              401,
              status(client, endpoint, rawClaimsJwt(key, issuer, tenant, "tenant", malformed)));

        for (String missing : List.of("exp", "sub", "tenant")) {
          assertEquals(
              401, status(client, endpoint, alteredJwt(key, issuer, tenant, missing, false)));
        }
        assertEquals(401, status(client, endpoint, alteredJwt(key, issuer, tenant, "", true)));

        assertEquals(200, status(client, endpoint, valid));
        assertEquals(
            404,
            status(
                client, endpoint, jwt(key, issuer, "durableflow", "other-tenant", "viewer", 60)));
        assertEquals(
            403, status(client, endpoint, jwt(key, issuer, "durableflow", tenant, "worker", 60)));
        assertEquals(
            401, status(client, endpoint, jwt(wrong, issuer, "durableflow", tenant, "viewer", 60)));
        assertEquals(
            401,
            status(
                client,
                endpoint,
                jwt(key, issuer + "/wrong", "durableflow", tenant, "viewer", 60)));
        assertEquals(
            401, status(client, endpoint, jwt(key, issuer, "wrong", tenant, "viewer", 60)));
        assertEquals(
            401, status(client, endpoint, jwt(key, issuer, "durableflow", tenant, "viewer", -120)));
        var request =
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/v1/definitions"))
                .header("Authorization", "Bearer " + valid)
                .header("Content-Type", "application/json")
                .POST(
                    java.net.http.HttpRequest.BodyPublishers.ofString(
                        new ObjectMapper().writeValueAsString(one(Safety.PURE))))
                .build();
        assertEquals(
            403,
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        String operatorToken = jwt(key, issuer, "durableflow", tenant, "operator", 300),
            workerToken = jwt(key, issuer, "durableflow", tenant, "worker", 300),
            deployerToken = jwt(key, issuer, "durableflow", tenant, "deployer", 300);
        Definition typedApi =
            new Definition(
                "typed-api",
                1,
                List.of(
                    new Node(
                        "wait",
                        Kind.SIGNAL,
                        List.of(),
                        null,
                        null,
                        null,
                        Type.INTEGER,
                        Type.INTEGER,
                        3600,
                        "number",
                        null,
                        null),
                    new Node(
                        "sum",
                        Kind.ACTIVITY,
                        List.of("wait"),
                        "sum",
                        Safety.PURE,
                        null,
                        Type.INTEGER,
                        Type.INTEGER,
                        0,
                        null,
                        null,
                        null)));
        String base = "http://127.0.0.1:" + port;
        var deployed =
            post(
                client,
                base + "/v1/definitions",
                deployerToken,
                new ObjectMapper().writeValueAsString(typedApi),
                null);
        assertEquals(200, deployed.statusCode());
        String definitionHash = new ObjectMapper().readTree(deployed.body()).get("hash").asText();
        String wrongStart =
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "definitionHash",
                        definitionHash,
                        "input",
                        Map.of(
                            "payload",
                            Map.of("type", "INTEGER", "data", "3"),
                            "context",
                            Map.of())));
        assertEquals(
            400,
            post(client, base + "/v1/executions", operatorToken, wrongStart, "wrong-type-api")
                .statusCode());
        String typedStart =
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "definitionHash",
                        definitionHash,
                        "input",
                        new Data.ExecutionInput(Data.Value.integer(3), Map.of())));
        var typedStarted =
            post(client, base + "/v1/executions", operatorToken, typedStart, "typed-api");
        assertEquals(200, typedStarted.statusCode());
        UUID typedId =
            UUID.fromString(new ObjectMapper().readTree(typedStarted.body()).get("id").asText());
        String signals = base + "/v1/executions/" + typedId + "/signals";
        assertEquals(
            400,
            post(
                    client,
                    signals,
                    operatorToken,
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("name", "number", "value", Data.Value.bool(true))),
                    "bad-type")
                .statusCode());
        assertEquals(1, store.history(tenant, typedId).size());
        assertEquals(
            200,
            post(
                    client,
                    signals,
                    operatorToken,
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("name", "number", "value", Data.Value.integer(7))),
                    "good-type")
                .statusCode());
        var claimed =
            post(
                client,
                base + "/v1/worker/claim",
                workerToken,
                "{\"worker\":\"typed-api-worker\",\"batch\":16,\"leaseSeconds\":60}",
                null);
        assertEquals(200, claimed.statusCode());
        List<WorkflowStore.Claim> apiClaims =
            new ObjectMapper()
                .readValue(
                    claimed.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<
                        List<WorkflowStore.Claim>>() {});
        WorkflowStore.Claim apiSum =
            apiClaims.stream().filter(q -> q.instance().equals(typedId)).findFirst().orElseThrow();
        long typedVersion = store.get(tenant, typedId).version();
        assertEquals(
            400,
            post(
                    client,
                    base + "/v1/worker/complete",
                    workerToken,
                    new ObjectMapper()
                        .writeValueAsString(
                            Map.of("claim", apiSum, "result", List.of(Data.Value.text("7")))),
                    null)
                .statusCode());
        assertEquals(typedVersion, store.get(tenant, typedId).version());
        try (Worker pure = new Worker(store, URI.create("http://127.0.0.1:1/effects"))) {
          var actual = pure.execute(apiSum, "normal");
          assertEquals(List.of(Data.Value.integer(7)), actual);
          assertEquals(
              200,
              post(
                      client,
                      base + "/v1/worker/complete",
                      workerToken,
                      new ObjectMapper()
                          .writeValueAsString(Map.of("claim", apiSum, "result", actual)),
                      null)
                  .statusCode());
        }
        assertEquals(State.COMPLETED, store.get(tenant, typedId).state());
        String deployer = jwt(key, issuer, "durableflow", tenant, "deployer", 60);
        var oversized =
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + port + "/v1/definitions"))
                .header("Authorization", "Bearer " + deployer)
                .header("Content-Type", "application/json")
                .POST(
                    java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(new byte[9000])))
                .build();
        assertEquals(
            413,
            client
                .send(oversized, java.net.http.HttpResponse.BodyHandlers.ofString())
                .statusCode());
        WorkerEnvelopeFixture.verify(store, client, base, workerToken, tenant);
        context.getBean(WorkflowStore.class).close();
        var unavailable =
            client.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/health"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(503, unavailable.statusCode());
        var problem = new ObjectMapper().readTree(unavailable.body());
        assertEquals(503, problem.path("status").asInt());
        assertEquals("Workflow storage unavailable", problem.path("title").asText());
        assertFalse(unavailable.body().contains(pg.getPassword()));
        assertFalse(unavailable.body().contains("HikariDataSource"));
      }
    } finally {
      if (context != null) context.close();
      issuerServer.stop(0);
    }
  }

  int status(java.net.http.HttpClient client, String endpoint, String token) throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
            .timeout(java.time.Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    var response =
        client.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      assertTrue(
          response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .startsWith("application/problem+json"),
          "status="
              + response.statusCode()
              + ", content-type="
              + response.headers().firstValue("Content-Type").orElse("missing"));
      var problem = new ObjectMapper().readTree(response.body());
      assertEquals(response.statusCode(), problem.get("status").asInt());
      assertFalse(response.body().contains("stackTrace"));
    }
    return response.statusCode();
  }

  String jwt(
      com.nimbusds.jose.jwk.RSAKey key,
      String issuer,
      String audience,
      String tenant,
      String scope,
      int seconds)
      throws Exception {
    var claims =
        new com.nimbusds.jwt.JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("synthetic-test")
            .claim("tenant", tenant)
            .claim("scope", scope)
            .issueTime(new java.util.Date())
            .expirationTime(java.util.Date.from(Instant.now().plusSeconds(seconds)))
            .build();
    var signed =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .build(),
            claims);
    signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
    return signed.serialize();
  }

  @Test
  void nonIdempotentDispatchCannotBeAuthorizedTwice() throws Exception {
    UUID id = instance(one(Safety.NON_IDEMPOTENT));
    WorkflowStore.Claim q = claim();
    Path log = Files.createTempDirectory("durableflow-once-dispatch").resolve("effects.log");
    try (FakeProvider provider = new FakeProvider(0, log);
        Worker worker =
            new Worker(store, URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
      var result = worker.execute(q, "normal");
      assertThrows(IllegalStateException.class, () -> worker.execute(q, "normal"));
      assertEquals(1, provider.effectCount());
      try (var lines = Files.lines(log)) {
        assertEquals(1, lines.filter(l -> l.startsWith("CALL\t")).count());
      }
      store.complete(q, result);
      assertEquals(State.COMPLETED, store.get(tenant, id).state());
    }
  }

  @Test
  void generatedCrashSchedulesPreserveLogicalIdentity() throws Exception {
    Random random = new Random(20261018);
    for (int schedule = 0; schedule < 30; schedule++) {
      tenant = "generated-" + UUID.randomUUID();
      UUID id = instance(one(Safety.PURE));
      WorkflowStore.Claim current = claim();
      int expirations = random.nextInt(3);
      for (int fault = 0; fault < expirations; fault++) {
        WorkflowStore.Claim stale = current;
        expire(stale);
        store.tick(256);
        makeReady(id);
        current = claim();
        long version = store.get(tenant, id).version();
        assertThrows(IllegalStateException.class, () -> store.dispatch(stale));
        assertThrows(IllegalStateException.class, () -> store.complete(stale, "stale"));
        assertEquals(version, store.get(tenant, id).version());
      }
      store.dispatch(current);
      store.complete(current, "done");
      assertEquals(State.COMPLETED, store.get(tenant, id).state());
      var replay = new Interpreter().replay(one(Safety.PURE), store.history(tenant, id), Map.of());
      assertEquals(State.COMPLETED, replay.state());
      assertTrue(replay.decisions().isEmpty());
    }
  }

  String alteredJwt(
      com.nimbusds.jose.jwk.RSAKey key,
      String issuer,
      String tenant,
      String missing,
      boolean future)
      throws Exception {
    Map<String, Object> claims = new HashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", List.of("durableflow"));
    claims.put("sub", "synthetic-test");
    claims.put("tenant", tenant);
    claims.put("scope", "viewer");
    claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
    if (!missing.isEmpty()) claims.remove(missing);
    if (future) claims.put("nbf", Instant.now().plusSeconds(300).getEpochSecond());
    var token =
        new com.nimbusds.jwt.SignedJWT(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .build(),
            com.nimbusds.jwt.JWTClaimsSet.parse(claims));
    token.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
    return token.serialize();
  }

  @Test
  void claimingAndCancellationSerializeBeforeDispatch() throws Exception {
    try (ExecutorService concurrency = Executors.newFixedThreadPool(2)) {
      for (int race = 0; race < 20; race++) {
        tenant = "cancel-race-" + UUID.randomUUID();
        String scope = tenant;
        UUID id = instance(one(Safety.PURE));
        CountDownLatch ready = new CountDownLatch(1);
        Future<List<WorkflowStore.Claim>> claims =
            concurrency.submit(
                () -> {
                  ready.await();
                  return store.claim(scope, "racing", 1, 60);
                });
        Future<?> cancel =
            concurrency.submit(
                () -> {
                  try {
                    ready.await();
                    store.cancel(scope, id);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                });
        ready.countDown();
        List<WorkflowStore.Claim> claimed = claims.get();
        cancel.get();
        for (WorkflowStore.Claim q : claimed) {
          long version = store.get(scope, id).version();
          assertThrows(IllegalStateException.class, () -> store.dispatch(q));
          assertEquals(version, store.get(scope, id).version());
          expire(q);
        }
        store.tick(256);
        assertEquals(State.CANCELLED, store.get(scope, id).state());
      }
    }
  }

  @Test
  void typedRuntimeRejectsAtomicWrongTypesAndSendsActualPredecessorValues() throws Exception {
    Definition d =
        new Definition(
            "typed-order",
            1,
            List.of(
                new Node(
                    "wait",
                    Kind.SIGNAL,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.INTEGER,
                    Type.INTEGER,
                    3600,
                    "number",
                    null,
                    null),
                new Node(
                    "sum",
                    Kind.ACTIVITY,
                    List.of("wait"),
                    "sum",
                    Safety.PURE,
                    null,
                    Type.INTEGER,
                    Type.INTEGER,
                    0,
                    null,
                    null,
                    null),
                new Node(
                    "choice",
                    Kind.BRANCH,
                    List.of("sum"),
                    null,
                    null,
                    null,
                    Type.INTEGER,
                    Type.BOOLEAN,
                    0,
                    null,
                    "value.sum==7",
                    null),
                new Node(
                    "reserve",
                    Kind.ACTIVITY,
                    List.of("choice"),
                    "reserve",
                    Safety.IDEMPOTENT_WITH_KEY,
                    "refund",
                    Type.BOOLEAN,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null)),
            Map.of("approved", Type.BOOLEAN));
    String hash = store.deploy(tenant, d);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.start(
                tenant,
                hash,
                "wrong-root",
                new Data.ExecutionInput(
                    Data.Value.text("3"), Map.of("approved", Data.Value.bool(true)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.start(
                tenant,
                hash,
                "wrong-context",
                new Data.ExecutionInput(
                    Data.Value.integer(3), Map.of("approved", Data.Value.text("true")))));
    UUID id =
        store.start(
            tenant,
            hash,
            "typed",
            new Data.ExecutionInput(
                Data.Value.integer(3), Map.of("approved", Data.Value.bool(true))));
    long before = store.get(tenant, id).version();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.signal(tenant, id, "bad-signal", "number", Data.Value.text("7")));
    assertEquals(before, store.get(tenant, id).version());
    assertTrue(store.signal(tenant, id, "number", "number", Data.Value.integer(7)));
    WorkflowStore.Claim sum = claim();
    Data.TaskInput sumArguments =
        new ObjectMapper().readValue(sum.parameters(), Data.TaskInput.class);
    assertEquals(List.of(Data.Value.integer(7)), sumArguments.arguments());
    long completionVersion = store.get(tenant, id).version();
    assertThrows(
        IllegalArgumentException.class, () -> store.complete(sum, List.of(Data.Value.text("7"))));
    assertEquals(completionVersion, store.get(tenant, id).version());
    int completionHistorySize = store.history(tenant, id).size();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.complete(sum, List.of(Data.Value.integer(7), Data.Value.integer(8))));
    assertEquals(completionVersion, store.get(tenant, id).version());
    assertEquals(completionHistorySize, store.history(tenant, id).size());
    Path log = Files.createTempDirectory("typed-provider").resolve("effects.log");
    try (FakeProvider provider = new FakeProvider(0, log);
        Worker worker =
            new Worker(store, URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
      store.complete(sum, worker.execute(sum, "normal"));
      WorkflowStore.Claim reserve = claim();
      Data.TaskInput actual =
          new ObjectMapper().readValue(reserve.parameters(), Data.TaskInput.class);
      assertEquals(List.of(Data.Value.bool(true)), actual.arguments());
      assertEquals(List.of(Data.Value.bool(true)), actual.predecessors().get("choice").values());
      assertNotEquals(actual.execution().payload(), actual.arguments().getFirst());
      store.complete(reserve, worker.execute(reserve, "normal"));
      assertEquals(1, provider.effectCount());
      try (var lines = Files.lines(log)) {
        String call = lines.filter(l -> l.startsWith("CALL\t")).findFirst().orElseThrow();
        String body =
            new String(
                Base64.getDecoder().decode(call.split("\t")[2]),
                java.nio.charset.StandardCharsets.UTF_8);
        Data.TaskInput observed =
            new ObjectMapper()
                .readValue(body.substring(body.indexOf(':') + 1), Data.TaskInput.class);
        assertEquals(actual, observed);
      }
    }
    assertEquals(State.COMPLETED, store.get(tenant, id).state());
    var replay =
        new Interpreter()
            .replay(
                d,
                store.history(tenant, id),
                new Data.ExecutionInput(
                    Data.Value.integer(3), Map.of("approved", Data.Value.bool(true))));
    assertEquals(store.get(tenant, id).values(), replay.values());
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement(
                "SELECT count(*) FROM workflow_instance WHERE tenant=? AND start_key IN ('wrong-root','wrong-context')")) {
      s.setString(1, tenant);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        assertEquals(0, r.getLong(1));
      }
    }
  }

  @Test
  void persistedLegacyPinsRemainSemanticsOneAcrossTypedUpgrade() throws Exception {
    LegacyDefinitionV1 original =
        new LegacyDefinitionV1(
            "legacy-wait",
            1,
            List.of(
                new LegacyDefinitionV1.Node(
                    "timer",
                    LegacyDefinitionV1.Kind.TIMER,
                    List.of(),
                    null,
                    null,
                    null,
                    LegacyDefinitionV1.Type.TEXT,
                    LegacyDefinitionV1.Type.TEXT,
                    0,
                    null,
                    null,
                    null)));
    String hash = original.contentHash();
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO workflow_definition(tenant,name,version,hash,body) VALUES(?,?,?,?,?)")) {
      s.setString(1, tenant);
      s.setString(2, original.name());
      s.setInt(3, 1);
      s.setString(4, hash);
      s.setString(5, new ObjectMapper().writeValueAsString(original));
      s.executeUpdate();
    }
    UUID legacy = store.start(tenant, hash, "old", Map.of());
    assertEquals("1", store.get(tenant, legacy).semantics());
    store.tick(256);
    assertEquals(
        List.of(Data.Value.text("timeout")),
        store.get(tenant, legacy).values().get("timer").values());
    Definition upgraded =
        new Definition(
            "legacy-wait",
            2,
            List.of(
                new Node(
                    "timer",
                    Kind.TIMER,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null)));
    UUID typed =
        store.start(
            tenant,
            store.deploy(tenant, upgraded),
            "new",
            new Data.ExecutionInput(Data.Value.text("business-input"), Map.of()));
    store.tick(256);
    assertEquals("3", store.get(tenant, typed).semantics());
    assertEquals(
        List.of(Data.Value.text("business-input")),
        store.get(tenant, typed).values().get("timer").values());
    assertEquals(hash, store.get(tenant, legacy).definitionHash());
    assertEquals("1", store.get(tenant, legacy).semantics());
  }

  @Test
  void persistedTypedTwoCancellationRemainsPinnedAcrossEvidenceThree() throws Exception {
    Definition old =
        new Definition(
            "typed-cancel-pin",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), Safety.PURE, null, 0),
                node("left", Kind.ACTIVITY, List.of("root"), Safety.PURE, null, 0),
                node(
                    "right",
                    Kind.ACTIVITY,
                    List.of("root"),
                    Safety.IDEMPOTENT_WITH_KEY,
                    "refund",
                    0)));
    String oldHash = old.contentHash();
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        PreparedStatement insert =
            c.prepareStatement(
                "INSERT INTO workflow_definition(tenant,name,version,hash,body,semantics) VALUES(?,?,?,?,?,'2')")) {
      insert.setString(1, tenant);
      insert.setString(2, old.name());
      insert.setInt(3, old.version());
      insert.setString(4, oldHash);
      insert.setString(5, new ObjectMapper().writeValueAsString(old));
      insert.executeUpdate();
    }
    Definition upgraded = new Definition(old.name(), 2, old.nodes());
    Path log = Files.createTempDirectory("typed-cancel-pin").resolve("effects.log");
    try (FakeProvider provider = new FakeProvider(0, log);
        Worker worker =
            new Worker(store, URI.create("http://127.0.0.1:" + provider.port() + "/effects"))) {
      for (String pin : List.of("2", "3")) {
        String hash = pin.equals("2") ? oldHash : store.deploy(tenant, upgraded);
        UUID id = store.start(tenant, hash, "pin-" + pin, Map.of());
        store.complete(claim(), "root-result");
        var parallel = store.claim(tenant, "parallel-pin", 2, 60);
        WorkflowStore.Claim
            left = parallel.stream().filter(q -> q.task().equals("left")).findFirst().orElseThrow(),
            right =
                parallel.stream().filter(q -> q.task().equals("right")).findFirst().orElseThrow();
        var actualEffect = worker.execute(right, "normal");
        for (int attempt = 1; attempt <= 4; attempt++) {
          store.fail(left, true, "pure activity rejected");
          if (attempt < 4) {
            makeReady(id);
            left = claim();
          }
        }
        assertEquals(State.CANCEL_REQUESTED, store.get(tenant, id).state());
        store.cancel(tenant, id);
        assertEquals(State.CANCEL_REQUESTED, store.get(tenant, id).state());
        var cancelled = store.history(tenant, id).getLast();
        assertEquals(EventType.CANCELLED, cancelled.type());
        assertEquals(
            pin.equals("2") ? List.of() : List.of(Data.Value.integer(1)), cancelled.result());
        if (pin.equals("2")) {
          var before = store.history(tenant, id);
          store.deploy(tenant, upgraded);
          assertEquals("2", store.get(tenant, id).semantics());
          assertEquals(oldHash, store.get(tenant, id).definitionHash());
          assertEquals(before, store.history(tenant, id));
        }
        store.complete(right, actualEffect);
        assertEquals(State.COMPENSATING, store.get(tenant, id).state());
        var undo = claim();
        assertEquals("comp-right", undo.task());
        store.complete(undo, worker.execute(undo, "normal"));
        assertEquals(State.CANCELLED, store.get(tenant, id).state());
        assertEquals(pin, store.get(tenant, id).semantics());
        if (pin.equals("3"))
          assertEquals(
              State.CANCELLED,
              new Interpreter().replay(upgraded, store.history(tenant, id), Map.of()).state());
      }
      assertEquals(4, provider.effectCount());
    }
    UUID terminal =
        store.start(
            tenant,
            store.deploy(
                tenant,
                new Definition(
                    "terminal-cancel",
                    1,
                    List.of(node("root", Kind.ACTIVITY, List.of(), Safety.PURE, null, 0)))),
            "terminal",
            Map.of());
    var root = claim();
    for (int attempt = 1; attempt <= 4; attempt++) {
      store.fail(root, true, "rejected");
      if (attempt < 4) {
        makeReady(terminal);
        root = claim();
      }
    }
    assertEquals(State.FAILED, store.get(tenant, terminal).state());
    var history = store.history(tenant, terminal);
    long version = store.get(tenant, terminal).version();
    assertThrows(IllegalArgumentException.class, () -> store.cancel(tenant, terminal));
    assertEquals(version, store.get(tenant, terminal).version());
    assertEquals(history, store.history(tenant, terminal));
  }

  String rawClaimsJwt(
      com.nimbusds.jose.jwk.RSAKey key, String issuer, String tenant, String field, Object original)
      throws Exception {
    Map<String, Object> claims = new HashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", List.of("durableflow"));
    claims.put("sub", "synthetic-test");
    claims.put("tenant", tenant);
    claims.put("scope", "viewer");
    claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
    claims.put(field, original);
    var signed =
        new com.nimbusds.jose.JWSObject(
            new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .build(),
            new com.nimbusds.jose.Payload(new ObjectMapper().writeValueAsString(claims)));
    signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
    return signed.serialize();
  }

  java.net.http.HttpResponse<String> post(
      java.net.http.HttpClient client, String url, String token, String body, String key)
      throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
    if (key != null) request.header("Idempotency-Key", key);
    var response =
        client.send(
            request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 400) {
      assertTrue(
          response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .startsWith("application/problem+json"),
          "status="
              + response.statusCode()
              + ", content-type="
              + response.headers().firstValue("Content-Type").orElse("missing"));
      assertEquals(
          response.statusCode(),
          new ObjectMapper().readTree(response.body()).get("status").asInt());
    }
    return response;
  }
}
