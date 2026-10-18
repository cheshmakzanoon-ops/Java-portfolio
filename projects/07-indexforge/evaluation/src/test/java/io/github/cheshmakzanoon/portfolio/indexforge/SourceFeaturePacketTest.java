package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFeaturePacketTest {
  @TempDir Path root;

  private SourceFeaturePacket packet(boolean evidence) {
    var queries = new ArrayList<SourceFeaturePacket.Query>();
    for (int i = 0; i < 40; i++) {
      var header =
          new Wave2Inputs.Header(
              "q" + i,
              "development",
              i == 0 ? "identifier" : "prose",
              i == 0 ? "member" : "observe shared values");
      double[] first = new double[16], second = new double[16];
      first[0] = 2;
      second[0] = 10;
      if (evidence) second[12] = 1;
      var rows =
          List.of(
              new SearchEngine.AuditCandidate("tenant/repo/a.java", "a.java", first),
              new SearchEngine.AuditCandidate("tenant/repo/b.java", "b.java", second));
      var usage =
          i == 0
              ? List.of("tenant/repo/b.java", "tenant/repo/a.java")
              : rows.stream().map(SearchEngine.AuditCandidate::key).toList();
      queries.add(
          new SourceFeaturePacket.Query(
              header, rows, usage, i == 0 ? "APPLIED_OBSERVED_LOWER_BOUND" : "SYNTAX_BYPASS"));
    }
    return new SourceFeaturePacket(queries);
  }

  private static Map<String, Map<String, Integer>> labels(SourceFeaturePacket packet) {
    var labels = new TreeMap<String, Map<String, Integer>>();
    for (var query : packet.queries())
      labels.put(
          query.header().id(), Map.of("b.java", 3, "missing-" + query.header().id() + ".java", 1));
    return labels;
  }

  @Test
  void labelBlindRoundTripRetainsAll16DimensionsAndSeparateUsagePermutation() throws Exception {
    var packet = packet(true);
    Path file = root.resolve("features.tsv");
    packet.writeNew(file);
    String text = Files.readString(file);
    assertFalse(text.contains("grade"));
    assertFalse(text.contains("judgment"));
    var read = SourceFeaturePacket.read(file);
    assertEquals(40, read.queries().size());
    assertEquals(List.of("b.java", "a.java"), read.queries().getFirst().usagePaths());
    assertEquals(1, read.queries().getFirst().candidates().get(1).features()[12]);
    assertThrows(FileAlreadyExistsException.class, () -> packet.writeNew(file));
    for (int dimensions : List.of(12, 16)) {
      var attached = read.attachDevelopmentLabels(labels(read), dimensions);
      assertEquals(40, attached.size());
      assertEquals(dimensions, attached.getFirst().rows().getFirst().features().length);
      assertEquals(
          3,
          attached.getFirst().rows().stream()
              .filter(row -> row.key().equals("b.java"))
              .findFirst()
              .orElseThrow()
              .grade());
      assertEquals(
          0,
          attached.getFirst().rows().stream()
              .filter(row -> row.key().equals("a.java"))
              .findFirst()
              .orElseThrow()
              .grade());
      assertEquals(0, attached.getFirst().rows().getFirst().baseRank());
    }
  }

  @Test
  void syntaxMetadataCannotMakeMultiwordOrUnknownPoolsAnIdentifierUsageWinner() throws Exception {
    var query = packet(true).queries().get(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceFeaturePacket.Query(
                query.header(),
                query.candidates(),
                List.of("tenant/repo/b.java", "tenant/repo/a.java"),
                "APPLIED_OBSERVED_LOWER_BOUND"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceFeaturePacket.Query(
                query.header(),
                query.candidates(),
                List.of("tenant/repo/a.java", "tenant/repo/a.java"),
                "SYNTAX_BYPASS"));
    assertThrows(
        IllegalArgumentException.class, () -> packet(true).attachDevelopmentLabels(Map.of(), 16));
    Path valid = root.resolve("valid");
    packet(true).writeNew(valid);
    Path invalid = root.resolve("invalid");
    // Independently force a usage-rank gap in the known first record, without changing features.
    var lines = new ArrayList<>(Files.readAllLines(valid));
    String[] fields = lines.get(2).split("\t", -1);
    fields[4] = "499";
    lines.set(2, String.join("\t", fields));
    Files.write(invalid, lines);
    assertThrows(IOException.class, () -> SourceFeaturePacket.read(invalid));
  }

  @Test
  void noNewEvidencePreservesC1OrderingRatherThanApplyingAZeroFeatureModel() throws Exception {
    var without = packet(false);
    var queries = without.attachDevelopmentLabels(labels(without), 16);
    double[] scales = new double[16], weights = new double[16];
    Arrays.fill(scales, 1);
    weights[0] = 1;
    var model =
        new LinearRankingModel(new double[16], scales, weights, "a".repeat(64), "b".repeat(64), 1);
    assertFalse(SourcePairwiseSelection.reranks(queries.get(1)));
    assertEquals(
        List.of("a.java", "b.java"), SourcePairwiseSelection.rankedPaths(queries, model).get("q1"));
    var with = packet(true).attachDevelopmentLabels(labels(packet(true)), 16);
    assertEquals(
        List.of("b.java", "a.java"), SourcePairwiseSelection.rankedPaths(with, model).get("q1"));
    assertEquals(
        List.of("a.java", "b.java"),
        SourcePairwiseSelection.rankedPaths(with, model).get("q0"),
        "actual single identifier must bypass prose learning");
    var assignments = GroupedFolds.assign(queries);
    var result =
        new SourcePairwiseSelection()
            .select(
                queries,
                labels(without),
                Set.of("q0"),
                assignments,
                "a".repeat(64),
                "b".repeat(64),
                new PairwiseSelection.Metrics(.5, .5, .5),
                new PairwiseSelection.Metrics(.5, .5, .5));
    assertEquals("NOT_ATTEMPTED_SOURCE_AUDIT_CONDITION", result.status());
    assertTrue(result.folds().isEmpty());
    assertNull(result.selected());
  }

  @Test
  void malformedLongAndNonfiniteRecordsFailBeforeAnyLabelAttachment() throws Exception {
    Path valid = root.resolve("valid");
    packet(true).writeNew(valid);
    String text = Files.readString(valid);
    Path invalid = root.resolve("invalid");
    Files.writeString(invalid, text.replaceFirst("2\\.0", "NaN"));
    assertThrows(IOException.class, () -> SourceFeaturePacket.read(invalid));
    Files.writeString(invalid, text.lines().findFirst().orElseThrow() + "\n" + "x".repeat(16385));
    assertThrows(IOException.class, () -> SourceFeaturePacket.read(invalid));
    Files.writeString(invalid, text.replaceFirst("cTA=", "*invalid*"));
    assertThrows(IOException.class, () -> SourceFeaturePacket.read(invalid));
  }
}
