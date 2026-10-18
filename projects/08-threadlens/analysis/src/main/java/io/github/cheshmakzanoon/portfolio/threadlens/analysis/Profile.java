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
    Map<String, Long> counters) {}
