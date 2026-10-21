package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Wave2ProtocolTest {
  @TempDir Path root;

  static double[] f(double n) {
    double[] values = new double[10];
    values[0] = n;
    return values;
  }

  static FeaturePacket packet() {
    List<FeaturePacket.Query> queries = new ArrayList<>();
    for (int i = 0; i < 40; i++)
      queries.add(
          new FeaturePacket.Query(
              new Wave2Inputs.Header(
                  "q" + i,
                  "development",
                  i % 2 == 0 ? "prose" : "identifier",
                  i % 2 == 0 ? "reader lease" : "resolve"),
              List.of(
                  new SearchEngine.AuditCandidate("opaque/t/r/Mention.java", "Mention.java", f(0)),
                  new SearchEngine.AuditCandidate("opaque/t/r/Actual.java", "Actual.java", f(1)))));
    return new FeaturePacket(queries);
  }

  @Test
  void featurePacketIsLabelBlindBoundedAndDoesNotInsertMissingPositive() throws Exception {
    Path file = root.resolve("features.tsv");
    var original = packet();
    original.writeNew(file);
    assertThrows(FileAlreadyExistsException.class, () -> original.writeNew(file));
    String export = Files.readString(file);
    assertFalse(export.contains("grade"));
    assertFalse(export.contains("judgment"));
    var restored = FeaturePacket.read(file);
    assertEquals(40, restored.queries().size());
    assertEquals("Mention.java", restored.queries().getFirst().candidates().getFirst().path());
    assertArrayEquals(f(1), restored.queries().getFirst().candidates().get(1).features());
    Map<String, Map<String, Integer>> labels = new TreeMap<>();
    for (var query : original.queries())
      labels.put(query.header().id(), Map.of("Missing.java", 3, "Actual.java", 1));
    var attached = restored.attachDevelopmentLabels(labels);
    assertEquals(2, attached.getFirst().rows().size());
    assertTrue(
        attached.stream()
            .flatMap(q -> q.rows().stream())
            .noneMatch(r -> r.key().equals("Missing.java")));
    var base = PairwiseSelection.basePaths(attached);
    var metric =
        PairwiseSelection.metrics(
            base,
            labels,
            original.queries().stream()
                .filter(q -> q.header().type().equals("identifier"))
                .map(q -> q.header().id())
                .collect(java.util.stream.Collectors.toSet()));
    assertTrue(metric.ndcg10() > 0 && metric.ndcg10() < .15);
    assertEquals(.5, metric.identifierMrr10());
    assertEquals(.5, metric.recall20());
    assertThrows(IllegalArgumentException.class, () -> restored.attachDevelopmentLabels(Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new FeaturePacket(original.queries().subList(0, 39)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeaturePacket.Query(
                new Wave2Inputs.Header("v", "validation", "prose", "reader lease"), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeaturePacket.Query(
                original.queries().getFirst().header(),
                List.of(
                    original.queries().getFirst().candidates().getFirst(),
                    original.queries().getFirst().candidates().getFirst())));
  }

  @Test
  void malformedExportsAndInvalidUnicodeFailExplicitly() throws Exception {
    Path valid = root.resolve("valid.tsv");
    packet().writeNew(valid);
    String original = Files.readString(valid);
    Path changed = root.resolve("changed.tsv");
    for (String bad :
        List.of(
            "D\tbogus\n",
            "X\n",
            original.replaceFirst("D\\t", "X\t"),
            original.replaceFirst("\\t0\\t", "\t2\t"),
            original.replaceFirst("\\t1.0\\t", "\tNaN\t"),
            original.replaceFirst("Q\\t[^\\t]*", "Q\t/w=="),
            "X".repeat(65537))) {
      Files.writeString(changed, bad);
      assertThrows(IOException.class, () -> FeaturePacket.read(changed));
    }
    Files.writeString(changed, "");
    assertThrows(IOException.class, () -> FeaturePacket.read(changed));
    try (var sparse = java.nio.channels.FileChannel.open(changed, StandardOpenOption.WRITE)) {
      sparse.position(134217728);
      sparse.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    assertThrows(IOException.class, () -> FeaturePacket.read(changed));
  }

  @Test
  void splitIsFilteredBeforeAnyHeldOutContentOrGradeParsing() throws Exception {
    Files.writeString(
        root.resolve("judgments.tsv"),
        "q1\tdevelopment\tprose\treader lease\tActual.java:3\n"
            + "secret\theld-out\tinvalid type\t( invalid syntax\tinvalid grade\n"
            + "q2\tvalidation\tidentifier\tresolve\tOther.java:2\n");
    var dev = Wave2Inputs.headers(root, Set.of("development"));
    assertEquals(1, dev.size());
    assertEquals("reader lease", dev.getFirst().text());
    assertEquals(Map.of("q1", Map.of("Actual.java", 3)), Wave2Inputs.labels(root, dev));
    assertThrows(
        IllegalArgumentException.class, () -> Wave2Inputs.headers(root, Set.of("held-out")));
    assertThrows(IllegalArgumentException.class, () -> Wave2Inputs.headers(root, Set.of()));
    var validation = Wave2Inputs.headers(root, Set.of("validation"));
    assertThrows(IllegalArgumentException.class, () -> Wave2Inputs.labels(root, validation));
    assertEquals(
        Map.of("q2", Map.of("Other.java", 2)), Wave2Inputs.evaluationLabels(root, validation));
    assertEquals(
        Wave2Inputs.judgmentGroup(Map.of("a", 1, "b", 3)),
        Wave2Inputs.judgmentGroup(new LinkedHashMap<>(Map.of("b", 3, "a", 1))));
    assertEquals("\"a\\n\\u03bb\"", Wave2Inputs.quote("a\nλ"));
  }

  @Test
  void finiteDevelopmentGridPreservesGroupsAndUsesFullJudgments() {
    List<PairwiseData.Query> queries = new ArrayList<>();
    Map<String, Map<String, Integer>> labels = new TreeMap<>();
    Set<String> identifiers = new HashSet<>();
    for (int i = 0; i < 40; i++) {
      String id = "q" + i, positive = "Positive" + i % 5 + ".java";
      var full = Map.of(positive, 3);
      labels.put(id, full);
      if (i % 2 == 1) identifiers.add(id);
      queries.add(
          new PairwiseData.Query(
              id,
              Wave2Inputs.judgmentGroup(full),
              i % 2 == 0 ? "reader lease" : "resolve",
              "development",
              List.of(
                  new PairwiseData.Row("Wrong.java", f(0), 0, 0),
                  new PairwiseData.Row(positive, f(1), 3, 1))));
    }
    var result =
        PairwiseSelection.select(
            queries,
            labels,
            identifiers,
            GroupedFolds.assign(queries),
            "a".repeat(64),
            "b".repeat(64));
    assertEquals("FITTED_BEFORE_VALIDATION", result.status());
    assertNotNull(result.selected());
    assertEquals(1, result.selected().model().l2());
    assertEquals(15, result.folds().size());
    assertEquals(3, result.trials().size());
    for (var trial : result.trials()) {
      assertNull(trial.failure());
      assertTrue(trial.metrics().ndcg10() > result.comparator().ndcg10());
      assertEquals(result.comparator().identifierMrr10(), trial.metrics().identifierMrr10());
    }
    var paths = PairwiseSelection.rankedPaths(queries, result.selected().model());
    assertEquals("Wrong.java", paths.get("q1").getFirst());
    assertEquals("Positive0.java", paths.get("q0").getFirst());
    var changed = new HashMap<>(labels);
    changed.put("q0", Map.of("Elsewhere.java", 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PairwiseSelection.select(
                queries,
                changed,
                identifiers,
                GroupedFolds.assign(queries),
                "a".repeat(64),
                "b".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PairwiseSelection.metrics(Map.of(), labels, identifiers));
    var notEligible =
        queries.stream()
            .map(q -> new PairwiseData.Query(q.id(), q.group(), "resolve", q.split(), q.rows()))
            .toList();
    assertEquals(
        "NOT_ATTEMPTED_AUDIT_CONDITION",
        PairwiseSelection.select(
                notEligible,
                labels,
                identifiers,
                GroupedFolds.assign(notEligible),
                "a".repeat(64),
                "b".repeat(64))
            .status());
  }

  @Test
  void nonDiscriminatingFeaturesCannotPassByDroppingGoodBaseOrdering() {
    var fixture = selectionFixture(false);
    var result =
        PairwiseSelection.select(
            fixture.queries(),
            fixture.labels(),
            fixture.identifiers(),
            GroupedFolds.assign(fixture.queries()),
            "a".repeat(64),
            "b".repeat(64));
    assertEquals("FAILED_NO_DEVELOPMENT_DOMINANCE", result.status());
    assertNull(result.selected());
    assertEquals(3, result.trials().size());
    assertEquals(15, result.folds().size());
    assertTrue(
        result.trials().stream()
            .allMatch(
                t -> t.failure() == null && t.metrics().ndcg10() < result.comparator().ndcg10()));
    var assignments = new HashMap<>(GroupedFolds.assign(fixture.queries()));
    assignments.put("q0", 4);
    if (assignments.equals(GroupedFolds.assign(fixture.queries()))) assignments.put("q0", 3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PairwiseSelection.select(
                fixture.queries(),
                fixture.labels(),
                fixture.identifiers(),
                assignments,
                "a".repeat(64),
                "b".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PairwiseSelection.select(
                fixture.queries(),
                fixture.labels(),
                Set.of(),
                GroupedFolds.assign(fixture.queries()),
                "a".repeat(64),
                "b".repeat(64)));
  }

  @Test
  void aFoldWithoutEligibleTrainingQueriesRecordsFailedTrialsAndNoModel() {
    var fixture = selectionFixture(true);
    var result =
        PairwiseSelection.select(
            fixture.queries(),
            fixture.labels(),
            fixture.identifiers(),
            GroupedFolds.assign(fixture.queries()),
            "a".repeat(64),
            "b".repeat(64));
    assertEquals("FAILED_NO_DEVELOPMENT_DOMINANCE", result.status());
    assertNull(result.selected());
    assertEquals(3, result.trials().size());
    assertTrue(
        result.trials().stream()
            .allMatch(
                t ->
                    t.metrics() == null && t.failure().contains("no eligible development scaler")));
    assertEquals(3, result.folds().stream().filter(f -> f.fit() == null).count());
  }

  private record SelectionFixture(
      List<PairwiseData.Query> queries,
      Map<String, Map<String, Integer>> labels,
      Set<String> identifiers) {}

  private SelectionFixture selectionFixture(boolean concentrated) {
    List<PairwiseData.Query> queries = new ArrayList<>();
    Map<String, Map<String, Integer>> labels = new TreeMap<>();
    Set<String> identifiers = new HashSet<>();
    for (int i = 0; i < 40; i++) {
      boolean plain = concentrated ? i < 10 : i % 2 == 0;
      int group = concentrated ? i < 10 ? 0 : 1 + i % 4 : i % 5;
      String id = "q" + i, positive = "ZPositive" + group + ".java";
      var full = Map.of(positive, 3);
      labels.put(id, full);
      if (!plain) identifiers.add(id);
      int positiveRank = i == 0 ? 1 : 0;
      queries.add(
          new PairwiseData.Query(
              id,
              Wave2Inputs.judgmentGroup(full),
              plain ? "reader lease" : "resolve",
              "development",
              List.of(
                  new PairwiseData.Row("AWrong.java", f(0), 0, 1 - positiveRank),
                  new PairwiseData.Row(positive, f(0), 3, positiveRank))));
    }
    return new SelectionFixture(queries, labels, identifiers);
  }
}
