package io.github.cheshmakzanoon.portfolio.threadlens.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComparisonRenderingTest {
  @TempDir Path directory;

  @Test
  void allObservationKindsEscapeHostileNamesWithoutAddingActiveContent() throws Exception {
    String hostile = "<svg onload=alert('x')>\"&";
    var baseline = document("<img src=x>", hostile, 100);
    var candidate = document("candidate<script>", hostile, 200);
    Path html = directory.resolve("comparison.html");
    ReportWriter.comparison(html, baseline, candidate);
    String text = Files.readString(html);
    assertFalse(text.contains("<svg"));
    assertFalse(text.contains("<img"));
    assertFalse(text.contains("<script"));
    assertFalse(text.contains("https://"));
    assertTrue(text.contains("&lt;svg onload=alert(&#39;x&#39;)&gt;&quot;&amp;"));
    assertTrue(text.contains("&lt;img src=x&gt;"));
    assertTrue(text.contains("candidate&lt;script&gt;"));
    assertTrue(text.contains("default-src 'none'"));
    for (String target :
        List.of("samples", "allocation", "contention", "parking", "timings", "limits")) {
      assertTrue(text.contains("href=\"#" + target + "\""));
      assertTrue(text.contains("id=\"" + target + "\""));
    }
    assertEquals(6, count(text, "<table>"));
    assertEquals(6, count(text, "class=\"table-scroll\""));
    assertFalse(text.contains("<th>"));
  }

  @Test
  void jsonPreservesLiteralIdentifiersNullQuantilesAndEveryTimingSource() throws Exception {
    var baseline = document("b", "before", 100);
    var candidate = document("c", "<new>&\"", 200);
    Path json = directory.resolve("comparison.json");
    ReportWriter.comparisonJson(json, baseline, candidate);
    var root = new ObjectMapper().readTree(json.toFile());
    for (String group : List.of("monitors", "parks", "selectedTimings")) {
      assertEquals(2, root.get(group).size());
      var added = root.get(group).get(0);
      assertEquals("<new>&\"", added.get("subject").asText());
      assertEquals("CANDIDATE_ONLY", added.get("presence").asText());
      assertTrue(added.get("baselineP50Nanos").isNull());
      assertTrue(added.get("baselineP95Nanos").isNull());
      assertTrue(added.get("baselineP99Nanos").isNull());
      assertTrue(added.get("p99PercentChange").isNull());
      assertTrue(added.get("totalNanosPercentChange").isNull());
      assertEquals(200, added.get("candidateTotalNanos").asLong());
    }
    assertEquals("b", root.get("baselineRecordingId").asText());
    assertEquals("c", root.get("candidateRecordingId").asText());
    assertTrue(root.get("interpretation").size() >= 7);
  }

  @Test
  void absentQuantilesAreRenderedAsUnavailableAndNeverAsAZeroLatencyImprovement() throws Exception {
    var baseline = document("b", "old", 100);
    var candidate = document("c", "new", 200);
    Path html = directory.resolve("comparison.html");
    ReportWriter.comparison(html, baseline, candidate);
    String text = Files.readString(html);
    assertTrue(text.contains("candidate only; no retained baseline observations"));
    assertTrue(text.contains("baseline only; no retained candidate observations"));
    assertTrue(text.contains("unavailable"));
    assertFalse(text.contains("NaN"));
    assertFalse(text.contains("Infinity"));
    assertTrue(text.contains("separate agent collection interval"));
    assertTrue(text.contains("not recording duration"));
  }

  @Test
  void structuredSerializationHasStableOrderingAcrossRepeatedCalls() throws Exception {
    var baseline = document("b", "work", 10);
    var candidate = document("c", "work", 20);
    Path one = directory.resolve("one.json"), two = directory.resolve("two.json");
    ReportWriter.comparisonJson(one, baseline, candidate);
    ReportWriter.comparisonJson(two, baseline, candidate);
    assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two));
    var root = new ObjectMapper().readTree(one.toFile());
    assertEquals(
        100.0, root.get("allocations").get(0).get("weightedBytesPercentChange").asDouble());
    assertEquals(
        100.0, root.get("selectedTimings").get(0).get("totalNanosPercentChange").asDouble());
  }

  private static ReportDocument document(String id, String subject, long value) {
    var accumulator = new ProfileAccumulator(10, 1_000_000_000L, 10);
    accumulator.accept(
        new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of(subject)));
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(new NormalizedEvent(kind, 1, value, subject, List.of()));
    return ReportDocument.create(
        id,
        accumulator.snapshot(),
        Map.of("jdk.ExecutionSample#period", "20 ms"),
        Map.of(),
        Map.of());
  }

  private static int count(String text, String substring) {
    int matches = 0, from = 0;
    while ((from = text.indexOf(substring, from)) >= 0) {
      matches++;
      from += substring.length();
    }
    return matches;
  }
}
