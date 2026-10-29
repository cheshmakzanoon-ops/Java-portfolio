package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.ProfileAccumulator;
import io.github.cheshmakzanoon.portfolio.backtrace.report.ReportDocument;
import io.github.cheshmakzanoon.portfolio.backtrace.report.ReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportCommandTest {
  @TempDir Path directory;

  private Path report(String name, int hotWeight) throws IOException {
    var accumulator = new ProfileAccumulator(50, 1_000_000_000L, 10);
    for (int i = 0; i < hotWeight; i++)
      accumulator.accept(
          new NormalizedEvent(
              NormalizedEvent.Kind.SAMPLE,
              1 + i,
              1,
              "sample",
              List.of("app.Main.run()V", "app.Hot.spin(Ljava/lang/String;)V")));
    accumulator.accept(
        new NormalizedEvent(
            NormalizedEvent.Kind.SAMPLE,
            99,
            1,
            "sample",
            List.of("app.Main.run()V", "app.Cold.idle()V")));
    Path path = directory.resolve(name + ".json");
    ReportWriter.json(
        path,
        ReportDocument.create(
            name,
            accumulator.snapshot(),
            Map.of("jdk.ExecutionSample#period", "20 ms"),
            Map.of(),
            Map.of()));
    return path;
  }

  private static void run(String... arguments) throws Exception {
    BacktraceCli.main(arguments);
  }

  @Test
  void foldedExportCountsEverySampleAndNamesFramesReadably() throws Exception {
    Path input = report("rec", 3);
    Path output = directory.resolve("rec.folded");
    run("export", "folded", input.toString(), output.toString());
    assertEquals(
        "app.Main.run();app.Cold.idle() 1\napp.Main.run();app.Hot.spin(java.lang.String) 3\n",
        Files.readString(output));
  }

  @Test
  void speedscopeExportIsValidJsonWithTheSameWeights() throws Exception {
    Path input = report("rec", 3);
    Path output = directory.resolve("rec.speedscope.json");
    run("export", "speedscope", input.toString(), output.toString());
    var parsed = new ObjectMapper().readTree(output.toFile());
    assertEquals("rec", parsed.get("name").asText());
    assertEquals(4, parsed.at("/profiles/0/endValue").asLong());
    assertEquals(3, parsed.at("/shared/frames").size());
  }

  @Test
  void differentialExportPairsBaselineAndCandidateCounts() throws Exception {
    Path before = report("before", 2), after = report("after", 5);
    Path output = directory.resolve("regression.folded");
    run("export", "diff", before.toString(), after.toString(), output.toString());
    assertEquals(
        "app.Main.run();app.Cold.idle() 1 1\napp.Main.run();app.Hot.spin(java.lang.String) 2 5\n",
        Files.readString(output));
  }

  @Test
  void repeatedExportIsDeterministicAndLeavesNoStagedFiles() throws Exception {
    Path input = report("rec", 4);
    Path output = directory.resolve("out.folded");
    run("export", "folded", input.toString(), output.toString());
    String first = Files.readString(output);
    run("export", "folded", input.toString(), output.toString());
    assertEquals(first, Files.readString(output));
    try (var entries = Files.list(directory)) {
      assertFalse(
          entries.anyMatch(p -> p.getFileName().toString().startsWith(".backtrace-export-")));
    }
  }

  @Test
  void outputsCannotReplaceInputsAndInputsMustBeRealReports() throws Exception {
    Path input = report("rec", 1), other = report("other", 1);
    String before = Files.readString(input);
    assertThrows(
        IOException.class, () -> run("export", "folded", input.toString(), input.toString()));
    assertThrows(
        IOException.class,
        () -> run("export", "diff", input.toString(), other.toString(), other.toString()));
    assertEquals(before, Files.readString(input));

    Path alias = directory.resolve("alias.json");
    Files.createSymbolicLink(alias, input);
    assertThrows(
        IOException.class,
        () -> run("export", "folded", alias.toString(), directory.resolve("x").toString()));
    assertThrows(
        IOException.class, () -> run("export", "folded", input.toString(), alias.toString()));

    Path notAReport = directory.resolve("not-a-report.json");
    Files.writeString(notAReport, "{\"schemaVersion\":1}");
    Path output = directory.resolve("never.folded");
    assertThrows(
        IOException.class, () -> run("export", "folded", notAReport.toString(), output.toString()));
    assertFalse(Files.exists(output), "a rejected input publishes nothing");
    assertThrows(
        IOException.class, () -> run("export", "folded", directory.toString(), output.toString()));
    assertThrows(
        IOException.class, () -> run("export", "folded", input.toString(), directory.toString()));
  }

  @Test
  void argumentShapesAreEnforcedBeforeAnyFileIsTouched() {
    for (String[] arguments :
        List.of(
            new String[] {"export"},
            new String[] {"export", "folded"},
            new String[] {"export", "folded", "in.json"},
            new String[] {"export", "folded", "in.json", "out", "extra"},
            new String[] {"export", "diff", "a.json", "out"},
            new String[] {"export", "pdf", "in.json", "out"}))
      assertThrows(
          IllegalArgumentException.class, () -> run(arguments), String.join(" ", arguments));
  }
}
