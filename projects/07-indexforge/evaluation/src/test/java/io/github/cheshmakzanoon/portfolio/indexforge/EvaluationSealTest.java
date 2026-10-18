package io.github.cheshmakzanoon.portfolio.indexforge;

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
    assertEquals(
        Files.readString(first.resolve("HELDOUT-OPENED.txt")),
        Files.readString(second.resolve("HELDOUT-OPENED.txt")));
    assertThrows(
        IOException.class,
        () -> EvaluationSeal.claim(fixtures, root.resolve("different-output"), "changed model"));
    assertFalse(Files.exists(root.resolve("different-output/HELDOUT-OPENED.txt")));
    Files.writeString(first.resolve("HELDOUT-OPENED.txt"), "partial");
    assertThrows(
        IOException.class,
        () -> EvaluationSeal.claim(fixtures, first, "fixed model/profile/fixture identity"));
  }

  @Test
  void oversizedAndCorruptSealsFailClosedWithoutSearchingAnyHeldOutFixture() throws Exception {
    Path fixtures = Files.createDirectory(root.resolve("fixtures"));
    assertThrows(
        IOException.class,
        () -> EvaluationSeal.claim(fixtures, root.resolve("output"), "x".repeat(4097)));
    Files.writeString(fixtures.resolve("HELDOUT-OPENED.txt"), "x".repeat(4097));
    assertThrows(
        IOException.class, () -> EvaluationSeal.claim(fixtures, root.resolve("output"), "fixed"));
  }
}
