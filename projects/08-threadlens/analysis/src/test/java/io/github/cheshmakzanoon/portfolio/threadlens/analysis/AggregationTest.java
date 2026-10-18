package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AggregationTest {
  private NormalizedEvent event(
      NormalizedEvent.Kind k, long at, long value, String subject, String... stack) {
    return new NormalizedEvent(k, at, value, subject, List.of(stack));
  }

  @Test
  void deterministicInclusiveSelfWindowsAndWeights() {
    ProfileAccumulator a = new ProfileAccumulator(100, 100, 10),
        b = new ProfileAccumulator(100, 100, 10);
    var fixture =
        List.of(
            event(NormalizedEvent.Kind.SAMPLE, 99, 1, "sample", "root", "a"),
            event(NormalizedEvent.Kind.SAMPLE, 100, 1, "sample", "root", "b"),
            event(NormalizedEvent.Kind.ALLOCATION, 100, 400, "byte[]"),
            event(NormalizedEvent.Kind.ALLOCATION, 199, 200, "byte[]"),
            event(NormalizedEvent.Kind.GC, 150, 50, "gc"),
            event(NormalizedEvent.Kind.MONITOR, 101, 45, "lock"),
            event(NormalizedEvent.Kind.PARK, 201, 90, "park"),
            event(NormalizedEvent.Kind.TIMING, 202, 1000, "selected"),
            event(NormalizedEvent.Kind.LOSS, 203, 512, "loss"));
    fixture.forEach(
        e -> {
          a.accept(e);
          b.accept(e);
        });
    assertEquals(a.snapshot(), b.snapshot());
    var p = a.snapshot();
    assertEquals(2, p.methods().get("root").inclusive());
    assertEquals(0, p.methods().get("root").self());
    assertEquals(1, p.methods().get("a").self());
    assertEquals(600, p.allocations().get("byte[]").observedWeightedBytes());
    assertEquals(1, p.windows().get(0L).samples());
    assertEquals(1, p.windows().get(100L).samples());
    assertEquals(50, p.windows().get(100L).gcNanos());
    assertEquals(45, p.windows().get(100L).contentionNanos());
    assertEquals(90, p.windows().get(200L).parkNanos());
    assertTrue(p.windows().get(200L).incomplete());
    assertEquals(512, p.counters().get("jfrDataLossBytes"));
    assertEquals(1000, p.timings().get("selected").totalNanos());
  }

  @Test
  void boundedCardinalityAndMissingStacks() {
    var s = new StackAggregator(2);
    s.accept(List.of("a", "b"));
    s.accept(List.of("c"));
    s.accept(List.of());
    assertEquals(2, s.nodes());
    assertTrue(s.overflow() > 0);
    assertEquals(1, s.missingStacks());
    assertEquals(3, s.snapshot().inclusive());
  }

  @Test
  void recursionCountsSampleOnceButLeafIsSelf() {
    var s = new StackAggregator(10);
    s.accept(List.of("a", "a", "a"));
    assertEquals(1, s.methods().get("a").inclusive());
    assertEquals(1, s.methods().get("a").self());
    assertEquals(3, s.nodes());
  }

  @Test
  void boundsAndNegativeEventsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new StackAggregator(0));
    assertThrows(IllegalArgumentException.class, () -> new AllocationSummary(10001));
    assertThrows(IllegalArgumentException.class, () -> new WindowAccumulator(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ContentionSummary(257));
    assertThrows(
        IllegalArgumentException.class, () -> event(NormalizedEvent.Kind.SAMPLE, -1, 1, "bad"));
  }

  @Test
  void comparatorUsesProportionsAndMissingIsUnknown() {
    var a = new ProfileAccumulator(10, 100, 10);
    var b = new ProfileAccumulator(10, 100, 10);
    a.accept(event(NormalizedEvent.Kind.SAMPLE, 10, 1, "s", "hot"));
    b.accept(event(NormalizedEvent.Kind.SAMPLE, 10, 1, "s", "cold"));
    var deltas = ProfileComparator.compare(a.snapshot(), b.snapshot());
    assertEquals(2, deltas.size());
    assertEquals(-100, deltas.get(1).percentagePointDelta());
    assertNull(
        ProfileComparator.compare(new ProfileAccumulator(10, 100, 10).snapshot(), b.snapshot())
            .getFirst()
            .baselineProportion());
    var estimate =
        ProfileComparator.paired(
            new double[] {100, 100, 100, 100, 100, 100, 100},
            new double[] {105, 105, 105, 105, 105, 105, 105});
    assertEquals(5, estimate.meanPercent(), 1e-8);
    assertEquals(5, estimate.lower95Percent(), 1e-8);
    assertThrows(
        IllegalArgumentException.class,
        () -> ProfileComparator.paired(new double[] {0, 1}, new double[] {1, 1}));
  }
}
