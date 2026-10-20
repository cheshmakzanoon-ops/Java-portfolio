package io.github.cheshmakzanoon.portfolio.threadlens.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {
  @TempDir Path directory;

  @Test
  void representativeOfflineReportsExposeEveryNavigationTargetAndSafeText() throws Exception {
    ProfileAccumulator accumulator = new ProfileAccumulator(100, 1_000_000_000L, 20);
    for (int i = 0; i < 30; i++)
      accumulator.accept(
          new NormalizedEvent(
              NormalizedEvent.Kind.SAMPLE,
              i * 100_000_000L,
              1,
              "sample",
              List.of(
                  "safe.application.Root.run()V",
                  "safe.application." + "LongMethod".repeat(20) + "()V",
                  "<img src=x onerror=alert('unsafe')>")));
    for (var kind :
        List.of(
            NormalizedEvent.Kind.ALLOCATION,
            NormalizedEvent.Kind.MONITOR,
            NormalizedEvent.Kind.PARK,
            NormalizedEvent.Kind.TIMING))
      accumulator.accept(
          new NormalizedEvent(
              kind, 100_000_000, 123456789, "safe.application.Work.handle()V", List.of()));
    ReportDocument document =
        ReportDocument.create(
            "offline-browser-fixture",
            accumulator.snapshot(),
            Map.of("jdk.ExecutionSample#period", "20 ms"),
            Map.of(),
            Map.of("queueDrops", 0L));
    Path output = Path.of("target", "ui-fixture");
    Files.createDirectories(output);
    ReportWriter.json(output.resolve("report.json"), document);
    ReportWriter.html(output.resolve("report.html"), document);
    ReportWriter.comparison(output.resolve("comparison.html"), document, document);
    for (String file : List.of("report.html", "comparison.html")) {
      String html = Files.readString(output.resolve(file));
      assertFalse(html.contains("<img"));
      assertFalse(html.contains("<script"));
      assertTrue(html.contains("&lt;img"));
      assertTrue(html.contains("class=\"table-scroll\""));
      var links = java.util.regex.Pattern.compile("href=\"#([^\"]+)\"").matcher(html);
      int count = 0;
      while (links.find()) {
        assertTrue(html.contains("id=\"" + links.group(1) + "\""));
        count++;
      }
      assertTrue(count >= 5);
    }
  }

  @Test
  void hostileIdentifiersAreEscapedAndJsonRoundTripsDeterministically() throws Exception {
    var a = new ProfileAccumulator(20, 1_000_000_000, 20);
    a.accept(
        new NormalizedEvent(
            NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of("<script>alert('x')</script>")));
    var doc =
        ReportDocument.create(
            "<img src=x onerror=alert(1)>",
            a.snapshot(),
            Map.of("configuration", "<&\"'"),
            Map.of(),
            Map.of());
    Path json = directory.resolve("report.json"), html = directory.resolve("report.html");
    ReportWriter.json(json, doc);
    String first = Files.readString(json);
    ReportWriter.json(json, doc);
    assertEquals(first, Files.readString(json));
    assertEquals(doc, ReportWriter.read(json));
    ReportWriter.html(html, doc);
    String page = Files.readString(html);
    assertFalse(page.contains("<script>"));
    assertFalse(page.contains("<img"));
    assertTrue(page.contains("&lt;script&gt;"));
    assertTrue(page.contains("Content-Security-Policy"));
    assertFalse(page.contains("https://"));
    assertFalse(page.contains("fixture secret"));
    ReportWriter.comparison(directory.resolve("compare.html"), doc, doc);
    assertTrue(Files.readString(directory.resolve("compare.html")).contains("percentage points"));
  }

  @Test
  void differingSettingsCannotProduceMisleadingComparison() {
    var a = new ProfileAccumulator(1, 1, 1).snapshot();
    var base = ReportDocument.create("a", a, Map.of("period", "20ms"), Map.of(), Map.of());
    var candidate = ReportDocument.create("b", a, Map.of("period", "10ms"), Map.of(), Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> ReportWriter.comparison(directory.resolve("out.html"), base, candidate));
  }

  @Test
  void configuredLimitsAreDistinctFromAbsoluteCeilings() throws Exception {
    var profile = new ProfileAccumulator(1, 1, 1).snapshot();
    var doc =
        ReportDocument.create(
            "small-config",
            profile,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("queueCapacity", 2, "methodIds", 100));
    Path file = directory.resolve("configured.json");
    ReportWriter.json(file, doc);
    var replay = ReportWriter.read(file);
    assertEquals(2, replay.activeCollectionLimits().get("queueCapacity"));
    assertEquals(100, replay.activeCollectionLimits().get("methodIds"));
    assertEquals(65536, replay.aggregationLimits().get("queueCapacityCeiling"));
  }

  @Test
  void unknownProducerSettingsCannotEstablishComparability() {
    var p = new ProfileAccumulator(1, 1, 1).snapshot();
    var report =
        ReportDocument.create(
            "unknown", p, Map.of("producerSettings", "unknown"), Map.of(), Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> ReportWriter.comparison(directory.resolve("misleading.html"), report, report));
  }
}
