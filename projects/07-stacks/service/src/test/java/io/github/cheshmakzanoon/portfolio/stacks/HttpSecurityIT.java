package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.*;

class HttpSecurityIT {
  @TempDir Path dir;

  HttpResponse<String> request(
      HttpClient client, String base, String path, String token, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realHttpQueueRejectsEleventhJobAndKeepsRejectedRegistrationHonest() throws Exception {
    Files.createDirectories(dir.resolve("sources/demo/public/project"));
    Files.writeString(
        dir.resolve("sources/demo/public/project/Queue.java"),
        "class Queue { void queueCapacitySymbol() {} }");
    SpringApplication app = new SpringApplication(StacksApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address",
            "127.0.0.1",
            "server.port",
            "0",
            "spring.profiles.active",
            "dev",
            "stacks.issuer-port",
            "0",
            "stacks.data",
            dir.resolve("data").toString(),
            "stacks.sources",
            dir.resolve("sources").toString(),
            "spring.main.banner-mode",
            "off"));
    try (var context = app.run();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      var client = HttpClient.newHttpClient();
      var issuer = context.getBean(LocalOidcIssuer.class);
      String token = issuer.token("alice", "demo", Instant.now().plusSeconds(300), "stacks");
      var engine = context.getBean(SearchEngine.class);
      var access = context.getBean(AuthorizationFilter.class).current("alice");
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var entry =
          new SnapshotManifest.Entry(
              "Held.java",
              engine
                  .contentStore()
                  .put(
                      "class Held { void heldBuildSymbol() {} }"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of("heldBuildSymbol"),
              "",
              "");
      var held =
          pool.submit(
              () -> {
                engine.ingest(
                    access,
                    "held",
                    new SnapshotManifest("demo", "public", "held", false, List.of(entry), Set.of()),
                    boundary -> {
                      if (boundary == GenerationBuilder.Boundary.BEFORE_COMMIT) {
                        entered.countDown();
                        try {
                          if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IOException("held build timeout");
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IOException(interrupted);
                        }
                      }
                    },
                    () -> false);
                return null;
              });
      List<String> accepted = new ArrayList<>();
      try {
        assertTrue(entered.await(15, java.util.concurrent.TimeUnit.SECONDS));
        for (int i = 0; i < 11; i++) {
          String key = "capacity-" + i;
          String body =
              "{\"repository\":\"public\",\"revision\":\"queue\",\"idempotencyKey\":\""
                  + key
                  + "\",\"path\":\"project\"}";
          var response = request(client, base, "/v1/ingestions", token, body);
          assertEquals(i < 10 ? 202 : 429, response.statusCode(), response.body());
          String id = Digests.sha256("demo\npublic\n" + key);
          if (i < 10) accepted.add(id);
          else {
            var status = request(client, base, "/v1/ingestions/" + id, token, null);
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("REGISTERED"), status.body());
            assertFalse(status.body().contains("QUEUED"));
          }
        }
      } finally {
        release.countDown();
      }
      held.get(30, java.util.concurrent.TimeUnit.SECONDS);
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (accepted.stream().anyMatch(id -> !engine.completed(id))
          && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(accepted.stream().allMatch(engine::completed));
      assertEquals(
          1,
          engine
              .search("alice", new SearchEngine.Request("queueCapacitySymbol", 10, null, Map.of()))
              .totalHits());
    }
  }

  @Test
  void realOidcJwksProtectedSearchIngestionAndClaimFailures() throws Exception {
    Files.createDirectories(dir.resolve("sources/demo/public/project"));
    Files.writeString(
        dir.resolve("sources/demo/public/project/Demo.java"),
        "class Demo { void findAtomicGeneration() {} }");
    SpringApplication app = new SpringApplication(StacksApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address",
            "127.0.0.1",
            "server.port",
            "0",
            "spring.profiles.active",
            "dev",
            "stacks.issuer-port",
            "0",
            "stacks.data",
            dir.resolve("data").toString(),
            "stacks.sources",
            dir.resolve("sources").toString(),
            "spring.main.banner-mode",
            "off"));
    try (ConfigurableApplicationContext context =
        app.run(
            "--stacks.ranking-mode=NO_COVERAGE",
            "--stacks.ranking-profile=BM25_NO_LENGTH")) {
      String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      HttpClient client = HttpClient.newHttpClient();
      LocalOidcIssuer issuer = context.getBean(LocalOidcIssuer.class);
      String token = issuer.token("alice", "demo", Instant.now().plusSeconds(300), "stacks");
      assertEquals(200, request(client, base, "/health", null, null).statusCode());
      assertEquals(
          401,
          request(client, base, "/v1/search", null, "{\"text\":\"generation\",\"pageSize\":10}")
              .statusCode());
      assertEquals(400, request(client, base, "/v1/ingestions", token, "{}").statusCode());
      String body =
          "{\"repository\":\"public\",\"revision\":\"v1\",\"idempotencyKey\":\"http1\",\"path\":\"project\",\"complete\":true}";
      var ingestion = request(client, base, "/v1/ingestions", token, body);
      assertEquals(202, ingestion.statusCode(), ingestion.body());

      // A browser-ambient cookie must not bypass CSRF checks, even with a valid bearer token.
      // Normal explicit bearer requests remain functional (as asserted above).
      var cookieRequest =
          HttpRequest.newBuilder(URI.create(base + "/v1/ingestions"))
              .timeout(Duration.ofSeconds(20))
              .header("Authorization", "Bearer " + token)
              .header("Cookie", "JSESSIONID=ambient")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      assertEquals(
          403, client.send(cookieRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
      Files.createDirectories(dir.resolve("sources/demo/denied"));
      Files.writeString(
          dir.resolve("sources/demo/denied/Private.java"), "unreadableOtherRepositorySecret");
      String escape =
          "{\"repository\":\"public\",\"revision\":\"v2\",\"idempotencyKey\":\"escape\",\"path\":\"../denied\"}";
      var escaped = request(client, base, "/v1/ingestions", token, escape);
      assertEquals(422, escaped.statusCode());
      assertFalse(escaped.body().contains("Private.java"));
      String deniedRepository =
          "{\"repository\":\"denied\",\"revision\":\"v2\",\"idempotencyKey\":\"denied\",\"path\":\".\"}";
      assertEquals(
          403, request(client, base, "/v1/ingestions", token, deniedRepository).statusCode());

      SearchEngine engine = context.getBean(SearchEngine.class);
      Files.createDirectories(dir.resolve("sources/other/public/project"));
      Files.writeString(
          dir.resolve("sources/other/public/project/Foreign.java"),
          "class Foreign { void foreignTenantSymbol() {} }");
      String bob = issuer.token("bob", "other", Instant.now().plusSeconds(300), "stacks");
      assertEquals(202, request(client, base, "/v1/ingestions", bob, body).statusCode());
      long foreignDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while ((engine.generations("other").currentId() == null
              || engine.generations("demo").currentId() == null)
          && System.nanoTime() < foreignDeadline) Thread.sleep(20);
      var foreign =
          request(
              client,
              base,
              "/v1/search",
              bob,
              "{\"text\":\"foreignTenantSymbol\",\"pageSize\":10}");
      assertEquals(200, foreign.statusCode());
      assertTrue(foreign.body().contains("Foreign.java"));
      var isolated =
          request(
              client,
              base,
              "/v1/search",
              token,
              "{\"text\":\"foreignTenantSymbol\",\"pageSize\":10}");
      assertEquals(200, isolated.statusCode());
      assertFalse(isolated.body().contains("Foreign.java"));
      assertTrue(isolated.body().contains("\"totalHits\":0"));
      Files.createSymbolicLink(
          dir.resolve("sources/demo/linked"), dir.resolve("sources/other/public"));
      var authDirectory = context.getBean(AuthorizationFilter.class);
      authDirectory.update("alice", "demo", Set.of("public", "linked"), true);
      String linked =
          "{\"repository\":\"linked\",\"revision\":\"v2\",\"idempotencyKey\":\"link\",\"path\":\"project\"}";
      assertEquals(422, request(client, base, "/v1/ingestions", token, linked).statusCode());
      authDirectory.update("alice", "demo", Set.of("public"), true);
      String tenantEscape =
          "{\"repository\":\"public\",\"revision\":\"v2\",\"idempotencyKey\":\"tenant-escape\",\"path\":\"../../other/public\"}";
      assertEquals(422, request(client, base, "/v1/ingestions", token, tenantEscape).statusCode());

      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (engine.generations("demo").currentId() == null && System.nanoTime() < deadline)
        Thread.sleep(20);
      var response =
          request(
              client,
              base,
              "/v1/search",
              token,
              "{\"text\":\"findAtomicGeneration\",\"pageSize\":10}");
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("Demo.java"));
      assertTrue(
          response
              .body()
              .contains(
                  QueryPlanner.VERSION
                      + "-BM25_NO_LENGTH/"
                      + RankingProfile.BM25_NO_LENGTH.parametersIdentity()
                      + "/CLASSIC/shared-v1/"),
          response.body());
      assertEquals(
          200, request(client, base, "/v1/suggest", token, "{\"prefix\":\"find\"}").statusCode());
      assertEquals(
          400,
          request(client, base, "/v1/search", token, "{\"text\":\"x\",\"pageSize\":101}")
              .statusCode());
      assertEquals(413, request(client, base, "/v1/search", token, "x".repeat(65537)).statusCode());
      assertEquals(
          403,
          request(
                  client,
                  base,
                  "/v1/search",
                  issuer.token("alice", "other", Instant.now().plusSeconds(300), "stacks"),
                  "{\"text\":\"find\",\"pageSize\":10}")
              .statusCode());
      assertEquals(
          401,
          request(
                  client,
                  base,
                  "/v1/search",
                  issuer.token("alice", "demo", Instant.now().minusSeconds(5), "stacks"),
                  "{}")
              .statusCode());
      assertEquals(
          401,
          request(
                  client,
                  base,
                  "/v1/search",
                  issuer.token("alice", "demo", Instant.now().plusSeconds(300), "wrong"),
                  "{}")
              .statusCode());
      String fixed =
          ",\"aud\":[\"stacks\"],\"exp\":"
              + (Instant.now().getEpochSecond() + 300)
              + ",\"tenant\":\"demo\",\"scope\":\"search ingest\"}";
      for (String badSubject : List.of("42", "[]", "null", "\"\"", "\"   \"")) {
        String signed =
            issuer.sign("{\"iss\":\"" + issuer.issuer() + "\",\"sub\":" + badSubject + fixed);
        assertEquals(
            401,
            request(client, base, "/v1/search", signed, "{\"text\":\"find\",\"pageSize\":10}")
                .statusCode(),
            "original signed subject type");
      }
      JwtDecoder decoder = context.getBean(JwtDecoder.class);
      for (String claims :
          List.of(
              "{\"iss\":\""
                  + issuer.issuer()
                  + "\",\"sub\":\"alice\",\"aud\":[\"stacks\"],\"tenant\":\"demo\",\"scope\":\"search\"}",
              "{\"iss\":\""
                  + issuer.issuer()
                  + "\",\"sub\":\"alice\",\"aud\":[\"stacks\"],\"exp\":"
                  + (Instant.now().getEpochSecond() + 300)
                  + ",\"tenant\":7,\"scope\":\"search\"}",
              "{\"iss\":\"wrong\",\"sub\":\"alice\",\"aud\":[\"stacks\"],\"exp\":"
                  + (Instant.now().getEpochSecond() + 300)
                  + ",\"tenant\":\"demo\",\"scope\":\"search\"}"))
        assertThrows(JwtException.class, () -> decoder.decode(issuer.sign(claims)));
      try (LocalOidcIssuer attacker = new LocalOidcIssuer(0)) {
        assertThrows(
            JwtException.class,
            () ->
                decoder.decode(
                    attacker.token("alice", "demo", Instant.now().plusSeconds(300), "stacks")));
      }
      var auth = context.getBean(AuthorizationFilter.class);
      auth.update("alice", "demo", Set.of(), false);
      var denied =
          request(
              client,
              base,
              "/v1/search",
              token,
              "{\"text\":\"findAtomicGeneration\",\"pageSize\":10}");
      assertEquals(200, denied.statusCode());
      assertFalse(denied.body().contains("Demo.java"));
      assertTrue(denied.body().contains("\"totalHits\":0"));
    }
  }
}
