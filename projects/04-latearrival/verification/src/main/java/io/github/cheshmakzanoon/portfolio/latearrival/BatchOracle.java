package io.github.cheshmakzanoon.portfolio.latearrival;

import java.util.*;

/**
 * Deliberately non-incremental reference: stores each accepted contribution and scans memberships.
 */
public final class BatchOracle {
  public record Identity(String tenant, String sensor, String type, long start, long end) {}

  public record Total(long count, long sum, long minimum, long maximum) {}

  private final Map<Identity, List<Long>> values = new HashMap<>();
  private final Map<String, String> seen = new HashMap<>();
  private long duplicateCount;
  private long lateTumbling;

  private final long[] maximum;
  private final long[] activity;
  private final boolean[] idle;
  private long referenceWatermark = Long.MIN_VALUE;

  public BatchOracle() {
    this(1, 0);
  }

  public BatchOracle(int partitions, long now) {
    maximum = new long[partitions];
    Arrays.fill(maximum, Long.MIN_VALUE);
    activity = new long[partitions];
    Arrays.fill(activity, now);
    idle = new boolean[partitions];
  }

  public long watermark() {
    return referenceWatermark;
  }

  /** Derives all source progress independently; never reads production engine state. */
  public void record(Engine.Event e, int partition, long now) {
    String id = e.tenantId() + ":" + e.eventId();
    String old = seen.get(id);
    if (e.progress()) {
      if (old == null) seen.put(id, e.canonical());
      else if (old.equals(e.canonical())) duplicateCount++;
    } else measurement(e, referenceWatermark);
    if (old == null) {
      maximum[partition] = Math.max(maximum[partition], e.eventTimeEpochMillis());
      activity[partition] = now;
      idle[partition] = false;
      advanceReference();
    }
  }

  public void observe(int partition, boolean paused, long lag, int queued, long now) {
    if (paused || lag != 0 || queued != 0) {
      idle[partition] = false;
      activity[partition] = now;
    } else if (now - activity[partition] >= 30000) idle[partition] = true;
    advanceReference();
  }

  public void restart(long now) {
    Arrays.fill(idle, false);
    Arrays.fill(activity, now);
  }

  private void advanceReference() {
    java.util.OptionalLong activeMinimum =
        java.util.stream.IntStream.range(0, maximum.length)
            .filter(p -> !idle[p])
            .mapToLong(p -> maximum[p] == Long.MIN_VALUE ? Long.MIN_VALUE : maximum[p] - 5000)
            .min();
    if (activeMinimum.isPresent() && activeMinimum.getAsLong() > referenceWatermark)
      referenceWatermark = activeMinimum.getAsLong();
  }

  public void measurement(Engine.Event e, long arrivalWatermark) {
    if (e.progress()) return;
    String identity = e.tenantId() + ":" + e.eventId();
    String old = seen.putIfAbsent(identity, e.canonical());
    if (old != null) {
      if (old.equals(e.canonical())) duplicateCount++;
      return;
    }
    long timestamp = e.eventTimeEpochMillis();
    // Enumerate the timeline rather than call Engine.assigned or merge its panes.
    long nearest = (timestamp / 60_000) * 60_000;
    for (String type : List.of("TUMBLING", "SLIDING")) {
      long width = type.equals("TUMBLING") ? 60_000 : 300_000;
      for (long start = nearest - 300_000; start <= nearest; start += 60_000) {
        if (timestamp < start || timestamp >= start + width) continue;
        if (arrivalWatermark >= start + width + 30_000) {
          if (type.equals("TUMBLING")) lateTumbling++;
          continue;
        }
        values
            .computeIfAbsent(
                new Identity(e.tenantId(), e.sensorId(), type, start, start + width),
                k -> new ArrayList<>())
            .add(e.valueMilliUnits());
      }
    }
  }

  public Map<Identity, Total> totals() {
    Map<Identity, Total> result = new HashMap<>();
    values.forEach(
        (k, v) -> {
          long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
          for (long x : v) {
            sum = Math.addExact(sum, x);
            min = Math.min(min, x);
            max = Math.max(max, x);
          }
          result.put(k, new Total(v.size(), sum, min, max));
        });
    return result;
  }

  public long duplicates() {
    return duplicateCount;
  }

  public long lateTumbling() {
    return lateTumbling;
  }
}
