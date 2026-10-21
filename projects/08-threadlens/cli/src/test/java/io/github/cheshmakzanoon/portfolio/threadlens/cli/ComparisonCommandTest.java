package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator;
import io.github.cheshmakzanoon.portfolio.threadlens.report.ReportDocument;
import io.github.cheshmakzanoon.portfolio.threadlens.report.ReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComparisonCommandTest {
  @TempDir Path directory;

  @Test
  void commandProducesUsefulHtmlAndStructuredJsonFromTheSameInputs() throws Exception {
    Path baseline = input("baseline", 100, "20 ms");
    Path candidate = input("candidate", 250, "20 ms");
    Path html = directory.resolve("comparison.html");
    Path json = directory.resolve("comparison.json");
    ThreadLensCli.main(
        new String[] {
          "compare",
          baseline.toString(),
          candidate.toString(),
          html.toString(),
          "--json",
          json.toString()
        });
    String rendered = Files.readString(html);
    assertTrue(rendered.contains("Weighted allocation changes"));
    assertTrue(rendered.contains("Selected method timing changes"));
    assertTrue(rendered.contains("Parking changes"));
    assertTrue(rendered.contains("150.0"));
    var parsed = new ObjectMapper().readTree(json.toFile());
    assertEquals(1, parsed.get("schemaVersion").asInt());
    assertEquals("baseline", parsed.get("baselineRecordingId").asText());
    assertEquals("candidate", parsed.get("candidateRecordingId").asText());
    var allocation = parsed.get("allocations").get(0);
    assertEquals(150, allocation.get("weightedBytesChange").asLong());
    assertEquals(150.0, allocation.get("weightedBytesPercentChange").asDouble());
    assertEquals(1, parsed.get("parks").size());
    assertEquals(1, parsed.get("selectedTimings").size());
    assertEquals(1, parsed.get("monitors").size());
    assertNoStagedFiles();
  }

  @Test
  void existingFourArgumentCommandRemainsSupported() throws Exception {
    Path input = input("same", 100, "20 ms");
    Path html = directory.resolve("same.html");
    ThreadLensCli.main(
        new String[] {"compare", input.toString(), input.toString(), html.toString()});
    assertTrue(Files.readString(html).contains("Self-sample changes"));
    assertFalse(Files.exists(directory.resolve("same.html.json")));
    assertNoStagedFiles();
  }

  @Test
  void optionalOutputFlagHasAnExactGrammar() {
    for (String[] arguments :
        List.of(
            new String[] {"compare"},
            new String[] {"compare", "b", "c"},
            new String[] {"compare", "b", "c", "html", "--json"},
            new String[] {"compare", "b", "c", "html", "--other", "json"},
            new String[] {"compare", "b", "c", "html", "--json", "json", "extra"}))
      assertThrows(IllegalArgumentException.class, () -> ThreadLensCli.main(arguments));
  }

  @Test
  void outputCannotDestroyEitherInputIncludingNormalizedAliases() throws Exception {
    Path before = input("before", 1, "20 ms");
    Path after = input("after", 2, "20 ms");
    String beforeContents = Files.readString(before);
    String afterContents = Files.readString(after);
    for (Path output : List.of(before, after, directory.resolve("unused/../before.json"))) {
      assertThrows(IOException.class, () -> ComparisonCommand.run(before, after, output, null));
      assertThrows(
          IOException.class,
          () -> ComparisonCommand.run(before, after, directory.resolve("out.html"), output));
    }
    assertEquals(beforeContents, Files.readString(before));
    assertEquals(afterContents, Files.readString(after));
    assertFalse(Files.exists(directory.resolve("out.html")));
    assertNoStagedFiles();
  }

  @Test
  void hardLinkedOutputsCannotOverwriteAnInput() throws Exception {
    Path before = input("before", 1, "20 ms");
    Path after = input("after", 2, "20 ms");
    Path alias = directory.resolve("alias.html");
    Files.createLink(alias, before);
    String original = Files.readString(before);
    assertThrows(IOException.class, () -> ComparisonCommand.run(before, after, alias, null));
    assertEquals(original, Files.readString(before));
    assertNoStagedFiles();
  }

  @Test
  void htmlAndJsonMustHaveDifferentDestinationsIncludingHardLinks() throws Exception {
    Path input = input("input", 1, "20 ms");
    Path html = directory.resolve("same.html");
    assertThrows(IOException.class, () -> ComparisonCommand.run(input, input, html, html));
    Files.writeString(html, "original");
    Path json = directory.resolve("same.json");
    Files.createLink(json, html);
    assertThrows(IOException.class, () -> ComparisonCommand.run(input, input, html, json));
    assertEquals("original", Files.readString(html));
    assertEquals("original", Files.readString(json));
    assertNoStagedFiles();
  }

  @Test
  void malformedOrIncomparableInputsLeaveExistingOutputsUnchanged() throws Exception {
    Path input = input("input", 1, "20 ms");
    Path incompatible = input("incompatible", 1, "10 ms");
    Path malformed = directory.resolve("malformed.json");
    Files.writeString(malformed, "{not a report}");
    Path html = directory.resolve("comparison.html");
    Path json = directory.resolve("comparison.json");
    Files.writeString(html, "existing html");
    Files.writeString(json, "existing json");
    assertThrows(IOException.class, () -> ComparisonCommand.run(input, malformed, html, json));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparisonCommand.run(input, incompatible, html, json));
    assertEquals("existing html", Files.readString(html));
    assertEquals("existing json", Files.readString(json));
    assertNoStagedFiles();
  }

  @Test
  void directoriesAndMissingParentsFailBeforeAnyArtifactIsPublished() throws Exception {
    Path input = input("input", 1, "20 ms");
    Path html = directory.resolve("comparison.html");
    assertThrows(IOException.class, () -> ComparisonCommand.run(directory, input, html, null));
    assertThrows(IOException.class, () -> ComparisonCommand.run(input, input, directory, null));
    assertThrows(
        IOException.class,
        () -> ComparisonCommand.run(input, input, html, directory.resolve("missing/output.json")));
    assertFalse(Files.exists(html));
    assertNoStagedFiles();
  }

  @Test
  void repeatedRenderingIsDeterministicAndReplacesOnlyRequestedOutputs() throws Exception {
    Path input = input("input", 100, "20 ms");
    Path html = directory.resolve("comparison.html");
    Path json = directory.resolve("comparison.json");
    ComparisonCommand.run(input, input, html, json);
    String firstHtml = Files.readString(html), firstJson = Files.readString(json);
    ComparisonCommand.run(input, input, html, json);
    assertEquals(firstHtml, Files.readString(html));
    assertEquals(firstJson, Files.readString(json));
    assertEquals("input", ReportWriter.read(input).recordingId());
    assertNoStagedFiles();
  }

  private Path input(String name, long amount, String period) throws IOException {
    var accumulator = new ProfileAccumulator(10, 1_000_000_000L, 10);
    accumulator.accept(
        new NormalizedEvent(
            NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of("sample.Work.run()V")));
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(new NormalizedEvent(kind, 1, amount, "sample.Work", List.of()));
    Path path = directory.resolve(name + ".json");
    ReportWriter.json(
        path,
        ReportDocument.create(
            name,
            accumulator.snapshot(),
            Map.of("jdk.ExecutionSample#period", period),
            Map.of(),
            Map.of()));
    return path;
  }

  private void assertNoStagedFiles() throws IOException {
    try (var entries = Files.list(directory)) {
      assertFalse(
          entries.anyMatch(
              path -> path.getFileName().toString().startsWith(".threadlens-comparison-")));
    }
  }
}
