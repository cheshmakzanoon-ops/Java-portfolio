package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingBuffer;
import java.nio.file.*;
import org.HdrHistogram.HistogramLogReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimingStatisticsTest {
  @TempDir Path directory;

  @Test
  void boundedHistogramConservesAcceptedEventsAndHasRecomputableRawDistribution() throws Exception {
    long before = System.currentTimeMillis();
    var s = new TimingStatistics();
    s.accept(new TimingBuffer.Timing(1, 1, 100, false));
    s.accept(new TimingBuffer.Timing(1, 2, 200, true));
    for (int i = 0; i < 100_000; i++) s.accept(new TimingBuffer.Timing(1, i, 300, false));
    s.write(directory);
    String rows = Files.readString(directory.resolve("timing-summary.tsv"));
    assertEquals(
        "methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos",
        rows.lines().findFirst().orElseThrow());
    assertTrue(rows.contains("1\t100002\t1\t30000300"), rows);
    assertEquals(100002, s.observed());
    assertTrue(Files.size(directory.resolve("timing-summary.tsv")) < 1000);
    String raw = Files.readString(directory.resolve("timing-histograms.hlog"));
    assertTrue(raw.contains("#[Histogram log format version 1.3]"));
    assertTrue(raw.contains("accepted events for retained subjects aggregated"));
    assertTrue(
        raw.contains(
            "\"StartTimestamp\",\"Interval_Length\",\"Interval_Max\",\"Interval_Compressed_Histogram\""));
    try (var log = new HistogramLogReader(directory.resolve("timing-histograms.hlog").toFile())) {
      var histogram = (org.HdrHistogram.Histogram) log.nextIntervalHistogram();
      assertEquals(100002, histogram.getTotalCount());
      assertTrue(histogram.getMaxValueAsDouble() >= 300);
      assertEquals("1", histogram.getTag());
      assertTrue(histogram.getStartTimeStamp() >= before - 1);
      assertTrue(histogram.getEndTimeStamp() >= histogram.getStartTimeStamp());
      assertTrue(histogram.getEndTimeStamp() <= System.currentTimeMillis() + 1);
    }
  }

  @Test
  void exactMaximumDurationIsNotClippedAndLargerDurationIs() {
    var s = new TimingStatistics();
    s.accept(1, 1, 3_600_000_000_000L, false);
    assertEquals(0, s.clipped());
    s.accept(1, 1, 3_600_000_000_001L, true);
    assertEquals(1, s.clipped());
    assertEquals(2, s.observed());
  }

  @Test
  void highCardinalityAndHugeDurationsAreExplicitlyBounded() {
    var s = new TimingStatistics();
    for (int i = 0; i < 129; i++)
      s.accept(new TimingBuffer.Timing(i, i, 3_600_000_000_001L, false));
    assertEquals(1, s.overflow());
    assertEquals(128, s.clipped());
  }

  @Test
  void primitiveAndRecordAggregationAgreeAcrossCacheHitsMissesAndOverflow() throws Exception {
    var primitive = new TimingStatistics();
    var records = new TimingStatistics();
    int[] ids = {0, 0, 1, 0, 1, 1, 127, 127};
    for (int id : ids) {
      primitive.accept(id, 1, 100 + id, (id & 1) != 0);
      records.accept(new TimingBuffer.Timing(id, 1, 100 + id, (id & 1) != 0));
    }
    for (int i = 0; i < 130; i++) {
      primitive.accept(i, 1, 300, false);
      records.accept(new TimingBuffer.Timing(i, 1, 300, false));
    }
    primitive.accept(129, 1, 300, false);
    records.accept(new TimingBuffer.Timing(129, 1, 300, false));
    Path a = Files.createDirectory(directory.resolve("a")),
        b = Files.createDirectory(directory.resolve("b"));
    primitive.write(a);
    records.write(b);
    assertEquals(
        Files.readString(a.resolve("timing-summary.tsv")),
        Files.readString(b.resolve("timing-summary.tsv")));
    assertEquals(3, primitive.overflow());
    assertEquals(139, primitive.observed());
    assertEquals(records.observed(), primitive.observed());
  }
}
