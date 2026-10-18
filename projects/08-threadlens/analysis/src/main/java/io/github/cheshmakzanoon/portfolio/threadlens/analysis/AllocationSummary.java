package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.Map;
import java.util.TreeMap;

public final class AllocationSummary {
  public record Allocation(long events, long observedWeightedBytes) {}

  private final int limit;
  private final TreeMap<String, long[]> values = new TreeMap<>();
  private long overflow;

  public AllocationSummary(int limit) {
    if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("allocation limit");
    this.limit = limit;
  }

  public void accept(String type, long bytes) {
    if (bytes < 0) throw new IllegalArgumentException("negative weight");
    long[] counts = values.get(type);
    if (counts == null) {
      if (values.size() == limit) {
        overflow++;
        return;
      }
      counts = new long[2];
      values.put(type, counts);
    }
    counts[0]++;
    counts[1] = Math.addExact(counts[1], bytes);
  }

  public Map<String, Allocation> snapshot() {
    Map<String, Allocation> result = new TreeMap<>();
    values.forEach((k, v) -> result.put(k, new Allocation(v[0], v[1])));
    return result;
  }

  public long overflow() {
    return overflow;
  }
}
