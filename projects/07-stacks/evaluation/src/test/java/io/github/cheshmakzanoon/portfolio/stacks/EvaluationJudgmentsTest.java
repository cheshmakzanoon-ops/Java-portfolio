package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationJudgmentsTest {
  @TempDir Path root;

  private List<String> developmentAndValidation() {
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < 60; i++)
      rows.add(
          "q"
              + i
              + "\t"
              + (i < 40 ? "development" : "validation")
              + "\tidentifier\tpulse\tsrc/A.java:3,src/B.java:1");
    return rows;
  }

  @Test
  void developmentNeverInterpretsPoisonHeldOutQueriesOrGrades() throws Exception {
    var rows = developmentAndValidation();
    rows.add("secret\theld-out\tINVALID\t( malformed\tINVALID");
    Files.write(root.resolve("judgments.tsv"), rows);
    assertEquals(60, EvaluationJudgments.read(root, Set.of("development", "validation")).size());
    assertEquals(40, EvaluationJudgments.read(root, Set.of("development")).size());
    assertThrows(IOException.class, () -> EvaluationJudgments.read(root, Set.of("held-out")));
    assertThrows(
        IllegalArgumentException.class, () -> EvaluationJudgments.read(root, Set.of("final")));
    assertThrows(IllegalArgumentException.class, () -> EvaluationJudgments.read(root, Set.of()));
    assertFalse(Files.exists(root.resolve("HELDOUT-OPENED.txt")));
  }

  @Test
  void explicitCompleteReaderRejectsDuplicateIdsInvalidGradesPathsAndSplitCounts()
      throws Exception {
    var rows = developmentAndValidation();
    for (int i = 60; i < 80; i++)
      rows.add("q" + i + "\theld-out\tprose\treader lease\tsrc/A.java:2");
    Files.write(root.resolve("judgments.tsv"), rows);
    assertEquals(80, RelevanceEvaluator.judgments(root).size());
    for (String invalid :
        List.of("src/A.java:4", "../outside:3", "src/A.java:3,src/A.java:2", "broken")) {
      var copy = new ArrayList<>(rows);
      copy.set(0, "q0\tdevelopment\tidentifier\tpulse\t" + invalid);
      Files.write(root.resolve("judgments.tsv"), copy);
      assertThrows(IOException.class, () -> RelevanceEvaluator.judgments(root), invalid);
    }
    var copy = new ArrayList<>(rows);
    copy.set(1, rows.getFirst());
    Files.write(root.resolve("judgments.tsv"), copy);
    assertThrows(IOException.class, () -> RelevanceEvaluator.judgments(root));
    Files.write(root.resolve("judgments.tsv"), rows.subList(0, 79));
    assertThrows(IOException.class, () -> RelevanceEvaluator.judgments(root));
  }

  @Test
  void fixtureManifestRequiresChecksummedAllowlistedInputsAndReview() throws Exception {
    Files.writeString(root.resolve("corpus.tsv"), "synthetic corpus");
    Files.writeString(root.resolve("judgments.tsv"), "synthetic labels");
    var freeze =
        List.of(
            Digests.sha256(root.resolve("corpus.tsv")) + "  corpus.tsv",
            Digests.sha256(root.resolve("judgments.tsv")) + "  judgments.tsv");
    Files.write(root.resolve("FREEZE.sha256"), freeze);
    assertThrows(IOException.class, () -> RelevanceEvaluator.verifyFreeze(root));
    Files.writeString(root.resolve("REVIEW-APPROVED.md"), "Synthetic correctness fixture");
    RelevanceEvaluator.verifyFreeze(root);
    for (List<String> invalid :
        List.of(
            List.<String>of(),
            List.of(freeze.getFirst()),
            List.of(freeze.getFirst(), freeze.getFirst()),
            List.of("0".repeat(64) + "  ../outside"))) {
      Files.write(root.resolve("FREEZE.sha256"), invalid);
      assertThrows(IOException.class, () -> RelevanceEvaluator.verifyFreeze(root));
    }
    Files.write(root.resolve("FREEZE.sha256"), freeze);
    Files.writeString(root.resolve("corpus.tsv"), "changed");
    assertThrows(IOException.class, () -> RelevanceEvaluator.verifyFreeze(root));
  }

  @Test
  void performanceInputValidationPrecedesAnyWorkOrOutputCreation() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> PerformanceHarness.run(root.resolve("data"), root.resolve("output"), 3, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> PerformanceHarness.run(root.resolve("data"), root.resolve("output"), 3, 3601));
    assertThrows(
        IllegalArgumentException.class,
        () -> PerformanceHarness.run(root.resolve("data"), root.resolve("output"), 100001, 1));
    assertFalse(Files.exists(root.resolve("data")));
    assertFalse(Files.exists(root.resolve("output")));
    Path output = Files.createDirectory(root.resolve("existing-output"));
    Path evidence = Files.writeString(output.resolve("summary.json"), "prior evidence");
    assertThrows(
        FileAlreadyExistsException.class,
        () -> PerformanceHarness.run(root.resolve("data"), output, 3, 1));
    assertEquals("prior evidence", Files.readString(evidence));
    assertFalse(Files.exists(root.resolve("data")));
  }
}
