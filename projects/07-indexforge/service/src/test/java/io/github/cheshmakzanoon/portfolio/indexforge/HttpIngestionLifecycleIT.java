package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real signed HTTP requests exercise admission, cancellation, durable retry and JSON conversion.
 */
class HttpIngestionLifecycleIT {
  @TempDir Path dir;

  private record Server(ConfigurableApplicationContext context, String base, HttpClient client)
      implements AutoCloseable {
    String token(String principal, String tenant) {
      return context
          .getBean(LocalOidcIssuer.class)
          .token(principal, tenant, Instant.now().plusSeconds(300), "indexforge");
    }

    HttpResponse<String> request(String method, String path, String token, String body)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(20))
              .header("Authorization", "Bearer " + token);
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String state(String id, String token) throws Exception {
      var response = request("GET", "/v1/ingestions/" + id, token, null);
      assertEquals(200, response.statusCode(), response.body());
      return JsonMapper.builder().build().readTree(response.body()).get("state").asString();
    }

    @Override
    public void close() {
      context.close();
      client.close();
    }
  }

  private Server start() throws IOException {
    Files.createDirectories(dir.resolve("sources/demo/public/src"));
    Files.writeString(
        dir.resolve("sources/demo/public/src/Retry.java"),
        "class Retry { void persistedOriginalSnapshot() {} }");
    var app = new SpringApplication(IndexForgeApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address", "127.0.0.1",
            "server.port", "0",
            "spring.profiles.active", "dev",
            "indexforge.issuer-port", "0",
            "indexforge.data", dir.resolve("data").toString(),
            "indexforge.sources", dir.resolve("sources").toString(),
            "spring.main.banner-mode", "off"));
    var context = app.run();
    return new Server(
        context,
        "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"),
        HttpClient.newHttpClient());
  }

  private static String body(String key) {
    return "{\"repository\":\"public\",\"revision\":\"v1\",\"idempotencyKey\":\""
        + key
        + "\",\"path\":\"src\"}";
  }

  private static String id(String key) {
    return Digests.sha256("demo\npublic\n" + key);
  }

  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertTrue(condition.getAsBoolean(), "completion deadline");
  }

  @Test
  void cancelledQueuedRegistrationReturnsCapacityAndResumesItsPersistedInput() throws Exception {
    try (var server = start();
        var pool = Executors.newSingleThreadExecutor()) {
      String alice = server.token("alice", "demo");
      String bob = server.token("bob", "other");
      var engine = server.context().getBean(SearchEngine.class);
      var access = server.context().getBean(AuthorizationFilter.class).current("alice");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var entry =
          new SnapshotManifest.Entry(
              "Held.java",
              engine.contentStore().put("class Held {}".getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of(),
              "",
              "");
      var held =
          pool.submit(
              () -> {
                engine.ingest(
                    access,
                    "external-held",
                    new SnapshotManifest("demo", "public", "held", false, List.of(entry), Set.of()),
                    boundary -> {
                      if (boundary == GenerationBuilder.Boundary.BEFORE_COMMIT) {
                        entered.countDown();
                        try {
                          if (!release.await(30, TimeUnit.SECONDS))
                            throw new IOException("test timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IOException(interrupted);
                        }
                      }
                    },
                    () -> false);
                return null;
              });
      List<String> admitted = new ArrayList<>();
      try {
        assertTrue(entered.await(15, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) {
          String key = "queued-" + i;
          var response = server.request("POST", "/v1/ingestions", alice, body(key));
          assertEquals(202, response.statusCode(), response.body());
          admitted.add(id(key));
        }
        assertEquals("QUEUED", server.state(id("queued-8"), alice));
        var denied = server.request("DELETE", "/v1/ingestions/" + id("queued-8"), bob, null);
        assertEquals(403, denied.statusCode());
        assertEquals("QUEUED", server.state(id("queued-8"), alice));
        var full = server.request("POST", "/v1/ingestions", alice, body("rejected"));
        assertEquals(429, full.statusCode(), full.body());
        assertEquals("REGISTERED", server.state(id("rejected"), alice));

        var cancelled = server.request("DELETE", "/v1/ingestions/" + id("queued-8"), alice, null);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", server.state(id("queued-8"), alice));
        var retry =
            server.request("POST", "/v1/ingestions/" + id("rejected") + "/resume", alice, null);
        assertEquals(202, retry.statusCode(), retry.body());
        assertEquals("QUEUED", server.state(id("rejected"), alice));
        admitted.add(id("rejected"));
        var fullAgain =
            server.request("POST", "/v1/ingestions/" + id("queued-8") + "/resume", alice, null);
        assertEquals(429, fullAgain.statusCode(), fullAgain.body());
        assertEquals("CANCELLED", server.state(id("queued-8"), alice));

        // Resume must use the already registered immutable input, not this vanished checkout.
        Files.delete(dir.resolve("sources/demo/public/src/Retry.java"));
        Files.delete(dir.resolve("sources/demo/public/src"));
        var cancelNinth = server.request("DELETE", "/v1/ingestions/" + id("queued-9"), alice, null);
        assertEquals(200, cancelNinth.statusCode(), cancelNinth.body());
        admitted.remove(id("queued-9"));
        var resumed =
            server.request("POST", "/v1/ingestions/" + id("queued-8") + "/resume", alice, null);
        assertEquals(202, resumed.statusCode(), resumed.body());
        assertEquals("QUEUED", server.state(id("queued-8"), alice));
        var metrics = server.request("GET", "/v1/metrics", alice, null);
        assertEquals(200, metrics.statusCode());
        var values = JsonMapper.builder().build().readTree(metrics.body());
        assertEquals(8, values.get("queuedJobs").asInt());
        assertEquals(0, values.get("jobFailures").asInt());
      } finally {
        release.countDown();
      }
      held.get(30, TimeUnit.SECONDS);
      eventually(() -> admitted.stream().allMatch(engine::completed));
      assertEquals("CANCELLED", server.state(id("queued-9"), alice));
      assertEquals("COMPLETE", server.state(id("queued-8"), alice));
      var afterCommit = server.request("DELETE", "/v1/ingestions/" + id("queued-8"), alice, null);
      assertEquals(200, afterCommit.statusCode());
      assertEquals(
          "COMPLETE",
          JsonMapper.builder().build().readTree(afterCommit.body()).get("state").asString());
      var result =
          server.request(
              "POST",
              "/v1/search",
              alice,
              "{\"text\":\"persistedOriginalSnapshot\",\"pageSize\":10}");
      assertEquals(200, result.statusCode(), result.body());
      assertEquals(
          1, JsonMapper.builder().build().readTree(result.body()).get("totalHits").asInt());
      var metrics = server.request("GET", "/v1/metrics", alice, null);
      assertEquals(
          0, JsonMapper.builder().build().readTree(metrics.body()).get("jobFailures").asInt());
    }
  }

  @Test
  void ambiguousJsonIsRejectedBeforeItCanAcquireSourcesOrRegisterAJob() throws Exception {
    try (var server = start()) {
      String token = server.token("alice", "demo");
      var engine = server.context().getBean(SearchEngine.class);
      var access = server.context().getBean(AuthorizationFilter.class).current("alice");
      String valid = body("strict-http");
      for (String invalid :
          List.of(
              valid.replace(
                  "\"repository\":\"public\"",
                  "\"repository\":\"private\",\"repository\":\"public\""),
              valid.replace("\"revision\":\"v1\"", "\"revision\":42"),
              valid.replace("}", ",\"complete\":\"true\"}"),
              valid.replace("}", ",\"archive\":1}"),
              valid.replace("}", ",\"compelete\":true}"),
              valid.replace("}", ",\"deletions\":[\"../private\"]}"),
              valid + " {}")) {
        var response = server.request("POST", "/v1/ingestions", token, invalid);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("{\"error\":\"INVALID_REQUEST\"}", response.body());
        assertThrows(IOException.class, () -> engine.job(access, id("strict-http")));
      }
      var accepted = server.request("POST", "/v1/ingestions", token, valid);
      assertEquals(202, accepted.statusCode(), accepted.body());
      eventually(() -> engine.completed(id("strict-http")));
      String search = "{\"text\":\"persistedOriginalSnapshot\",\"pageSize\":10}";
      for (String invalid :
          List.of(
              search.replace("10", "1.7"),
              search.replace("10", "\"10\""),
              search.replace("10", "null"),
              search.replace("\"pageSize\":10", "\"pageSize\":10,\"pageSize\":1"),
              search.replace(
                  "}", ",\"filters\":{\"repository\":\"private\",\"repository\":\"public\"}}"),
              search + " null")) {
        var response = server.request("POST", "/v1/search", token, invalid);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("{\"error\":\"INVALID_REQUEST\"}", response.body());
      }
      var successful = server.request("POST", "/v1/search", token, search);
      assertEquals(200, successful.statusCode(), successful.body());
      assertEquals(
          1, JsonMapper.builder().build().readTree(successful.body()).get("totalHits").asInt());
      var suggest = server.request("POST", "/v1/suggest", token, "{\"prefix\":true}");
      assertEquals(400, suggest.statusCode());
      var conflict =
          server.request("POST", "/v1/ingestions", token, valid.replace("\"v1\"", "\"v2\""));
      assertEquals(409, conflict.statusCode(), conflict.body());
      assertEquals("COMPLETE", server.state(id("strict-http"), token));
    }
  }

  private static void awaitState(Server server, String id, String token, String expected)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    String observed;
    do {
      observed = server.state(id, token);
      if (expected.equals(observed)) return;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    assertEquals(expected, observed, "HTTP task state deadline");
  }

  @Test
  void failedRegisteredJobResumesAfterMissingContentIsRestoredWithoutSourceAcquisition()
      throws Exception {
    try (var server = start()) {
      String alice = server.token("alice", "demo");
      String bob = server.token("bob", "other");
      var engine = server.context().getBean(SearchEngine.class);
      var access = server.context().getBean(AuthorizationFilter.class).current("alice");
      byte[] missingContent =
          "class Restored { void durableInputRepair() {} }".getBytes(StandardCharsets.UTF_8);
      String digest = Digests.sha256(missingContent);
      var entry =
          new SnapshotManifest.Entry(
              "Restored.java", digest, "java", "source", List.of("durableInputRepair"), "", "");
      var registered =
          engine.register(
              access,
              "repair",
              new SnapshotManifest("demo", "public", "v1", false, List.of(entry), Set.of()));
      String route = "/v1/ingestions/" + registered.id();
      assertEquals(403, server.request("POST", route + "/resume", bob, null).statusCode());
      assertEquals(403, server.request("GET", route, bob, null).statusCode());
      assertEquals(403, server.request("DELETE", route, bob, null).statusCode());
      assertEquals(202, server.request("POST", route + "/resume", alice, null).statusCode());
      awaitState(server, registered.id(), alice, "FAILED");
      assertEquals(
          1,
          JsonMapper.builder()
              .build()
              .readTree(server.request("GET", "/v1/metrics", alice, null).body())
              .get("jobFailures")
              .asInt());
      assertFalse(engine.completed(registered.id()));
      assertEquals(digest, engine.contentStore().put(missingContent));
      assertEquals(202, server.request("POST", route + "/resume", alice, null).statusCode());
      eventually(() -> engine.completed(registered.id()));
      assertEquals("COMPLETE", server.state(registered.id(), alice));
      assertEquals(
          1,
          engine
              .search("alice", new SearchEngine.Request("durableInputRepair", 10, null, Map.of()))
              .totalHits());
      assertEquals(
          1,
          JsonMapper.builder()
              .build()
              .readTree(server.request("GET", "/v1/metrics", alice, null).body())
              .get("jobFailures")
              .asInt());
      assertEquals(
          "COMPLETE",
          JsonMapper.builder()
              .build()
              .readTree(server.request("DELETE", route, alice, null).body())
              .get("state")
              .asString());
    }
  }

  @Test
  void revokedQueuedAccessCannotPublishAndExplicitResumeUsesTheNewGrant() throws Exception {
    try (var server = start();
        var pool = Executors.newSingleThreadExecutor()) {
      String alice = server.token("alice", "demo");
      var engine = server.context().getBean(SearchEngine.class);
      var auth = server.context().getBean(AuthorizationFilter.class);
      var access = auth.current("alice");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var entry =
          new SnapshotManifest.Entry(
              "Held.java",
              engine.contentStore().put("class Held {}".getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of(),
              "",
              "");
      var held =
          pool.submit(
              () -> {
                engine.ingest(
                    access,
                    "held-revocation",
                    new SnapshotManifest("demo", "public", "held", false, List.of(entry), Set.of()),
                    boundary -> {
                      if (boundary == GenerationBuilder.Boundary.BEFORE_COMMIT) {
                        entered.countDown();
                        try {
                          if (!release.await(30, TimeUnit.SECONDS))
                            throw new IOException("test timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IOException(interrupted);
                        }
                      }
                    },
                    () -> false);
                return null;
              });
      try {
        assertTrue(entered.await(15, TimeUnit.SECONDS));
        assertEquals(
            202, server.request("POST", "/v1/ingestions", alice, body("revoked")).statusCode());
        auth.update("alice", "demo", Set.of(), true);
      } finally {
        release.countDown();
      }
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class, () -> held.get(30, TimeUnit.SECONDS));
      assertInstanceOf(SecurityException.class, failure.getCause());
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      long failures;
      do {
        var metrics = server.request("GET", "/v1/metrics", alice, null);
        assertEquals(200, metrics.statusCode(), metrics.body());
        failures =
            JsonMapper.builder().build().readTree(metrics.body()).get("jobFailures").asLong();
        if (failures == 1L) break;
        Thread.sleep(10);
      } while (System.nanoTime() < deadline);
      assertEquals(1L, failures);
      assertEquals(
          403, server.request("GET", "/v1/ingestions/" + id("revoked"), alice, null).statusCode());
      assertNull(engine.generations("demo").currentId());
      auth.update("alice", "demo", Set.of("public"), true);
      assertEquals("FAILED", server.state(id("revoked"), alice));
      assertEquals(
          202,
          server
              .request("POST", "/v1/ingestions/" + id("revoked") + "/resume", alice, null)
              .statusCode());
      eventually(() -> engine.completed(id("revoked")));
      assertEquals(
          1,
          engine
              .search(
                  "alice",
                  new SearchEngine.Request("persistedOriginalSnapshot", 10, null, Map.of()))
              .totalHits());
    }
  }

  @Test
  void closedBackgroundAdmissionReturns503AndKeepsDurableRegistrationResumable() throws Exception {
    try (var server = start()) {
      String token = server.token("alice", "demo");
      server.context().getBean(SearchController.class).close();
      var response = server.request("POST", "/v1/ingestions", token, body("after-close"));
      assertEquals(503, response.statusCode(), response.body());
      assertEquals("{\"error\":\"SERVICE_STOPPING\"}", response.body());
      assertEquals("REGISTERED", server.state(id("after-close"), token));
      var retry =
          server.request("POST", "/v1/ingestions/" + id("after-close") + "/resume", token, null);
      assertEquals(503, retry.statusCode(), retry.body());
      assertFalse(server.context().getBean(SearchEngine.class).completed(id("after-close")));
      assertEquals(
          0,
          JsonMapper.builder()
              .build()
              .readTree(server.request("GET", "/v1/metrics", token, null).body())
              .get("jobFailures")
              .asInt());
    }
  }
}
