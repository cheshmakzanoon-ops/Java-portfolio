package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LimitsTest {
  @Test
  void boundedWindowOverflowAndExactBoundary() {
    var windows = new WindowAccumulator(10, 2);
    windows.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 0, 1, "a", List.of()));
    windows.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 9, 1, "a", List.of()));
    windows.accept(new NormalizedEvent(NormalizedEvent.Kind.ALLOCATION, 10, 200, "a", List.of()));
    windows.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 20, 1, "a", List.of()));
    assertEquals(1, windows.overflow());
    assertEquals(2, windows.snapshot().size());
    assertEquals(2, windows.snapshot().get(0L).samples());
    assertEquals(10, windows.snapshot().get(0L).endNanos());
    assertEquals(200, windows.snapshot().get(10L).allocatedBytes());
    assertFalse(windows.snapshot().get(0L).incomplete());
    windows.markIncomplete();
    assertTrue(windows.snapshot().values().stream().allMatch(WindowAccumulator.Window::incomplete));
  }

  @Test
  void boundedAllocationAndDurationsDoNotLoseOverflowEvidence() {
    var a = new AllocationSummary(1);
    a.accept("A", 0);
    a.accept("A", 100);
    a.accept("B", 200);
    assertEquals(2, a.snapshot().get("A").events());
    assertEquals(100, a.snapshot().get("A").observedWeightedBytes());
    assertEquals(1, a.overflow());
    assertThrows(IllegalArgumentException.class, () -> a.accept("A", -1));
    var c = new ContentionSummary(1);
    c.accept("a", 0);
    c.accept("a", 1000);
    c.accept("a", 3_600_000_000_001L);
    c.accept("b", 99);
    assertEquals(1, c.overflow());
    assertEquals(1, c.clipped());
    assertEquals(3, c.snapshot().get("a").events());
    assertEquals(3_600_000_001_001L, c.snapshot().get("a").totalNanos());
    assertTrue(c.snapshot().get("a").p99Nanos() >= 3_600_000_000_000L);
    assertTrue(c.snapshot().get("a").p50Nanos() >= 1000);
    assertThrows(IllegalArgumentException.class, () -> c.accept("a", -1));
  }

  @Test
  void trieOverflowRecursionAndExistingPrefixes() {
    var s = new StackAggregator(2);
    s.accept(List.of("a", "b"));
    s.accept(List.of("a", "b"));
    s.accept(List.of("z", "z"));
    s.accept(List.of("a", "c"));
    assertEquals(2, s.nodes());
    assertEquals(2, s.methods().get("b").self());
    assertEquals(3, s.methods().get("a").inclusive());
    assertTrue(s.overflow() >= 2);
    assertEquals(4, s.snapshot().inclusive());
    assertEquals(3, s.snapshot().children().getFirst().inclusive());
  }

  @Test
  void allEventAndLimitValidationBranches() {
    for (int bad : new int[] {0, -1, 10_001}) {
      assertThrows(IllegalArgumentException.class, () -> new StackAggregator(bad));
      assertThrows(IllegalArgumentException.class, () -> new AllocationSummary(bad));
      assertThrows(IllegalArgumentException.class, () -> new WindowAccumulator(1, bad));
    }
    for (int bad : new int[] {0, -1, 257})
      assertThrows(IllegalArgumentException.class, () -> new ContentionSummary(bad));
    assertThrows(IllegalArgumentException.class, () -> new WindowAccumulator(-1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new NormalizedEvent(null, 1, 1, "a", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, -1, "a", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "a", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "x".repeat(257), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NormalizedEvent(
                NormalizedEvent.Kind.SAMPLE, 1, 1, "a", Collections.nCopies(129, "f")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NormalizedEvent(
                NormalizedEvent.Kind.SAMPLE, 1, 1, "a", Arrays.asList((String) null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "a", List.of("x".repeat(257))));
    assertEquals(
        128,
        new NormalizedEvent(
                NormalizedEvent.Kind.SAMPLE, 0, 0, "x".repeat(256), Collections.nCopies(128, "f"))
            .stack()
            .size());
  }

  @Test
  void emptyProfileAndPairStatistics() {
    var a = new ProfileAccumulator(1, 1, 1);
    assertEquals(0, a.snapshot().startNanos());
    a.markIncomplete();
    a.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "a", List.of()));
    assertTrue(a.snapshot().windows().get(1L).incomplete());
    assertEquals(1, a.snapshot().counters().get("missingSampleStacks"));
    var p = ProfileComparator.paired(new double[] {100, 100}, new double[] {90, 110});
    assertTrue(p.lower95Percent() < p.meanPercent());
    assertTrue(p.upper95Percent() > p.meanPercent());
    assertEquals(Math.expm1((Math.log(.9) + Math.log(1.1)) / 2) * 100, p.medianPercent(), 1e-9);
    assertThrows(
        IllegalArgumentException.class,
        () -> ProfileComparator.paired(new double[] {1}, new double[] {1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProfileComparator.paired(new double[] {1, 1}, new double[] {1}));
    for (double bad : new double[] {-1, 0, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProfileComparator.paired(new double[] {1, 1}, new double[] {1, bad}));
      assertThrows(
          IllegalArgumentException.class,
          () -> ProfileComparator.paired(new double[] {1, bad}, new double[] {1, 1}));
    }
  }

  @Test
  void aggregateOverflowAlwaysMarksRetainedWindowsIncomplete() {
    var p = new ProfileAccumulator(1, 1, 1);
    for (int i = 0; i < 130; i++)
      for (var kind :
          List.of(
              NormalizedEvent.Kind.SAMPLE,
              NormalizedEvent.Kind.ALLOCATION,
              NormalizedEvent.Kind.MONITOR,
              NormalizedEvent.Kind.PARK,
              NormalizedEvent.Kind.TIMING)) {
        p.accept(new NormalizedEvent(kind, i, 1, "subject-" + i, List.of("frame-" + i)));
      }
    var snap = p.snapshot();
    assertTrue(snap.windows().values().stream().allMatch(WindowAccumulator.Window::incomplete));
    for (String counter :
        List.of(
            "stackOverflow",
            "windowOverflow",
            "allocationOverflow",
            "contentionOverflow",
            "timingOverflow")) assertTrue(snap.counters().get(counter) > 0, counter);
  }

  @Test
  void comparisonCandidateMissingStaysUnknown() {
    var a = new ProfileAccumulator(2, 1, 1);
    a.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "s", List.of("a")));
    var delta =
        ProfileComparator.compare(a.snapshot(), new ProfileAccumulator(1, 1, 1).snapshot())
            .getFirst();
    assertNull(delta.candidateProportion());
    assertNull(delta.percentagePointDelta());
    assertEquals(1, delta.baselineProportion());
  }
}
