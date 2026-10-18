from pathlib import Path
b=Path('projects/07-indexforge');root='src/test/java/io/github/cheshmakzanoon/portfolio/indexforge'
p=b/'query/src/main/java/io/github/cheshmakzanoon/portfolio/indexforge/RankingProfile.java';s=p.read_text().replace('  BM25_NO_LENGTH','  BM25_DEFAULT(1.2f, .75f),\n  BM25_NO_LENGTH',1).replace('this(2, 6, 1, 32, 2, 2, 4, k1, b);','this(2, 6, 1, 32, 2, 2, 0, k1, b);');p.write_text(s)
p=b/'query'/root/'SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test
  void perLeaseSimilarityControlsLengthNormalizationAndAblationRestoresDefault() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    var request = new SearchEngine.Request("needle", 10, null, Map.of());
    Map<String, Float> defaults = new TreeMap<>(), baseline = new TreeMap<>();
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source : Map.of("Long.txt", "needle " + "padding ".repeat(90), "Short.txt", "needle").entrySet())
        entries.add(new SnapshotManifest.Entry(source.getKey(),
            engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
            "text", "source", List.of(), "", ""));
      engine.ingest(access, "bm25", new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(), () -> false);
      try (var lease = engine.generations("t").acquire()) {
        assertSame(lease.searcher(), lease.searcher(), "all response operations share one configured searcher");
      }
      engine.search("reader", request).results().forEach(hit -> defaults.put(hit.path(), hit.score()));
      engine.search("reader", request, QueryPlanner.Mode.BASELINE).results().forEach(hit -> baseline.put(hit.path(), hit.score()));
      assertTrue(defaults.get("Short.txt") > defaults.get("Long.txt"));
    }
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_NO_LENGTH)) {
      var noLength = engine.search("reader", request);
      assertEquals(2, noLength.totalHits());
      assertEquals(noLength.results().getFirst().score(), noLength.results().getLast().score(), .00001);
      assertEquals("Long.txt", noLength.results().getFirst().path(), "equal scores use stable key ordering");
      assertTrue(noLength.scoringVersion().endsWith("BM25_NO_LENGTH"));
      Map<String, Float> ablated = new TreeMap<>(), unchangedBaseline = new TreeMap<>();
      engine.search("reader", request, QueryPlanner.Mode.NO_SIMILARITY).results().forEach(hit -> ablated.put(hit.path(), hit.score()));
      engine.search("reader", request, QueryPlanner.Mode.BASELINE).results().forEach(hit -> unchangedBaseline.put(hit.path(), hit.score()));
      assertEquals(defaults, ablated);
      assertEquals(baseline, unchangedBaseline);
      assertEquals(noLength, engine.search("reader", request), "warmed configuration remains stable");
      assertEquals(0, engine.search("reader", new SearchEngine.Request("astronomy", 10, null, Map.of())).totalHits());
    }
  }

'''+s[at:];p.write_text(s)
p=b/'service'/root/'HttpSecurityIT.java';s=p.read_text();needle='try (ConfigurableApplicationContext context = app.run()) {';ix=s.rindex(needle);s=s[:ix]+s[ix:].replace(needle,'''try (ConfigurableApplicationContext context = app.run(
        "--indexforge.ranking-mode=NO_COVERAGE", "--indexforge.ranking-profile=BM25_NO_LENGTH")) {''',1);s=s.replace('''      assertTrue(response.body().contains("Demo.java"));''','''      assertTrue(response.body().contains("Demo.java"));
      assertTrue(response.body().contains("source-v6-bm25-BM25_NO_LENGTH"), response.body());''');p.write_text(s)
p=b/'evaluation'/root/'EvaluationSealTest.java';p.write_text('''package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationSealTest {
  @TempDir Path root;

  @Test
  void changingOutputCannotOpenAnotherHeldOutModelButExactReplayIsAllowed() throws Exception {
    Path fixtures = Files.createDirectory(root.resolve("fixtures"));
    Path first = root.resolve("first"), second = root.resolve("second");
    EvaluationSeal.claim(fixtures, first, "fixed model/profile/fixture identity");
    EvaluationSeal.claim(fixtures, second, "fixed model/profile/fixture identity");
    assertEquals(Files.readString(first.resolve("HELDOUT-OPENED.txt")),
        Files.readString(second.resolve("HELDOUT-OPENED.txt")));
    assertThrows(IOException.class, () -> EvaluationSeal.claim(fixtures, root.resolve("different-output"), "changed model"));
    assertFalse(Files.exists(root.resolve("different-output/HELDOUT-OPENED.txt")));
    Files.writeString(first.resolve("HELDOUT-OPENED.txt"), "partial");
    assertThrows(IOException.class, () -> EvaluationSeal.claim(fixtures, first, "fixed model/profile/fixture identity"));
  }

  @Test
  void oversizedAndCorruptSealsFailClosedWithoutSearchingAnyHeldOutFixture() throws Exception {
    Path fixtures = Files.createDirectory(root.resolve("fixtures"));
    assertThrows(IOException.class, () -> EvaluationSeal.claim(fixtures, root.resolve("output"), "x".repeat(4097)));
    Files.writeString(fixtures.resolve("HELDOUT-OPENED.txt"), "x".repeat(4097));
    assertThrows(IOException.class, () -> EvaluationSeal.claim(fixtures, root.resolve("output"), "fixed"));
  }
}
''')
