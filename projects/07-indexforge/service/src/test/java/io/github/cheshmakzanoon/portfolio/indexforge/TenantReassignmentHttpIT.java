package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.json.JsonMapper;

/** Real signatures, HTTP routing, index readers and permission changes share one request. */
class TenantReassignmentHttpIT {
  @TempDir Path root;

  @Test
  void signedSearchCannotFollowItsPrincipalIntoAnotherTenantDuringARefreshRetry() throws Exception {
    assertReassignment("/v1/search", "{\"text\":\"shared\",\"pageSize\":10}");
  }

  @Test
  void signedSuggestionsCannotFollowTheirPrincipalIntoAnotherTenantDuringARefreshRetry()
      throws Exception {
    assertReassignment("/v1/suggest", "{\"prefix\":\"shared\"}");
  }

  private void assertReassignment(String route, String body) throws Exception {
    var app = new SpringApplication(IndexForgeApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address", "127.0.0.1",
            "server.port", "0",
            "spring.profiles.active", "dev",
            "indexforge.issuer-port", "0",
            "indexforge.data", root.resolve("data").toString(),
            "indexforge.sources", root.resolve("sources").toString(),
            "spring.main.banner-mode", "off"));
    try (var context = app.run();
        var client = HttpClient.newHttpClient()) {
      String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      var engine = context.getBean(SearchEngine.class);
      var auth = context.getBean(AuthorizationFilter.class);
      var issuer = context.getBean(LocalOidcIssuer.class);
      seed(engine, auth.current("alice"), "sharedoriginal");
      seed(engine, auth.current("bob"), "sharedforeign");
      String originalToken =
          issuer.token("alice", "demo", Instant.now().plusSeconds(300), "indexforge");
      var originalRequest = request(base, route, originalToken, body);
      var warmed = client.send(originalRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, warmed.statusCode(), warmed.body());
      assertTrue(warmed.body().contains("sharedoriginal"), warmed.body());
      assertFalse(warmed.body().contains("sharedforeign"), warmed.body());

      CompletableFuture<HttpResponse<String>> pending;
      var publisher = engine.generations("demo");
      synchronized (publisher) {
        pending = client.sendAsync(originalRequest, HttpResponse.BodyHandlers.ofString());
        // The real request has captured its original permissions and is waiting to acquire
        // that tenant's generation. No sleeps or request-start guesses select the race.
        awaitReaderBlockedOn(publisher, pending);
        auth.update("alice", "other", Set.of("public"), false);
      }
      var rejected = pending.get(20, TimeUnit.SECONDS);
      assertEquals(403, rejected.statusCode(), rejected.body());
      assertEquals(
          Map.of("error", "ACCESS_DENIED"),
          JsonMapper.builder().build().readValue(rejected.body(), Map.class));
      assertFalse(rejected.body().contains("sharedforeign"));
      assertFalse(rejected.body().contains("sharedoriginal"));

      var staleToken = client.send(originalRequest, HttpResponse.BodyHandlers.ofString());
      assertEquals(403, staleToken.statusCode(), staleToken.body());
      String reassignedToken =
          issuer.token("alice", "other", Instant.now().plusSeconds(300), "indexforge");
      var current =
          client.send(
              request(base, route, reassignedToken, body), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, current.statusCode(), current.body());
      assertTrue(current.body().contains("sharedforeign"), current.body());
      assertFalse(current.body().contains("sharedoriginal"), current.body());
    }
  }

  private static HttpRequest request(String base, String route, String token, String body) {
    return HttpRequest.newBuilder(URI.create(base + route))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  private static void seed(SearchEngine engine, AuthorizationFilter.Access access, String symbol)
      throws IOException {
    var entry =
        new SnapshotManifest.Entry(
            symbol + ".txt",
            engine.contentStore().put(("shared " + symbol).getBytes(StandardCharsets.UTF_8)),
            "text",
            "source",
            List.of(symbol),
            "",
            "");
    engine.ingest(
        access,
        "seed",
        new SnapshotManifest(access.tenant(), "public", "v1", false, List.of(entry), Set.of()),
        GenerationBuilder.Fault.none(),
        () -> false);
  }

  private static void awaitReaderBlockedOn(
      GenerationPublisher publisher, CompletableFuture<?> request) throws InterruptedException {
    var threads = ManagementFactory.getThreadMXBean();
    long owner = Thread.currentThread().threadId();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    do {
      for (var thread : threads.dumpAllThreads(false, false)) {
        var lock = thread.getLockInfo();
        if (thread.getThreadState() == Thread.State.BLOCKED
            && thread.getLockOwnerId() == owner
            && lock != null
            && lock.getIdentityHashCode() == System.identityHashCode(publisher)
            && lock.getClassName().equals(GenerationPublisher.class.getName())
            && Arrays.stream(thread.getStackTrace())
                .anyMatch(
                    frame ->
                        frame.getClassName().equals(GenerationPublisher.class.getName())
                            && frame.getMethodName().equals("acquire"))) return;
      }
      assertFalse(request.isDone(), "request must reach original-tenant reader acquisition");
      Thread.sleep(1);
    } while (System.nanoTime() < deadline);
    fail("HTTP request did not reach the controlled generation-acquisition boundary");
  }
}
