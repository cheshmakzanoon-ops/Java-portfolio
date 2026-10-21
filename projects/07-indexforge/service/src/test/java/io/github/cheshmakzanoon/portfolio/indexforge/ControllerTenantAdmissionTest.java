package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

class ControllerTenantAdmissionTest {
  @TempDir Path root;

  @FunctionalInterface
  private interface Request {
    void invoke(SearchController controller, Jwt jwt) throws IOException;
  }

  @Test
  void searchRetainsTheJwtTenantWhenMappingChangesImmediatelyAfterContextValidation()
      throws Exception {
    assertAdmissionReassignment(
        (controller, jwt) ->
            controller.search(jwt, new SearchEngine.Request("foreign", 10, null, Map.of())));
  }

  @Test
  void suggestionsRetainTheJwtTenantWhenMappingChangesImmediatelyAfterContextValidation()
      throws Exception {
    assertAdmissionReassignment(
        (controller, jwt) ->
            controller.suggest(jwt, new SearchController.SuggestRequest("foreign")));
  }

  private void assertAdmissionReassignment(Request request) throws Exception {
    var auth = new AuthorizationFilter();
    auth.update("reader", "a", Set.of("public"), false);
    var foreignOwner = auth.update("owner-b", "b", Set.of("public"), true);
    try (var engine = new SearchEngine(root.resolve("data"), auth);
        var controller =
            new SearchController(
                engine,
                auth,
                new MockEnvironment()
                    .withProperty("indexforge.sources", root.resolve("sources").toString()))) {
      var entry =
          new SnapshotManifest.Entry(
              "Foreign.txt",
              engine
                  .contentStore()
                  .put("foreign private material".getBytes(StandardCharsets.UTF_8)),
              "text",
              "source",
              List.of("foreignsymbol"),
              "",
              "");
      engine.ingest(
          foreignOwner,
          "seed",
          new SnapshotManifest("b", "public", "v1", false, List.of(entry), Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var moved = new AtomicBoolean();
      // This adapter-level fixture represents an already verified JWT. Reading its tenant
      // schedules the policy update after context() captured A, before the engine is called.
      Jwt token =
          new Jwt(
              "verified-test-context",
              Instant.now(),
              Instant.now().plusSeconds(60),
              Map.of("alg", "RS256"),
              Map.of("sub", "reader", "tenant", "a", "scope", "search")) {
            @Override
            public String getClaimAsString(String claim) {
              String value = (String) getClaims().get(claim);
              if (claim.equals("tenant") && moved.compareAndSet(false, true))
                auth.update("reader", "b", Set.of("public"), false);
              return value;
            }
          };
      assertThrows(SecurityException.class, () -> request.invoke(controller, token));
      assertTrue(moved.get(), "the test must cross the controller admission boundary");
      assertEquals(
          1,
          engine
              .search("reader", new SearchEngine.Request("foreign", 10, null, Map.of()))
              .totalHits(),
          "foreign data is present and reachable with a newly admitted tenant B context");
      assertEquals(List.of("foreignsymbol"), engine.suggest("reader", "foreign"));
    }
  }
}
