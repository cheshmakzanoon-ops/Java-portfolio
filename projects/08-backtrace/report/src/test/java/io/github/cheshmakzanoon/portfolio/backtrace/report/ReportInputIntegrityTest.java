package io.github.cheshmakzanoon.portfolio.backtrace.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.ProfileAccumulator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportInputIntegrityTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void oneReportCannotHideATrailingDocumentPrimitiveOrGarbage() throws Exception {
    String report = JSON.writeValueAsString(valid());
    Path input = directory.resolve("trailing.json");
    for (String tail : List.of("{}", "[]", "true", "null", "0", "garbage", report)) {
      Files.writeString(input, report + "\n" + tail);
      assertThrows(IOException.class, () -> ReportWriter.read(input), tail);
    }
    Files.writeString(input, report + "\n\t ");
    assertEquals(valid(), ReportWriter.read(input));
  }

  @Test
  void primitiveNullsFractionalNumbersAndScalarCoercionCannotForgeCounts() throws Exception {
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            root -> root.putNull("sampleCount"),
            root -> root.put("sampleCount", "2"),
            root -> root.put("sampleCount", 2.0),
            root -> root.put("sampleCount", 2.5),
            root -> root.put("schemaVersion", "1"),
            root -> root.put("recordingId", 1234),
            root -> root.put("jdkVersion", true),
            root -> root.put("jdkVendor", 25.1),
            root -> ((ObjectNode) root.get("lossCounters")).put("drops", "0"),
            root ->
                ((ObjectNode) root.get("eventSettings")).put("jdk.ExecutionSample#enabled", true),
            root -> firstWindow(root).put("incomplete", "false"),
            root -> firstWindow(root).putNull("incomplete"),
            root -> ((ObjectNode) root.get("profile")).putNull("eventCount"))) reject(mutation);
  }

  @Test
  void duplicateWindowSamplesCannotExceedTheProfileDenominator() throws Exception {
    reject(root -> firstWindow(root).put("samples", 2));
  }

  @Test
  void overlappingAndOutOfSpanWindowsCannotMasqueradeAsIndependentObservation() throws Exception {
    reject(root -> firstWindow(root).put("endNanos", 2_000_000_001L));
    reject(
        root -> {
          var windows = (ObjectNode) root.get("profile").get("windows");
          var fake = firstWindow(root).deepCopy();
          fake.put("startNanos", 10_000_000_000L)
              .put("endNanos", 11_000_000_000L)
              .put("samples", 0);
          windows.set("10000000000", fake);
        });
    reject(
        root -> {
          root.put("startNanos", 2_000_000_001L);
          ((ObjectNode) root.get("profile")).put("startNanos", 2_000_000_001L);
        });
  }

  @Test
  void nonzeroMeasurementsRequireAnObservedEventAndDuration() throws Exception {
    for (Consumer<ObjectNode> mutation :
        List.<Consumer<ObjectNode>>of(
            root -> allocation(root).put("events", 0),
            root -> timing(root, "monitors").put("events", 0),
            root -> timing(root, "parks").put("events", 0),
            root -> timing(root, "timings").put("events", 0),
            root -> timing(root, "monitors").put("totalNanos", 0),
            root -> timing(root, "parks").put("totalNanos", 0),
            root -> timing(root, "timings").put("totalNanos", 0))) reject(mutation);
  }

  @Test
  void legitimateZeroWeightAndZeroDurationEventsRemainReadable() throws Exception {
    var accumulator = new ProfileAccumulator(10, 1_000_000_000L, 10);
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(new NormalizedEvent(kind, 1, 0, "zero", List.of()));
    var document =
        ReportDocument.create(
            "zero-events",
            accumulator.snapshot(),
            Map.of("jdk.ExecutionSample#period", "20 ms"),
            Map.of(),
            Map.of());
    Path input = directory.resolve("zero.json");
    ReportWriter.json(input, document);
    assertEquals(document, ReportWriter.read(input));
  }

  private void reject(Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode root = JSON.valueToTree(valid());
    mutation.accept(root);
    Path input = directory.resolve("invalid.json");
    Files.writeString(input, JSON.writeValueAsString(root));
    assertThrows(IOException.class, () -> ReportWriter.read(input));
  }

  private static ObjectNode firstWindow(ObjectNode root) {
    return (ObjectNode) root.get("profile").get("windows").get("0");
  }

  private static ObjectNode allocation(ObjectNode root) {
    return (ObjectNode) root.get("profile").get("allocations").get("subject");
  }

  private static ObjectNode timing(ObjectNode root, String name) {
    return (ObjectNode) root.get("profile").get(name).get("subject");
  }

  private static ReportDocument valid() {
    var accumulator = new ProfileAccumulator(10, 1_000_000_000L, 10);
    for (long instant : List.of(1L, 2_000_000_001L))
      accumulator.accept(
          new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, instant, 1, "sample", List.of("frame")));
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(new NormalizedEvent(kind, 1, 100, "subject", List.of()));
    return ReportDocument.create(
        "integrity",
        accumulator.snapshot(),
        Map.of("jdk.ExecutionSample#period", "20 ms"),
        Map.of(),
        Map.of());
  }
}
