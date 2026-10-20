package io.github.cheshmakzanoon.portfolio.byteguard.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.cli.ByteGuard;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputSafetyTest {
  @TempDir Path directory;

  @Test
  void reportCannotOverwriteItsInputEvenWhenInputParsingFails() throws Exception {
    Path input = directory.resolve("invalid.class");
    byte[] original = new byte[] {1, 2, 3, 4};
    Files.write(input, original);
    assertEquals(2, scan(input, input));
    assertArrayEquals(original, Files.readAllBytes(input));
  }

  @Test
  void reportAliasesThroughHardLinksAndSymbolicParentsAreRejected() throws Exception {
    Path input = directory.resolve("input.class");
    byte[] original = new byte[] {1, 2, 3, 4};
    Files.write(input, original);
    Path alias = directory.resolve("alias.class");
    Files.createLink(alias, input);
    assertEquals(2, scan(input, alias));
    assertArrayEquals(original, Files.readAllBytes(input));
    Path real = Files.createDirectory(directory.resolve("classes"));
    Path source = real.resolve("Target.class");
    Files.write(source, original);
    Path symbolic = Files.createSymbolicLink(directory.resolve("linked"), real);
    assertEquals(2, scan(real, symbolic.resolve("Target.class")));
    assertArrayEquals(original, Files.readAllBytes(source));
  }

  @Test
  void symbolicParentThenDotDotUsesFilesystemSemanticsBeforeOutputValidation() throws Exception {
    Path scanned = Files.createDirectory(directory.resolve("scanned"));
    Path input = scanned.resolve("Target.class");
    byte[] original = new byte[] {1, 2, 3, 4};
    Files.write(input, original);
    Path front = Files.createDirectory(directory.resolve("front"));
    Path deep = Files.createDirectory(directory.resolve("deep"));
    Path link = Files.createSymbolicLink(front.resolve("link"), deep);
    Path aliasedDirectory = link.resolve("..").resolve("scanned");
    assertEquals(scanned.toRealPath(), aliasedDirectory.toRealPath());

    assertEquals(2, scan(scanned, aliasedDirectory.resolve("Target.class")));
    assertArrayEquals(original, Files.readAllBytes(input));
    Path absentReport = aliasedDirectory.resolve("new-report.sarif");
    assertEquals(2, scan(scanned, absentReport));
    assertFalse(Files.exists(scanned.resolve("new-report.sarif")));
    assertArrayEquals(original, Files.readAllBytes(input));
  }

  @Test
  void baselinesAndRuleModelsCannotBecomeReportsOrExplanations() throws Exception {
    Path input = directory.resolve("input.class");
    Files.write(input, new byte[] {1, 2, 3, 4});
    for (String option : List.of("--baseline", "--rules")) {
      Path configuration = directory.resolve(option.substring(2) + ".json");
      byte[] original = "retained configuration".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(configuration, original);
      assertEquals(2, scan(input, configuration, option, configuration.toString()));
      assertArrayEquals(original, Files.readAllBytes(configuration));
      assertEquals(
          2,
          scan(
              input,
              directory.resolve("report.sarif"),
              option,
              configuration.toString(),
              "--explain-dir",
              directory.toString()));
      assertArrayEquals(original, Files.readAllBytes(configuration));
    }
  }

  @Test
  void failedReportSerializationPreservesThePreviousReport() throws Exception {
    Path report = directory.resolve("previous.sarif");
    String original = "previous complete report";
    Files.writeString(report, original);
    var exporter = new io.github.cheshmakzanoon.portfolio.byteguard.report.SarifExporter();
    assertThrows(java.io.IOException.class, () -> exporter.write(report, new Object()));
    assertEquals(original, Files.readString(report));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(report), files.toList());
    }
  }

  private int scan(Path input, Path output, String... additional) {
    List<String> arguments =
        new ArrayList<>(List.of("scan", "--input", input.toString(), "--out", output.toString()));
    arguments.addAll(List.of(additional));
    ByteArrayOutputStream messages = new ByteArrayOutputStream();
    try (PrintStream stream = new PrintStream(messages)) {
      return ByteGuard.execute(arguments.toArray(String[]::new), stream, stream);
    }
  }
}
