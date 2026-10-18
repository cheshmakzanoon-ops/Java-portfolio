package io.github.cheshmakzanoon.portfolio.threadlens.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
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
    Files.writeString(
        directory.resolve("observer-status.txt"),
        "collectionHealthy=true\nincomplete=false\nqueueDrops=0\nshutdownDiscarded=0\nprobeFailures=0\nrawTimingRowsOmitted=90002\nactiveMethodIdLimit=100\nactiveQueueCapacity=2\n");
    var result = new TimingImporter().read(directory);
    assertEquals(100002, result.exits());
    assertEquals(90002, result.rawRowsOmitted());
    assertFalse(result.incomplete());
    assertEquals(2, result.activeLimits().get("queueCapacity"));
    assertEquals(100, result.activeLimits().get("methodIds"));
    assertEquals(301, result.timings().get("a.method()V").p99Nanos());
  }
}
