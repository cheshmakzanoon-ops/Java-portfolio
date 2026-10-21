package io.github.cheshmakzanoon.portfolio.backtrace.analysis;

import java.util.Map;
import java.util.TreeMap;

/** Event-time buckets [start,start+width); bounded by distinct windows rather than wall clock. */
public final class WindowAccumulator {
  public record Window(
      long startNanos,
      long endNanos,
      long samples,
      long allocatedBytes,
      long gcNanos,
      long contentionNanos,
      long parkNanos,
      boolean incomplete) {}

  private final long width;
  private final int limit;
  private final TreeMap<Long, long[]> windows = new TreeMap<>();
  private long overflow;
  private boolean globallyIncomplete;

  public WindowAccumulator(long width, int limit) {
    if (width <= 0 || limit < 1 || limit > 10_000)
      throw new IllegalArgumentException("window bounds");
    this.width = width;
    this.limit = limit;
  }

  public void accept(NormalizedEvent event) {
    long start = event.epochNanos() / width * width;
    long[] row = windows.get(start);
    if (row == null) {
      if (windows.size() == limit) {
        overflow++;
        return;
      }
      row = new long[6];
      windows.put(start, row);
    }
    switch (event.kind()) {
      case SAMPLE -> row[0]++;
      case ALLOCATION -> row[1] = Math.addExact(row[1], event.value());
      case GC -> row[2] = Math.addExact(row[2], event.value());
      case MONITOR -> row[3] = Math.addExact(row[3], event.value());
      case PARK -> row[4] = Math.addExact(row[4], event.value());
      case LOSS -> row[5] = 1;
      case TIMING -> {}
    }
  }

  public void markIncomplete() {
    globallyIncomplete = true;
  }

  public Map<Long, Window> snapshot() {
    Map<Long, Window> result = new TreeMap<>();
    windows.forEach(
        (start, v) ->
            result.put(
                start,
                new Window(
                    start,
                    Math.addExact(start, width),
                    v[0],
                    v[1],
                    v[2],
                    v[3],
                    v[4],
                    globallyIncomplete || v[5] != 0)));
    return result;
  }

  public long overflow() {
    return overflow;
  }
}
