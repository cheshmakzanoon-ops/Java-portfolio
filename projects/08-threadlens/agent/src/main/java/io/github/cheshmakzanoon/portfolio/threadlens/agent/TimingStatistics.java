package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.TreeMap;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

/** Single-consumer bounded distributions, independent of workload invocation count. */
public final class TimingStatistics {
  public static final int SUBJECT_LIMIT = 128, RAW_ROWS = 10_000;
  private static final long MAX_NANOS = 3_600_000_000_000L;

  private static final class Entry {
    final Histogram histogram = new Histogram(1, MAX_NANOS, 2);
    long events, exceptions, total;
  }

  private final TreeMap<Integer, Entry> methods = new TreeMap<>();
  private final long startedMillis = System.currentTimeMillis();
  private long overflow, clipped, observed;

  public void accept(TimingBuffer.Timing timing) {
    observed++;
    Entry entry = methods.get(timing.methodId());
    if (entry == null) {
      if (methods.size() == SUBJECT_LIMIT) {
        overflow++;
        return;
      }
      entry = new Entry();
      methods.put(timing.methodId(), entry);
    }
    entry.events++;
    if (timing.exceptional()) entry.exceptions++;
    entry.total = Math.addExact(entry.total, timing.durationNanos());
    long duration = timing.durationNanos();
    if (duration > MAX_NANOS) {
      duration = MAX_NANOS;
      clipped++;
    }
    entry.histogram.recordValue(duration);
  }

  public void write(Path directory) throws IOException {
    try (var summary =
            Files.newBufferedWriter(
                directory.resolve("timing-summary.tsv"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        var output =
            Files.newOutputStream(
                directory.resolve("timing-histograms.hlog"), StandardOpenOption.CREATE_NEW)) {
      var log = new HistogramLogWriter(output);
      log.outputLogFormatVersion();
      log.outputComment(
          "ThreadLens observed selected method wall nanoseconds; tag=methodId; accepted events for retained subjects aggregated; overflow is counted; raw TSV is only a bounded prefix");
      log.outputLegend();
      summary.write(
          "methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos\n");
      long ended = System.currentTimeMillis();
      for (var item : methods.entrySet()) {
        Entry e = item.getValue();
        e.histogram.setTag(Integer.toString(item.getKey()));
        e.histogram.setStartTimeStamp(startedMillis);
        e.histogram.setEndTimeStamp(ended);
        log.outputIntervalHistogram(e.histogram);
        summary.write(
            item.getKey()
                + "\t"
                + e.events
                + "\t"
                + e.exceptions
                + "\t"
                + e.total
                + "\t"
                + e.histogram.getValueAtPercentile(50)
                + "\t"
                + e.histogram.getValueAtPercentile(95)
                + "\t"
                + e.histogram.getValueAtPercentile(99)
                + "\n");
      }
    }
  }

  public long observed() {
    return observed;
  }

  public long overflow() {
    return overflow;
  }

  public long clipped() {
    return clipped;
  }
}
