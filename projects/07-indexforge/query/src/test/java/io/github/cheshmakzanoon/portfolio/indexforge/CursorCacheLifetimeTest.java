package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CursorCacheLifetimeTest {
  @TempDir Path root;

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
      return now;
    }

    void advance() {
      now = now.plusSeconds(301);
    }
  }

  @Test
  void cachedFirstAndMiddlePagesIssueFreshCursorsAfterEarlierTokensExpire() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "tenant", Set.of("repository"), true);
    var clock = new MutableClock();
    try (var engine =
        new SearchEngine(
            root,
            auth,
            QueryPlanner.Mode.BASELINE,
            RankingProfile.CURRENT,
            new RankingConfiguration(RankingStrategy.CLASSIC, null),
            clock)) {
      String digest = engine.contentStore().put("shared content".getBytes(StandardCharsets.UTF_8));
      var entries = new ArrayList<SnapshotManifest.Entry>();
      for (int i = 0; i < 3; i++)
        entries.add(
            new SnapshotManifest.Entry(
                "A" + i + ".txt", digest, "text", "source", List.of(), "", ""));
      engine.ingest(
          access,
          "seed",
          new SnapshotManifest("tenant", "repository", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request = new SearchEngine.Request("shared", 1, null, Map.of());
      var first = engine.search("reader", request);
      assertNotNull(first.cursor());
      clock.advance();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              engine.search(
                  "reader", new SearchEngine.Request("shared", 1, first.cursor(), Map.of())));
      long cachedBefore = engine.cacheHits();
      var refreshed = engine.search("reader", request);
      assertEquals(cachedBefore + 1, engine.cacheHits());
      assertEquals(first.results(), refreshed.results());
      assertNotEquals(first.cursor(), refreshed.cursor());
      var middle =
          engine.search(
              "reader", new SearchEngine.Request("shared", 1, refreshed.cursor(), Map.of()));
      assertNotNull(middle.cursor());
      clock.advance();
      var latest = engine.search("reader", request);
      var cachedMiddle =
          engine.search("reader", new SearchEngine.Request("shared", 1, latest.cursor(), Map.of()));
      assertEquals(middle.results(), cachedMiddle.results());
      assertNotEquals(middle.cursor(), cachedMiddle.cursor());
      var last =
          engine.search(
              "reader", new SearchEngine.Request("shared", 1, cachedMiddle.cursor(), Map.of()));
      assertEquals(1, last.results().size());
      assertNull(last.cursor());
      assertEquals(
          3,
          Set.of(
                  first.results().getFirst().key(),
                  middle.results().getFirst().key(),
                  last.results().getFirst().key())
              .size());
      assertEquals(first.generationId(), last.generationId());
    }
  }
}
