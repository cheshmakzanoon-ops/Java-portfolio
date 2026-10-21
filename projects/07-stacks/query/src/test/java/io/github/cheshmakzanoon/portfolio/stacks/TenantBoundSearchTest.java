package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantBoundSearchTest {
  @TempDir Path root;

  private static final SearchEngine.Request PAGE =
      new SearchEngine.Request("shared", 1, null, Map.of());

  /** Cursor signing gives a real boundary immediately before the final permission recheck. */
  private static final class ReassignmentClock extends Clock {
    private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    private final AtomicReference<Runnable> next = new AtomicReference<>();

    void atNextCursor(Runnable update) {
      assertTrue(next.compareAndSet(null, update), "one pending authorization event");
    }

    void assertObserved() {
      assertNull(next.get(), "request must reach cursor signing before changing permissions");
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      Runnable update = next.getAndSet(null);
      if (update != null) update.run();
      return now;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AuthorizationFilter auth = new AuthorizationFilter();
    final ReassignmentClock clock = new ReassignmentClock();
    final AuthorizationFilter.Access admitted;
    final SearchEngine engine;

    Fixture(Path root) throws IOException {
      var first = auth.update("owner-a", "a", Set.of("public", "private"), true);
      var second = auth.update("owner-b", "b", Set.of("public"), true);
      admitted = auth.update("reader", "a", Set.of("public"), false);
      engine =
          new SearchEngine(
              root,
              auth,
              QueryPlanner.Mode.BASELINE,
              RankingProfile.CURRENT,
              new RankingConfiguration(RankingStrategy.CLASSIC, null),
              clock);
      try {
        seed(first, "public", "publicsymbol");
        seed(first, "private", "privatesymbol");
        seed(second, "public", "foreignsymbol");
      } catch (IOException | RuntimeException | Error failure) {
        try {
          engine.close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
        throw failure;
      }
    }

    private void seed(AuthorizationFilter.Access owner, String repository, String symbol)
        throws IOException {
      var entries = new ArrayList<SnapshotManifest.Entry>();
      for (int i = 0; i < 2; i++)
        entries.add(
            new SnapshotManifest.Entry(
                symbol + i + ".txt",
                engine
                    .contentStore()
                    .put(("shared " + symbol + i).getBytes(StandardCharsets.UTF_8)),
                "text",
                "source",
                List.of(symbol + i),
                "",
                ""));
      engine.ingest(
          owner,
          repository,
          new SnapshotManifest(owner.tenant(), repository, "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
    }

    void moveToForeignTenant() {
      auth.update("reader", "b", Set.of("public"), false);
    }

    @Override
    public void close() throws IOException {
      engine.close();
    }
  }

  @Test
  void reassignmentBeforeEngineAdmissionCannotReuseTheOldAuthenticatedTenant() throws Exception {
    try (var fixture = new Fixture(root)) {
      var original = fixture.engine.search(fixture.admitted, PAGE);
      assertEquals(2, original.totalHits());
      assertNotNull(original.cursor());
      fixture.moveToForeignTenant();
      assertThrows(SecurityException.class, () -> fixture.engine.search(fixture.admitted, PAGE));
      assertThrows(
          SecurityException.class, () -> fixture.engine.suggest(fixture.admitted, "foreign"));
      assertThrows(
          SecurityException.class,
          () ->
              fixture.engine.search(
                  fixture.admitted,
                  new SearchEngine.Request("shared", 1, original.cursor(), Map.of())));
      assertTrue(
          fixture.engine.search("reader", PAGE).results().getFirst().key().startsWith("b/"),
          "a new trusted principal-based call captures its current tenant");
    }
  }

  @Test
  void currentSameTenantGrantsReplaceCapturedGrantsForSearchAndSuggestions() throws Exception {
    try (var fixture = new Fixture(root)) {
      assertEquals(2, fixture.engine.search(fixture.admitted, PAGE).totalHits());
      assertEquals(
          List.of("publicsymbol0", "publicsymbol1"),
          fixture.engine.suggest(fixture.admitted, "public"));
      fixture.auth.update("reader", "a", Set.of(), false);
      var revoked = fixture.engine.search(fixture.admitted, PAGE);
      assertEquals(0, revoked.totalHits());
      assertTrue(revoked.results().isEmpty());
      assertTrue(revoked.facets().isEmpty());
      assertNull(revoked.cursor());
      assertEquals(List.of(), fixture.engine.suggest(fixture.admitted, "public"));
      fixture.auth.update("reader", "a", Set.of("private"), false);
      var refreshed = fixture.engine.search(fixture.admitted, PAGE, QueryPlanner.Mode.BASELINE);
      assertEquals(Map.of("private", 2), refreshed.facets());
      assertEquals("private", refreshed.results().getFirst().repository());
      assertEquals(
          List.of("privatesymbol0", "privatesymbol1"),
          fixture.engine.suggest(fixture.admitted, "private"));
      assertEquals(List.of(), fixture.engine.suggest(fixture.admitted, "public"));
    }
  }

  @Test
  void suppliedContextCannotGrantRepositoriesOrAdministratorPrivileges() throws Exception {
    try (var fixture = new Fixture(root)) {
      var untrustedGrants =
          new AuthorizationFilter.Access("reader", "a", Set.of("private"), Long.MAX_VALUE, true);
      var response = fixture.engine.search(untrustedGrants, PAGE);
      assertEquals(Map.of("public", 2), response.facets());
      assertEquals("public", response.results().getFirst().repository());
      assertEquals(List.of(), fixture.engine.suggest(untrustedGrants, "private"));
      var wrongTenant = new AuthorizationFilter.Access("reader", "b", Set.of("public"), 0, false);
      assertThrows(SecurityException.class, () -> fixture.engine.search(wrongTenant, PAGE));
      assertThrows(SecurityException.class, () -> fixture.engine.suggest(wrongTenant, "foreign"));
    }
  }

  @Test
  void freshResponseCannotRetryInAnotherTenantAfterItsFinalPermissionCheckFails() throws Exception {
    try (var fixture = new Fixture(root)) {
      fixture.clock.atNextCursor(fixture::moveToForeignTenant);
      assertThrows(SecurityException.class, () -> fixture.engine.search(fixture.admitted, PAGE));
      fixture.clock.assertObserved();
      assertEquals(0, fixture.engine.cacheHits());
      assertEquals("b", fixture.auth.current("reader").tenant());
    }
  }

  @Test
  void cachedResponseCannotRetryInAnotherTenantAfterItsFinalPermissionCheckFails()
      throws Exception {
    try (var fixture = new Fixture(root)) {
      var first = fixture.engine.search(fixture.admitted, PAGE);
      assertNotNull(first.cursor());
      long hits = fixture.engine.cacheHits();
      fixture.clock.atNextCursor(fixture::moveToForeignTenant);
      assertThrows(SecurityException.class, () -> fixture.engine.search(fixture.admitted, PAGE));
      fixture.clock.assertObserved();
      assertEquals(hits, fixture.engine.cacheHits(), "discarded cache content is not served");
    }
  }

  @Test
  void sameTenantPermissionChangesCanRetryWithoutServingTheSupersededCachedPage() throws Exception {
    try (var fixture = new Fixture(root)) {
      fixture.engine.search(fixture.admitted, PAGE);
      fixture.clock.atNextCursor(
          () -> fixture.auth.update("reader", "a", Set.of("private"), false));
      var response = fixture.engine.search(fixture.admitted, PAGE);
      fixture.clock.assertObserved();
      assertEquals(Map.of("private", 2), response.facets());
      assertEquals("private", response.results().getFirst().repository());
      assertTrue(response.results().getFirst().key().startsWith("a/"));
      assertEquals(0, fixture.engine.cacheHits());
    }
  }

  @Test
  void principalBasedCallsKeepTheirAdmissionTenantForTheDurationOfOneCall() throws Exception {
    try (var fixture = new Fixture(root)) {
      fixture.clock.atNextCursor(fixture::moveToForeignTenant);
      assertThrows(SecurityException.class, () -> fixture.engine.search("reader", PAGE));
      fixture.clock.assertObserved();
      var next = fixture.engine.search("reader", PAGE);
      assertEquals(2, next.totalHits());
      assertTrue(next.results().getFirst().key().startsWith("b/"));
      assertEquals(
          List.of("foreignsymbol0", "foreignsymbol1"), fixture.engine.suggest("reader", "foreign"));
    }
  }
}
