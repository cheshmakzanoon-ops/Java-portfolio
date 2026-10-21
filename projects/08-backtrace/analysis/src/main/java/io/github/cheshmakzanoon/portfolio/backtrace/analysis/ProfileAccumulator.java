package io.github.cheshmakzanoon.portfolio.backtrace.analysis;

import java.util.Map;
import java.util.TreeMap;

/** Single-consumer aggregate; all cardinalities are bounded independently of recording length. */
public final class ProfileAccumulator {
  private final StackAggregator stacks;
  private final WindowAccumulator windows;
  private final AllocationSummary allocations;
  private final ContentionSummary monitors = new ContentionSummary(64),
      parks = new ContentionSummary(64),
      timings = new ContentionSummary(128);
  private long events, samples, start = Long.MAX_VALUE, end, loss;

  public ProfileAccumulator(int cardinality, long windowNanos, int windowLimit) {
    stacks = new StackAggregator(cardinality);
    windows = new WindowAccumulator(windowNanos, windowLimit);
    allocations = new AllocationSummary(cardinality);
  }

  public void accept(NormalizedEvent event) {
    events++;
    start = Math.min(start, event.epochNanos());
    end = Math.max(end, event.epochNanos());
    windows.accept(event);
    switch (event.kind()) {
      case SAMPLE -> {
        samples++;
        stacks.accept(event.stack());
      }
      case ALLOCATION -> allocations.accept(event.subject(), event.value());
      case MONITOR -> monitors.accept(event.subject(), event.value());
      case PARK -> parks.accept(event.subject(), event.value());
      case TIMING -> timings.accept(event.subject(), event.value());
      case LOSS -> {
        loss = Math.addExact(loss, event.value());
        windows.markIncomplete();
      }
      case GC -> {}
    }
  }

  public void markIncomplete() {
    windows.markIncomplete();
  }

  public Profile snapshot() {
    if (stacks.overflow() > 0
        || stacks.missingStacks() > 0
        || windows.overflow() > 0
        || allocations.overflow() > 0
        || monitors.overflow() > 0
        || parks.overflow() > 0
        || timings.overflow() > 0
        || monitors.clipped() > 0
        || parks.clipped() > 0
        || timings.clipped() > 0) windows.markIncomplete();
    Map<String, Long> counters = new TreeMap<>();
    counters.put("jfrDataLossBytes", loss);
    counters.put("stackOverflow", stacks.overflow());
    counters.put("missingSampleStacks", stacks.missingStacks());
    counters.put("windowOverflow", windows.overflow());
    counters.put("allocationOverflow", allocations.overflow());
    counters.put("contentionOverflow", monitors.overflow() + parks.overflow());
    counters.put("timingOverflow", timings.overflow());
    counters.put("durationClipped", monitors.clipped() + parks.clipped() + timings.clipped());
    return new Profile(
        events,
        samples,
        start == Long.MAX_VALUE ? 0 : start,
        end,
        stacks.snapshot(),
        stacks.methods(),
        windows.snapshot(),
        allocations.snapshot(),
        monitors.snapshot(),
        parks.snapshot(),
        timings.snapshot(),
        counters);
  }
}
