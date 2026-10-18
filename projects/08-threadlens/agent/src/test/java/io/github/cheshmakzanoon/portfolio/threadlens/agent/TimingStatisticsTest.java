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
    var s = new TimingStatistics();
    s.accept(new TimingBuffer.Timing(1, 1, 100, false));
    s.accept(new TimingBuffer.Timing(1, 2, 200, true));
    for (int i = 0; i < 100_000; i++) s.accept(new TimingBuffer.Timing(1, i, 300, false));
    s.write(directory);
    String rows = Files.readString(directory.resolve("timing-summary.tsv"));
    assertTrue(rows.contains("1\t100002\t1\t30000300"), rows);
    assertEquals(100002, s.observed());
    assertTrue(Files.size(directory.resolve("timing-summary.tsv")) < 1000);
    try (var log = new HistogramLogReader(directory.resolve("timing-histograms.hlog").toFile())) {
      var histogram = (org.HdrHistogram.Histogram) log.nextIntervalHistogram();
      assertEquals(100002, histogram.getTotalCount());
      assertTrue(histogram.getMaxValueAsDouble() >= 300);
    }
  }

  @Test
  void highCardinalityAndHugeDurationsAreExplicitlyBounded() {
    var s = new TimingStatistics();
    for (int i = 0; i < 129; i++)
      s.accept(new TimingBuffer.Timing(i, i, 3_600_000_000_001L, false));
    assertEquals(1, s.overflow());
    assertEquals(128, s.clipped());
  }
}
