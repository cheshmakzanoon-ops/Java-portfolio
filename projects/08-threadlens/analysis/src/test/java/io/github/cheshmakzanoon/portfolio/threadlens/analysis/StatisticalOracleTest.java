package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class StatisticalOracleTest {
  @Test
  void nontrivialProportionsAndPrefixLeafCountsHaveIndependentExpectedValues() {
    var base = new ProfileAccumulator(20, 100, 10);
    var candidate = new ProfileAccumulator(20, 100, 10);
    base.accept(sample(150, "root", "a"));
    base.accept(sample(90, "root", "b"));
    candidate.accept(sample(200, "root", "a"));
    candidate.accept(sample(210, "root", "a"));
    candidate.accept(sample(220, "root", "b"));
    var p = base.snapshot();
    assertEquals(2, p.eventCount());
    assertEquals(90, p.startNanos());
    assertEquals(150, p.endNanos());
    assertEquals(0, p.flame().self());
    assertEquals(2, p.flame().children().getFirst().inclusive());
    assertEquals(1, p.flame().children().getFirst().children().getFirst().self());
    assertEquals(1, p.flame().children().getFirst().children().getLast().self());
    assertTrue(p.windows().values().stream().noneMatch(WindowAccumulator.Window::incomplete));
    var a =
        ProfileComparator.compare(p, candidate.snapshot()).stream()
            .filter(d -> d.method().equals("a"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, a.baselineSamples());
    assertEquals(2, a.candidateSamples());
    assertEquals(.5, a.baselineProportion());
    assertEquals(2.0 / 3, a.candidateProportion());
    assertEquals(100.0 / 6, a.percentagePointDelta(), 1e-10);
  }

  @Test
  void externallyCalculatedLogRatioIntervalsAndUnsortedMedian() { // Independent numeric oracle
    // calculated with Python
    // statistics.stdev, sample df,
    // Student-t critical values.
    var two = ProfileComparator.paired(new double[] {100, 100}, new double[] {90, 110});
    assertEquals(-.5012562893379998, two.meanPercent(), 1e-10);
    assertEquals(-72.1931028237423, two.lower95Percent(), 1e-10);
    assertEquals(256.02677771804395, two.upper95Percent(), 1e-9);
    var seven =
        ProfileComparator.paired(
            new double[] {100, 100, 100, 100, 100, 100, 100},
            new double[] {101, 95, 99, 110, 102, 105, 100});
    assertEquals(7, seven.pairs());
    assertEquals(1.620267270236514, seven.meanPercent(), 1e-10);
    assertEquals(1, seven.medianPercent(), 1e-10);
    assertEquals(-2.6422349818092328, seven.lower95Percent(), 1e-10);
    assertEquals(6.0693897209413015, seven.upper95Percent(), 1e-10);
  }

  @Test
  void monitorParkLossAndClippingRemainVisibleSeparately() {
    var a = new ProfileAccumulator(10, 100, 10);
    a.accept(sample(10, "root"));
    a.accept(
        new NormalizedEvent(
            NormalizedEvent.Kind.MONITOR, 120, 3_600_000_000_000L, "lock", List.of()));
    a.accept(
        new NormalizedEvent(NormalizedEvent.Kind.PARK, 130, 3_600_000_000_001L, "park", List.of()));
    a.accept(
        new NormalizedEvent(
            NormalizedEvent.Kind.TIMING, 140, 3_600_000_000_001L, "method", List.of()));
    var healthy = a.snapshot();
    assertEquals(4, healthy.eventCount());
    assertEquals(1, healthy.monitors().get("lock").events());
    assertEquals(3_600_000_000_000L, healthy.monitors().get("lock").totalNanos());
    assertEquals(3_600_000_000_001L, healthy.parks().get("park").totalNanos());
    assertEquals(2, healthy.counters().get("durationClipped"));
    assertTrue(healthy.windows().values().stream().noneMatch(WindowAccumulator.Window::incomplete));
    a.accept(new NormalizedEvent(NormalizedEvent.Kind.LOSS, 240, 1, "loss", List.of()));
    assertTrue(
        a.snapshot().windows().values().stream().allMatch(WindowAccumulator.Window::incomplete));
  }

  @Test
  void maximumValidBoundsAndInputImmutability() {
    assertNotNull(new StackAggregator(10_000));
    assertNotNull(new AllocationSummary(10_000));
    assertNotNull(new WindowAccumulator(1, 10_000));
    assertNotNull(new ContentionSummary(256));
    var stack = new ArrayList<>(List.of("f".repeat(256)));
    var event = new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 0, 0, "x", stack);
    stack.set(0, "mutated");
    assertEquals("f".repeat(256), event.stack().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> event.stack().add("other"));
    var bounded = new StackAggregator(1);
    bounded.accept(List.of("a"));
    bounded.accept(List.of("b"));
    assertEquals(1, bounded.methods().size());
  }

  @Test
  void recursiveSelfAccountingAcrossDepthAndMultipleRecurringFrames() {
    for (int depth : new int[] {2, 5}) {
      var s = new StackAggregator(10);
      s.accept(Collections.nCopies(depth, "r"));
      assertEquals(1, s.methods().get("r").inclusive());
      assertEquals(1, s.methods().get("r").self());
    }
    var s = new StackAggregator(10);
    s.accept(List.of("a", "b", "a", "b"));
    assertEquals(0, s.methods().get("a").self());
    assertEquals(1, s.methods().get("b").self());
  }

  private static NormalizedEvent sample(long at, String... frames) {
    return new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, at, 1, "sample", List.of(frames));
  }
}
