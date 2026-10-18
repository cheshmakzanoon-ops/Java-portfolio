package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.zaxxer.hikari.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationIT {
  PostgreSQLContainer db;
  GenericContainer<?> keycloak;
  HikariDataSource runtime;
  PolicyStore store;
  ConfigurableApplicationContext app;
  String issuer, base, alice, admin;
  ObjectMapper json = new ObjectMapper();
  HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  PolicyEngine.Definition definition;
  PolicyEngine.Compiled policy;

  @BeforeAll
  void start() throws Exception {
    db =
        new PostgreSQLContainer(
                DockerImageName.parse(
                        "postgres:18.3@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tenantshield")
            .withUsername("migration")
            .withPassword("local-migration-only");
    db.start();
    Flyway.configure()
        .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
        .locations("filesystem:../policy-store/src/main/resources/db/migration")
        .load()
        .migrate();
    keycloak =
        new GenericContainer<>(
                DockerImageName.parse(
                    "quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244"))
            .withExposedPorts(8080)
            .withEnv("JAVA_OPTS_KC_HEAP", "-Xms256m -Xmx768m -XX:ActiveProcessorCount=2")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("../infra/realm.json").toAbsolutePath()),
                "/opt/keycloak/data/import/realm.json")
            .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
            .waitingFor(
                org.testcontainers.containers.wait.strategy.Wait.forHttp(
                        "/realms/tenantshield/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(5)));
    keycloak.start();
    issuer =
        "http://"
            + keycloak.getHost()
            + ":"
            + keycloak.getMappedPort(8080)
            + "/realms/tenantshield";
    seed();
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(db.getJdbcUrl());
    hc.setUsername("tenantshield_runtime");
    hc.setPassword("local-tenantshield-only");
    hc.setMaximumPoolSize(2);
    hc.setConnectionTimeout(2000);
    runtime = new HikariDataSource(hc);
    store = new PolicyStore(runtime);
    definition =
        new PolicyEngine.Definition(
            Map.of("reader", Set.of(), "editor", Set.of("reader")),
            List.of(new PolicyEngine.Grant("reader", "document:read", "document")),
            List.of(
                new PolicyEngine.Rule(
                    "read",
                    "document:read",
                    "document",
                    PolicyEngine.Effect.ALLOW,
                    "hasRole(\"reader\") and resource.department == subject.department and subject.clearance >= resource.classification"),
                new PolicyEngine.Rule(
                    "suspend",
                    "document:read",
                    "document",
                    PolicyEngine.Effect.DENY,
                    "subject.suspended")));
    policy = PolicyEngine.compile(definition);
    for (String tenant : List.of("harbor", "orchard")) {
      long v = store.draft(tenant, principal("admin"), json.writeValueAsString(definition), policy);
      store.publish(tenant, principal("admin"), v, 0);
    }
    app =
        new SpringApplicationBuilder(DecisionApplication.class)
            .properties(
                Map.of(
                    "server.port",
                    "0",
                    "tenantshield.db.url",
                    db.getJdbcUrl(),
                    "tenantshield.db.password",
                    "local-tenantshield-only",
                    "tenantshield.oidc.issuer",
                    issuer,
                    "tenantshield.oidc.jwks",
                    issuer + "/protocol/openid-connect/certs",
                    "spring.flyway.enabled",
                    "false"))
            .run("--server.port=0");
    base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    alice = token("alice");
    admin = token("admin");
  }

  void seed() throws Exception {
    try (Connection c =
        DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
      for (String tenant : List.of("harbor", "orchard")) {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO tenant VALUES(?,1)")) {
          s.setString(1, tenant);
          s.executeUpdate();
        }
        for (String who : tenant.equals("harbor") ? List.of("admin", "alice") : List.of("admin")) {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO membership VALUES(?,?,?,ARRAY['reader'],'support',3,false,?,true)")) {
            s.setString(1, tenant);
            s.setString(2, issuer);
            s.setString(3, who);
            s.setBoolean(4, who.equals("admin"));
            s.executeUpdate();
          }
        }
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO resource VALUES(?,'doc-7','document',?,2,1),(?,'doc-missing','document','support',NULL,1)")) {
          s.setString(1, tenant);
          s.setString(2, tenant.equals("harbor") ? "support" : "finance");
          s.setString(3, tenant);
          s.executeUpdate();
        }
      }
    }
  }

  PolicyStore.Principal principal(String s) {
    return new PolicyStore.Principal(issuer, s);
  }

  String token(String user) throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&client_id=portfolio-demo&username="
                            + user
                            + "&password=local-pass"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    return json.readTree(r.body()).get("access_token").asText();
  }

  HttpResponse<String> decision(String tenant, String id, String token) throws Exception {
    return send(
        "POST",
        "/v1/tenants/" + tenant + "/decisions",
        token,
        "{\"action\":\"document:read\",\"resource\":{\"type\":\"document\",\"id\":\""
            + id
            + "\"},\"context\":{\"purpose\":\"support\"}}");
  }

  HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realOidcAndTenantBindingRejectAdversarialRequests() throws Exception {
    assertThat(json.readTree(decision("harbor", "doc-7", alice).body()).get("decision").asText())
        .isEqualTo("ALLOW");
    assertThat(json.readTree(decision("orchard", "doc-7", alice).body()).get("decision").asText())
        .isEqualTo("DENY");
    assertThat(
            json.readTree(decision("harbor", "doc-missing", alice).body()).get("reason").asText())
        .isEqualTo("INDETERMINATE");
    assertThat(
            json.readTree(decision("harbor", "does-not-exist", alice).body())
                .get("decision")
                .asText())
        .isEqualTo("DENY");
    assertThat(decision("harbor", "doc-7", token("mallory")).body()).contains("NO_MEMBERSHIP");
    assertThat(
            send(
                    "POST",
                    "/v1/tenants/harbor/decisions",
                    alice,
                    "{\"subjectId\":\"admin\",\"action\":\"document:read\",\"resource\":{\"type\":\"document\",\"id\":\"doc-7\"}}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    "/v1/tenants/harbor/decisions",
                    alice,
                    "{\"action\":\"document:read\",\"resource\":{\"type\":\"document\",\"id\":\"doc-7\"},\"context\":{\"subject.clearance\":999}}")
                .statusCode())
        .isEqualTo(400);
    assertThat(send("GET", "/v1/tenants/harbor/audit", alice, "").statusCode()).isEqualTo(403);
    assertThat(
            decision(
                    "harbor",
                    "doc-7",
                    alice.substring(0, alice.lastIndexOf('.') + 1) + "invalidsignature")
                .statusCode())
        .isEqualTo(401);
    var rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
    for (var claims :
        List.of(
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("alice")
                .audience("tenantshield")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build(),
            new JWTClaimsSet.Builder()
                .issuer("http://127.0.0.1:9/arbitrary-discovery")
                .subject("alice")
                .audience("wrong")
                .issueTime(new Date())
                .expirationTime(new Date(0))
                .notBeforeTime(Date.from(Instant.now().plusSeconds(3600)))
                .build())) {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rogue").build(), claims);
      jwt.sign(new RSASSASigner(rogue));
      assertThat(decision("harbor", "doc-7", jwt.serialize()).statusCode()).isEqualTo(401);
    }
    JwtDecoder decoder =
        DecisionApplication.trustedDecoder(
            issuer, issuer + "/protocol/openid-connect/certs", "wrong-audience");
    assertThatThrownBy(() -> decoder.decode(alice)).isInstanceOf(JwtException.class);
    assertThatThrownBy(
            () ->
                DecisionApplication.trustedDecoder(
                        "http://127.0.0.1:9/evil",
                        issuer + "/protocol/openid-connect/certs",
                        "tenantshield")
                    .decode(alice))
        .isInstanceOf(JwtException.class);
  }

  PolicyEngine.Decision evaluate(PolicyStore.Snapshot s) {
    if (s.membership() == null || !s.membership().active())
      return new PolicyEngine.Decision(false, "NO_MEMBERSHIP", List.of());
    return PolicyEngine.decide(
        PolicyEngine.compile(json.readValue(s.policy().content(), PolicyEngine.Definition.class)),
        s.membership().roles(),
        "document:read",
        "document",
        Map.of(
            "subject.department",
            s.membership().department(),
            "subject.clearance",
            s.membership().clearance(),
            "subject.suspended",
            s.membership().suspended(),
            "resource.department",
            s.resource().department(),
            "resource.classification",
            s.resource().classification()));
  }

  @Test
  void pooledTenantIsolationImmutableRevisionsAndSnapshotRevocationBoundary() throws Exception {
    assertThat(decision("harbor", "doc-7", alice).body()).contains("ALLOW");
    assertThat(app.getBean(DecisionController.class).cacheSize()).isGreaterThan(0);
    for (int n = 0; n < 100; n++) {
      String tenant = n % 2 == 0 ? "harbor" : "orchard";
      var d = store.decide(tenant, principal("alice"), "document:read", "doc-7", this::evaluate);
      assertThat(d.decision().allowed()).isEqualTo(tenant.equals("harbor"));
    }
    try (Connection c = runtime.getConnection();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT count(*) FROM resource")) {
      r.next();
      assertThat(r.getInt(1)).isZero();
    }
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<PolicyStore.Outcome> before =
          ex.submit(
              () ->
                  store.decide(
                      "harbor",
                      principal("alice"),
                      "document:read",
                      "doc-7",
                      snapshot -> {
                        started.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("release deadline");
                        } catch (InterruptedException e) {
                          throw new RuntimeException(e);
                        }
                        return evaluate(snapshot);
                      }));
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      long epoch =
          store.membership(
              "harbor",
              principal("admin"),
              principal("alice"),
              new PolicyStore.Membership(Set.of("reader"), "support", 3L, false, false, false));
      release.countDown();
      PolicyStore.Outcome old = before.get(10, TimeUnit.SECONDS);
      assertThat(old.decision().allowed()).isTrue();
      assertThat(old.snapshot().epoch()).isLessThan(epoch);
      for (int n = 0; n < 30; n++) {
        var after = decision("harbor", "doc-7", alice);
        assertThat(json.readTree(after.body()).get("decision").asText()).isEqualTo("DENY");
        assertThat(json.readTree(after.body()).get("tenantEpoch").asLong())
            .isGreaterThanOrEqualTo(epoch);
      }
    } finally {
      store.membership(
          "harbor",
          principal("admin"),
          principal("alice"),
          new PolicyStore.Membership(Set.of("reader"), "support", 3L, false, false, true));
    }
    assertThatThrownBy(() -> store.publish("harbor", principal("admin"), 1, 0))
        .isInstanceOf(PolicyStore.Conflict.class);
    try (Connection c = runtime.getConnection()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('tenantshield.tenant','harbor',true)");
        assertThatThrownBy(
                () ->
                    s.executeUpdate(
                        "UPDATE policy_version SET content='tampered' WHERE tenant_id='harbor'"))
            .isInstanceOf(SQLException.class);
      }
      c.rollback();
    }
    var controller = app.getBean(DecisionController.class);
    long size = controller.cacheSize();
    assertThat(size).isGreaterThan(0);
    controller.evict();
    assertThat(decision("harbor", "doc-7", alice).body()).contains("ALLOW");
    assertThat(store.audits("harbor", principal("admin")))
        .allSatisfy(
            a -> {
              assertThat(a.principal()).hasSize(64);
              assertThat(a.reason()).doesNotContain("Bearer");
            });
  }

  @Test
  void concurrentPublicationKeepsVersionedCacheImmutable() throws Exception {
    long prior =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot()
            .policy()
            .version();
    Map<Long, Boolean> expected = new ConcurrentHashMap<>();
    expected.put(prior, true);
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> readers = new ArrayList<>();
      AtomicInteger seen = new AtomicInteger();
      for (int t = 0; t < 4; t++)
        readers.add(
            workers.submit(
                () -> {
                  try {
                    for (int n = 0; n < 40; n++) {
                      var r = decision("harbor", "doc-7", alice);
                      assertThat(r.statusCode()).isEqualTo(200);
                      var body = json.readTree(r.body());
                      long version = body.get("policyVersion").asLong();
                      assertThat(expected).containsKey(version);
                      assertThat(body.get("decision").asText())
                          .isEqualTo(expected.get(version) ? "ALLOW" : "DENY");
                      seen.incrementAndGet();
                    }
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      for (int n = 0; n < 6; n++) {
        boolean permit = n % 2 == 1;
        var changed =
            new PolicyEngine.Definition(
                definition.parents(),
                definition.grants(),
                List.of(
                    new PolicyEngine.Rule(
                        "versioned",
                        "document:read",
                        "document",
                        PolicyEngine.Effect.ALLOW,
                        permit ? "true" : "false")));
        var compiled = PolicyEngine.compile(changed);
        long version =
            store.draft("harbor", principal("admin"), json.writeValueAsString(changed), compiled);
        expected.put(version, permit);
        store.publish("harbor", principal("admin"), version, prior);
        prior = version;
      }
      for (var reader : readers) reader.get(60, TimeUnit.SECONDS);
      assertThat(seen.get()).isEqualTo(160);
    } finally {
      long current =
          store
              .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
              .snapshot()
              .policy()
              .version();
      store.rollback("harbor", principal("admin"), 1, current);
    }
  }

  @Test
  void rollbackCopiesAndPublishesNewMonotonicRevisionAtomically() throws Exception {
    var before =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    var rolled = store.rollback("harbor", principal("admin"), 1, before.policy().version());
    assertThat(rolled.policyVersion()).isGreaterThan(before.policy().version());
    assertThat(rolled.tenantEpoch()).isGreaterThan(before.epoch());
    var after =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    assertThat(after.policy().version()).isEqualTo(rolled.policyVersion());
    assertThat(after.policy().content())
        .isEqualTo(store.readForAdmin("harbor", principal("admin"), 1).content());
    assertThatThrownBy(
            () -> store.rollback("harbor", principal("admin"), 1, before.policy().version()))
        .isInstanceOf(PolicyStore.Conflict.class);
  }

  @Test
  void requiredAuditAndDatabaseOutageNeverReturnAllow() throws Exception {
    try (Connection c =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Statement s = c.createStatement()) {
      s.execute("REVOKE INSERT ON audit FROM tenantshield_runtime");
      try {
        assertThat(decision("harbor", "doc-7", alice).statusCode()).isEqualTo(503);
      } finally {
        s.execute("GRANT INSERT ON audit TO tenantshield_runtime");
      }
    }
  }

  @Test
  void pausedDatabaseReturnsExplicitFailure() throws Exception {
    db.getDockerClient().pauseContainerCmd(db.getContainerId()).exec();
    try {
      assertThat(decision("harbor", "doc-7", alice).statusCode()).isEqualTo(503);
    } finally {
      db.getDockerClient().unpauseContainerCmd(db.getContainerId()).exec();
    }
  }

  @Test
  void simulationIsPrivilegedAndDoesNotPublishOrGrant() throws Exception {
    var before =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    String body =
        json.writeValueAsString(
            Map.of(
                "policy",
                definition,
                "example",
                Map.of(
                    "roles",
                    List.of("reader"),
                    "action",
                    "document:read",
                    "resourceType",
                    "document",
                    "fields",
                    Map.of(
                        "subject.department",
                        "support",
                        "subject.clearance",
                        3,
                        "subject.suspended",
                        false,
                        "resource.department",
                        "support",
                        "resource.classification",
                        2),
                    "allowed",
                    true,
                    "reason",
                    "MATCHED_ALLOW")));
    assertThat(send("POST", "/v1/tenants/harbor/simulate", alice, body).statusCode())
        .isEqualTo(403);
    assertThat(
            send(
                    "PUT",
                    "/v1/tenants/harbor/memberships/alice",
                    admin,
                    "{\"roles\":[\"reader\"],\"clearance\":1.5,\"active\":true}")
                .statusCode())
        .isEqualTo(400);
    var simulated = send("POST", "/v1/tenants/harbor/simulate", admin, body);
    assertThat(simulated.statusCode()).isEqualTo(200);
    assertThat(json.readTree(simulated.body()).get("allowed").asBoolean()).isTrue();
    var after =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    assertThat(after.epoch()).isEqualTo(before.epoch());
    assertThat(after.policy().version()).isEqualTo(before.policy().version());
  }

  @Test
  void resourceMaintenanceIncrementsVersionAndEpoch() throws Exception {
    var before =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    try (Connection c =
            DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var s = c.createStatement()) {
      s.executeUpdate(
          "UPDATE resource SET classification=classification WHERE tenant_id='harbor' AND resource_id='doc-7'");
    }
    var after =
        store
            .decide("harbor", principal("admin"), "document:read", "doc-7", this::evaluate)
            .snapshot();
    assertThat(after.epoch()).isGreaterThan(before.epoch());
    assertThat(after.resource().version()).isEqualTo(before.resource().version() + 1);
  }

  @Test
  void chunkedBodyLimitRunsBeforeJsonBinding() throws Exception {
    var body =
        HttpRequest.BodyPublishers.ofInputStream(
            () -> new java.io.ByteArrayInputStream(new byte[262145]));
    var result =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/v1/tenants/harbor/decisions"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + alice)
                .header("Content-Type", "application/json")
                .POST(body)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(result.statusCode()).isEqualTo(413);
    assertThat(result.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/problem+json");
  }

  @AfterAll
  void close() {
    if (app != null) app.close();
    if (runtime != null) runtime.close();
    if (keycloak != null) keycloak.stop();
    if (db != null) db.stop();
  }
}
