package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.Map;
import java.util.TreeMap;
import org.HdrHistogram.Histogram;

public final class ContentionSummary {
  public record Timing(long events, long totalNanos, long p50Nanos, long p95Nanos, long p99Nanos) {}

  private static final class Entry {
    long count, total;
    final Histogram histogram = new Histogram(1, 3_600_000_000_000L, 2);
  }

  private final int limit;
  private final TreeMap<String, Entry> entries = new TreeMap<>();
  private long overflow, clipped;

  public ContentionSummary(int limit) {
    if (limit < 1 || limit > 256)
      throw new IllegalArgumentException("histogram cardinality 1..256");
    this.limit = limit;
  }

  public void accept(String subject, long nanos) {
    if (nanos < 0) throw new IllegalArgumentException("negative duration");
    Entry e = entries.get(subject);
    if (e == null) {
      if (entries.size() == limit) {
        overflow++;
        return;
      }
      e = new Entry();
      entries.put(subject, e);
    }
    e.count++;
    e.total = Math.addExact(e.total, nanos);
    if (nanos > 3_600_000_000_000L) {
      nanos = 3_600_000_000_000L;
      clipped++;
    }
    e.histogram.recordValue(nanos);
  }

  public Map<String, Timing> snapshot() {
    Map<String, Timing> result = new TreeMap<>();
    entries.forEach(
        (k, e) ->
            result.put(
                k,
                new Timing(
                    e.count,
                    e.total,
                    e.histogram.getValueAtPercentile(50),
                    e.histogram.getValueAtPercentile(95),
                    e.histogram.getValueAtPercentile(99))));
    return result;
  }

  public long overflow() {
    return overflow;
  }

  public long clipped() {
    return clipped;
  }
}
