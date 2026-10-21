package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

class TenantMetricsTest {
  @TempDir Path root;

  private Jwt token(String subject, String tenant) {
    return Jwt.withTokenValue("verified-test-context")
        .header("alg", "RS256")
        .subject(subject)
        .claim("tenant", tenant)
        .claim("scope", "search ingest")
        .build();
  }

  @Test
  void administratorsSeeOnlyTheirTenantsRequestsFailuresAndQueuedWork() throws Exception {
    var auth = new AuthorizationFilter();
    var a = auth.update("admin-a", "a", Set.of("r"), true);
    var b = auth.update("admin-b", "b", Set.of("r"), true);
    auth.update("reader-a", "a", Set.of("r"), false);
    try (var engine = new SearchEngine(root.resolve("data"), auth);
        var controller =
            new SearchController(
                engine,
                auth,
                new MockEnvironment()
                    .withProperty("stacks.sources", root.resolve("sources").toString()))) {
      for (var access : List.of(a, b)) {
        String hash =
            engine.contentStore().put("pulse".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var entry = new SnapshotManifest.Entry("A.txt", hash, "text", "source", List.of(), "", "");
        engine.ingest(
            access,
            "seed",
            new SnapshotManifest(access.tenant(), "r", "v1", false, List.of(entry), Set.of()),
            GenerationBuilder.Fault.none(),
            () -> false);
      }
      var query = new SearchEngine.Request("pulse", 10, null, Map.of());
      assertEquals(1, controller.search(token("admin-a", "a"), query).totalHits());
      assertEquals(1, controller.search(token("admin-b", "b"), query).totalHits());
      assertEquals(1, controller.search(token("admin-b", "b"), query).totalHits());
      assertEquals(1L, controller.metrics(token("admin-a", "a")).get("searches"));
      assertEquals(2L, controller.metrics(token("admin-b", "b")).get("searches"));
      var missing =
          new SnapshotManifest.Entry(
              "Missing.txt", "0".repeat(64), "text", "source", List.of(), "", "");
      var job =
          engine.register(
              b,
              "missing-content",
              new SnapshotManifest("b", "r", "v2", false, List.of(missing), Set.of()));
      controller.resume(token("admin-b", "b"), job.id());
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while ((!controller.job(token("admin-b", "b"), job.id()).get("state").equals("FAILED")
              || controller.metrics(token("admin-b", "b")).get("jobFailures") != 1L)
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals("FAILED", controller.job(token("admin-b", "b"), job.id()).get("state"));
      assertEquals(
          Map.of("searches", 1L, "jobFailures", 0L, "queuedJobs", 0L),
          controller.metrics(token("admin-a", "a")));
      assertEquals(
          Map.of("searches", 2L, "jobFailures", 1L, "queuedJobs", 0L),
          controller.metrics(token("admin-b", "b")));
      assertThrows(SecurityException.class, () -> controller.metrics(token("reader-a", "a")));
    }
  }
}
