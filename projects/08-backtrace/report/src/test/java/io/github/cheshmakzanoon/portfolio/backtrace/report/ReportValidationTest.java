package io.github.cheshmakzanoon.portfolio.backtrace.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportValidationTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();

  private static ReportDocument valid() {
    ProfileAccumulator accumulator = new ProfileAccumulator(10, 1_000_000_000L, 10);
    accumulator.accept(
        new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of("frame")));
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(new NormalizedEvent(kind, 1, 1, "subject", List.of()));
    return ReportDocument.create(
        "bounded-report",
        accumulator.snapshot(),
        Map.of("jdk.ExecutionSample#period", "20 ms"),
        Map.of(),
        Map.of());
  }

  @Test
  void serializedReportCannotChangeItsDenominatorCoordinatesOrSchema() throws Exception {
    for (Consumer<ObjectNode> corrupt :
        List.<Consumer<ObjectNode>>of(
            root -> root.put("schemaVersion", 2),
            root -> root.put("recordingId", ""),
            root -> root.put("recordingId", "x".repeat(257)),
            root -> root.putNull("jdkVendor"),
            root -> root.put("sampleCount", 2),
            root -> root.put("startNanos", 2),
            root -> root.put("endNanos", 2),
            root -> profile(root).put("eventCount", -1),
            root -> profile(root).put("sampleCount", 6),
            root -> profile(root).put("startNanos", 2),
            root -> profile(root).put("endNanos", 0),
            root -> ((ObjectNode) profile(root).get("flame")).put("inclusive", 2),
            root -> profile(root).putNull("counters"),
            root -> root.putNull("eventSettings"))) reject(corrupt);
  }

  @Test
  void flameAndMethodTotalsCannotExceedTheirObservedSampleCounts() throws Exception {
    for (Consumer<ObjectNode> corrupt :
        List.<Consumer<ObjectNode>>of(
            root -> ((ObjectNode) profile(root).get("flame")).put("self", 2),
            root ->
                ((ObjectNode) profile(root).get("flame").get("children").get(0))
                    .put("inclusive", -1),
            root ->
                ((ArrayNode) profile(root).get("flame").get("children"))
                    .add(profile(root).get("flame").get("children").get(0).deepCopy()),
            root -> ((ObjectNode) profile(root).get("methods").get("frame")).put("self", -1),
            root -> ((ObjectNode) profile(root).get("methods").get("frame")).put("self", 2),
            root -> ((ObjectNode) profile(root).get("methods").get("frame")).put("inclusive", 2),
            root ->
                ((ObjectNode) profile(root).get("methods"))
                    .set("second", profile(root).get("methods").get("frame").deepCopy())))
      reject(corrupt);
    reject(
        root -> {
          ObjectNode flame = (ObjectNode) profile(root).get("flame");
          flame.put("self", Long.MAX_VALUE);
          ((ObjectNode) flame.get("children").get(0)).put("inclusive", Long.MAX_VALUE);
        });
  }

  @Test
  void invalidWindowsQuantilesAndCountersCannotBeRenderedAsMeasurements() throws Exception {
    for (Consumer<ObjectNode> corrupt :
        List.<Consumer<ObjectNode>>of(
            root -> ((ObjectNode) profile(root).get("windows").get("0")).put("startNanos", 1),
            root -> ((ObjectNode) profile(root).get("windows").get("0")).put("endNanos", 0),
            root -> ((ObjectNode) profile(root).get("windows").get("0")).put("samples", 2),
            root -> ((ObjectNode) profile(root).get("windows").get("0")).put("allocatedBytes", -1),
            root ->
                ((ObjectNode) profile(root).get("allocations").get("subject")).put("events", -1),
            root ->
                ((ObjectNode) profile(root).get("allocations").get("subject"))
                    .put("observedWeightedBytes", -1),
            root -> ((ObjectNode) profile(root).get("monitors").get("subject")).put("p50Nanos", 2),
            root -> ((ObjectNode) profile(root).get("parks").get("subject")).put("p99Nanos", 0),
            root ->
                ((ObjectNode) profile(root).get("timings").get("subject")).put("totalNanos", -1),
            root -> ((ObjectNode) profile(root).get("counters")).put("forged", -1),
            root -> ((ObjectNode) root.get("activeCollectionLimits")).put("queueCapacity", -1),
            root -> ((ObjectNode) root.get("eventSettings")).put("key", "x".repeat(4097))))
      reject(corrupt);
  }

  @Test
  void cardinalityAndTreeDepthRemainBoundedForUntrustedReports() throws Exception {
    for (String name :
        List.of("methods", "allocations", "monitors", "parks", "timings", "windows")) {
      int maximum =
          switch (name) {
            case "methods", "allocations" -> 10000;
            case "windows" -> 900;
            case "timings" -> 128;
            default -> 64;
          };
      reject(
          root -> {
            ObjectNode values = (ObjectNode) profile(root).get(name);
            JsonNode value = values.elements().next().deepCopy();
            values.removeAll();
            for (int i = 0; i <= maximum; i++) values.set(Integer.toString(i), value);
          });
    }
    for (String name :
        List.of(
            "eventSettings",
            "lossCounters",
            "aggregationLimits",
            "activeCollectionLimits",
            "units",
            "supportedCapabilities")) {
      int maximum =
          switch (name) {
            case "eventSettings", "lossCounters" -> 128;
            case "supportedCapabilities" -> 64;
            default -> 32;
          };
      reject(
          root -> {
            ObjectNode values = (ObjectNode) root.get(name);
            for (int i = 0; i <= maximum; i++) values.put("k" + i, 1);
          });
    }
    reject(
        root -> {
          ObjectNode node = (ObjectNode) profile(root).get("flame");
          for (int i = 0; i < 129; i++) {
            node = node.putArray("children").addObject();
            node.put("frame", "depth");
            node.put("inclusive", 1);
            node.put("self", 0);
            node.putArray("children");
          }
        });
    reject(
        root -> {
          ObjectNode flame = (ObjectNode) profile(root).get("flame");
          flame.put("inclusive", 10001);
          profile(root).put("sampleCount", 10001).put("eventCount", 10001);
          root.put("sampleCount", 10001);
          ArrayNode children = flame.putArray("children");
          for (int i = 0; i < 10001; i++)
            children
                .addObject()
                .put("frame", "f" + i)
                .put("inclusive", 1)
                .put("self", 1)
                .putArray("children");
        });
  }

  @Test
  void parserRejectsDuplicateFieldsOversizedFilesAndUnsupportedNesting() throws Exception {
    Path input = directory.resolve("input.json");
    String valid = JSON.writeValueAsString(valid());
    Files.writeString(
        input, valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"));
    assertThrows(IOException.class, () -> ReportWriter.read(input));
    try (var file = new java.io.RandomAccessFile(input.toFile(), "rw")) {
      file.setLength(32L * 1024 * 1024 + 1);
    }
    assertThrows(IOException.class, () -> ReportWriter.read(input));
    reject(
        root -> {
          ObjectNode node = (ObjectNode) profile(root).get("flame");
          for (int i = 0; i < 160; i++) {
            node = node.putArray("children").addObject();
            node.put("frame", "deep").put("inclusive", 1).put("self", 0);
            node.putArray("children");
          }
        });
    Path output = directory.resolve("valid.json");
    ReportWriter.json(output, valid());
    assertEquals(valid(), ReportWriter.read(output));
  }

  @Test
  void maximumFlameDepthAndNodeCountAreAcceptedWithoutLosingTheirDenominators() throws Exception {
    ObjectNode deep = JSON.valueToTree(valid());
    ObjectNode node = (ObjectNode) profile(deep).get("flame");
    for (int i = 0; i < 128; i++) {
      node = node.putArray("children").addObject();
      node.put("frame", "depth").put("inclusive", 1).put("self", 0);
      node.putArray("children");
    }
    node.put("self", 1);
    Path input = directory.resolve("maximum.json");
    Files.writeString(input, JSON.writeValueAsString(deep));
    assertEquals(1, ReportWriter.read(input).sampleCount());
    ObjectNode wide = JSON.valueToTree(valid());
    ObjectNode flame = (ObjectNode) profile(wide).get("flame");
    flame.put("inclusive", 10000);
    profile(wide).put("sampleCount", 10000).put("eventCount", 10000);
    wide.put("sampleCount", 10000);
    ArrayNode children = flame.putArray("children");
    for (int i = 0; i < 10000; i++)
      children
          .addObject()
          .put("frame", "f" + i)
          .put("inclusive", 1)
          .put("self", 1)
          .putArray("children");
    Files.writeString(input, JSON.writeValueAsString(wide));
    assertEquals(10000, ReportWriter.read(input).profile().flame().children().size());
  }

  private void reject(Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode root = JSON.valueToTree(valid());
    mutation.accept(root);
    Path input = directory.resolve("invalid.json");
    Files.writeString(input, JSON.writeValueAsString(root));
    assertThrows(IOException.class, () -> ReportWriter.read(input));
  }

  private static ObjectNode profile(ObjectNode root) {
    return (ObjectNode) root.get("profile");
  }
}
