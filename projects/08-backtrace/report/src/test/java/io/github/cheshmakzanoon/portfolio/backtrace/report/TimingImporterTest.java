package io.github.cheshmakzanoon.portfolio.backtrace.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimingImporterTest {
  @TempDir Path directory;

  void fixture(String rows) throws Exception {
    Files.writeString(directory.resolve("methods.tsv"), "methodId\tidentity\n0\ta.method()V\n");
    Files.writeString(
        directory.resolve("timings.tsv"),
        "methodId\tstartedMonotonicNanos\tdurationNanos\texceptional\n" + rows);
  }

  String completeStatus(long observed, long omitted) {
    return "statusVersion=2\ncollectionHealthy=true\nqueueDrops=0\nshutdownDiscarded=0\nincomplete=false\nprobeFailures=0\nmethodOverflow=0\ntimingHistogramOverflow=0\ndurationClipped=0\nobservedTimingEvents="
        + observed
        + "\nacceptedTimingReservations="
        + observed
        + "\nproducerQuiesced=true\nunfinishedProbeExits=0\nrawTimingRowsOmitted="
        + omitted
        + "\nactiveMethodIdLimit=100\nactiveQueueCapacity=2\nskips={}\nstatusComplete=true\n";
  }

  @Test
  void distinctMethodIdsCannotOverwriteTheSameReportedIdentity() throws Exception {
    fixture("0\t1\t100\tfalse\n1\t2\t200\tfalse\n");
    Files.writeString(
        directory.resolve("methods.tsv"), "methodId\tidentity\n0\ta.method()V\n1\ta.method()V\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
    Files.writeString(
        directory.resolve("methods.tsv"), "methodId\tidentity\n0\ta.method()V\n1\ta.other()V\n");
    var valid = new TimingImporter().read(directory);
    assertEquals(2, valid.timings().size());
    assertEquals(2, valid.exits());
    assertEquals(
        300,
        valid.timings().values().stream().mapToLong(ContentionSummary.Timing::totalNanos).sum());
  }

  @Test
  void rowLimitStopsAnUnterminatedInputBeforeReadingItsRemainder() throws Exception {
    java.io.Reader endless =
        new java.io.Reader() {
          private int consumed;

          @Override
          public int read(char[] characters, int offset, int length) {
            if (++consumed > 512) throw new AssertionError("reader consumed past the row budget");
            characters[offset] = 'x';
            return 1;
          }

          @Override
          public void close() {}
        };
    try (var reader = new java.io.BufferedReader(endless, 1)) {
      assertThrows(java.io.IOException.class, () -> TimingImporter.readBoundedLine(reader, 128));
    }
    try (var reader =
        new java.io.BufferedReader(new java.io.StringReader("abc\r\ndef\rghi\nlast"))) {
      assertEquals("abc", TimingImporter.readBoundedLine(reader, 3));
      assertEquals("def", TimingImporter.readBoundedLine(reader, 3));
      assertEquals("ghi", TimingImporter.readBoundedLine(reader, 3));
      assertEquals("last", TimingImporter.readBoundedLine(reader, 4));
      assertNull(TimingImporter.readBoundedLine(reader, 4));
    }
  }

  @Test
  void primitiveTimingReplayPreservesDistributionsAndLosses() throws Exception {
    fixture("0\t123\t100\tfalse\n0\t234\t200\ttrue\n1\t345\t300\tfalse\n");
    Files.writeString(
        directory.resolve("observer-status.txt"), "queueDrops=9\nprobeFailures=1\nskips={}\n");
    var importer = new TimingImporter();
    var r = importer.read(directory);
    assertEquals(2, r.exits());
    assertEquals(1, r.exceptionalExits());
    assertEquals(1, r.unknownMethodIds());
    assertEquals(300, r.timings().get("a.method()V").totalNanos());
    assertEquals(9, r.queueDrops());
    assertEquals(1, r.probeFailures());
    var merged = importer.merge(new ProfileAccumulator(1, 1, 1).snapshot(), r);
    assertEquals(r.timings(), merged.timings());
    assertEquals(1, merged.counters().get("probeFailures"));
    assertEquals(0, merged.sampleCount());
  }

  @Test
  void malformedTimingDataIsRejected() throws Exception {
    fixture("bad\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
    fixture("0\t1\t-1\tfalse\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
    fixture("0\t1\t1\tmaybe\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
    fixture("bad\t1\t1\ttrue\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
    Files.writeString(directory.resolve("methods.tsv"), "bad header\n");
    assertThrows(java.io.IOException.class, () -> new TimingImporter().read(directory));
  }

  @Test
  void absentAndTruncatedStatusCannotLookHealthy() throws Exception {
    fixture("0\t1\t100\tfalse\n");
    var importer = new TimingImporter();
    var unknown = importer.read(directory);
    assertFalse(unknown.statusPresent());
    assertTrue(unknown.incomplete());
    assertFalse(unknown.collectionHealthy());
    Files.writeString(
        directory.resolve("observer-status.txt"),
        "collectionHealthy=false\nshutdownDiscarded=17\nincomplete=true\nqueueDrops=0\nprobeFailures=0\n");
    var failed = importer.read(directory);
    assertEquals(17, failed.shutdownDiscarded());
    assertTrue(failed.incomplete());
    var merged = importer.merge(new ProfileAccumulator(1, 1, 1).snapshot(), failed);
    assertEquals(17, merged.counters().get("timingShutdownDiscarded"));
    assertEquals(1, merged.counters().get("timingIncomplete"));
  }

  @Test
  void completeHistogramSummarySupersedesBoundedRawPrefix() throws Exception {
    fixture("0\t1\t100\tfalse\n");
    Files.writeString(
        directory.resolve("timing-summary.tsv"),
        "methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos\n0\t100002\t1\t30000300\t300\t301\t301\n");
    Files.writeString(directory.resolve("observer-status.txt"), completeStatus(100002, 90002));
    var result = new TimingImporter().read(directory);
    assertEquals(100002, result.exits());
    assertEquals(90002, result.rawRowsOmitted());
    assertFalse(result.incomplete());
    assertEquals(2, result.activeLimits().get("queueCapacity"));
    assertEquals(100, result.activeLimits().get("methodIds"));
    assertEquals(301, result.timings().get("a.method()V").p99Nanos());
  }

  @Test
  void missingOrTruncatedSummaryCannotTurnRawPrefixIntoCompleteDistribution() throws Exception {
    fixture("0\t1\t100\tfalse\n");
    Files.writeString(directory.resolve("observer-status.txt"), completeStatus(100002, 90002));
    var importer = new TimingImporter();
    var missing = importer.read(directory);
    assertTrue(missing.incomplete());
    assertEquals(1, missing.exits());
    assertEquals(100002, missing.producerObservedEvents());
    assertEquals(100001, missing.unaccountedEvents());
    Files.writeString(
        directory.resolve("timing-summary.tsv"),
        "methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos\n0\t2\t0\t200\t100\t100\t100\n");
    var truncated = importer.read(directory);
    assertTrue(truncated.incomplete());
    assertEquals(100000, truncated.unaccountedEvents());
    Files.writeString(directory.resolve("observer-status.txt"), completeStatus(1, 0));
    assertThrows(java.io.IOException.class, () -> importer.read(directory));
  }

  @Test
  void healthyTruncatedLegacyAndDuplicateStatusNeverLookComplete() throws Exception {
    fixture("0\t1\t100\tfalse\n");
    var importer = new TimingImporter();
    for (String text :
        java.util.List.of(
            "collectionHealthy=true\nincomplete=false\n",
            "statusVersion=2\ncollectionHealthy=true\nincomplete=false\nstatusComplete=true\n",
            completeStatus(1, 0).replace("methodOverflow=0\n", ""))) {
      Files.writeString(directory.resolve("observer-status.txt"), text);
      var r = importer.read(directory);
      assertFalse(r.statusComplete());
      assertTrue(r.incomplete());
    }
    Files.writeString(directory.resolve("observer-status.txt"), completeStatus(1, 0));
    assertFalse(importer.read(directory).incomplete());
    for (String duplicate :
        java.util.List.of(
            "methodOverflow=0\n",
            "collectionHealthy=true\n",
            "statusComplete=true\n",
            "queueDrops=0\n")) {
      Files.writeString(directory.resolve("observer-status.txt"), completeStatus(1, 0) + duplicate);
      assertThrows(java.io.IOException.class, () -> importer.read(directory));
    }
  }

  @Test
  void selectionOverflowAndBoundedReasonsSurviveReportRoundtrip() throws Exception {
    fixture("0\t1\t100\tfalse\n");
    Files.writeString(
        directory.resolve("observer-status.txt"),
        "collectionHealthy=true\nincomplete=false\nmethodOverflow=1\nskips={methodCeiling=1, outsideAllowlist=17}\nqueueDrops=0\n");
    var importer = new TimingImporter();
    var result = importer.read(directory);
    assertTrue(result.incomplete());
    assertEquals(1, result.methodSelectionOverflow());
    assertEquals(17, result.selectionSkips().get("outsideAllowlist"));
    var p = importer.merge(new ProfileAccumulator(1, 1, 1).snapshot(), result);
    var doc =
        ReportDocument.create(
            "selection-fixture", p, java.util.Map.of(), java.util.Map.of(), p.counters());
    Path json = directory.resolve("report.json"), html = directory.resolve("report.html");
    ReportWriter.json(json, doc);
    ReportWriter.html(html, doc);
    assertEquals(1, ReportWriter.read(json).lossCounters().get("timingMethodSelectionOverflow"));
    assertTrue(Files.readString(html).contains("timingSelectionSkip.methodCeiling"));
    assertTrue(ReportWriter.read(json).missingEventStatus().contains("incomplete"));
    for (String bad :
        java.util.List.of(
            "{methodCeiling=-1}",
            "{methodCeiling=1, methodCeiling=2}",
            "{<script>=1}",
            "x".repeat(4097))) {
      Files.writeString(directory.resolve("observer-status.txt"), "skips=" + bad + "\n");
      assertThrows(java.io.IOException.class, () -> importer.read(directory));
    }
    Files.writeString(directory.resolve("observer-status.txt"), "methodOverflow=-1\n");
    assertThrows(java.io.IOException.class, () -> importer.read(directory));
  }
}
