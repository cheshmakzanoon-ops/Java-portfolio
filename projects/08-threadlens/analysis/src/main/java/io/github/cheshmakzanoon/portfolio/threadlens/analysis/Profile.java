package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.Map;

public record Profile(
    long eventCount,
    long sampleCount,
    long startNanos,
    long endNanos,
    StackAggregator.Flame flame,
    Map<String, StackAggregator.MethodSamples> methods,
    Map<Long, WindowAccumulator.Window> windows,
    Map<String, AllocationSummary.Allocation> allocations,
    Map<String, ContentionSummary.Timing> monitors,
    Map<String, ContentionSummary.Timing> parks,
    Map<String, ContentionSummary.Timing> timings,
    Map<String, Long> counters) {
  public Profile {
    methods = sorted(methods);
    windows = sorted(windows);
    allocations = sorted(allocations);
    monitors = sorted(monitors);
    parks = sorted(parks);
    timings = sorted(timings);
    counters = sorted(counters);
  }

  private static <K extends Comparable<? super K>, V> Map<K, V> sorted(Map<K, V> values) {
    return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(values));
  }
}
