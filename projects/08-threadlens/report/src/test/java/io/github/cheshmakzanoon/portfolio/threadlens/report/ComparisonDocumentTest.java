package io.github.cheshmakzanoon.portfolio.threadlens.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.AllocationSummary;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ContentionSummary;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.Profile;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.StackAggregator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ComparisonDocumentTest {
  private static final Map<String, String> SETTINGS = Map.of("jdk.ExecutionSample#period", "20 ms");

  @Test
  void selfSamplesUseSeparateDenominatorsAndSortByObservedImpact() throws Exception {
    var before = accumulator();
    var after = accumulator();
    samples(before, "a", 8);
    samples(before, "b", 2);
    samples(after, "a", 2);
    samples(after, "b", 4);
    samples(after, "c", 14);
    var comparison =
        ComparisonDocument.create(document("before", before), document("after", after));

    assertEquals(1, comparison.schemaVersion());
    assertEquals("before", comparison.baselineRecordingId());
    assertEquals("after", comparison.candidateRecordingId());
    assertEquals(
        List.of("a", "c", "b"),
        comparison.samples().stream().map(delta -> delta.method()).toList());
    var a = comparison.samples().getFirst();
    assertEquals(8, a.baselineSamples());
    assertEquals(2, a.candidateSamples());
    assertEquals(0.8, a.baselineProportion());
    assertEquals(0.1, a.candidateProportion());
    assertEquals(-70, a.percentagePointDelta(), 1e-10);
    assertEquals(70, comparison.samples().get(1).percentagePointDelta(), 1e-10);
    assertEquals(0, comparison.samples().get(2).percentagePointDelta(), 1e-10);
    assertEquals(10, comparison.baselineCoverage().samples());
    assertEquals(20, comparison.candidateCoverage().samples());
    assertEquals(0, comparison.baselineCoverage().samplesWithoutRetainedSelfAttribution());
  }

  @Test
  void missingSamplesRemainUnavailableInsteadOfShowingAFullCpuChange() throws Exception {
    var before = accumulator();
    var after = accumulator();
    samples(after, "observed", 3);
    var comparison =
        ComparisonDocument.create(document("empty", before), document("observed", after));
    var delta = comparison.samples().getFirst();
    assertNull(delta.baselineProportion());
    assertNull(delta.percentagePointDelta());
    assertEquals(1.0, delta.candidateProportion());
    assertTrue(
        comparison.interpretation().stream()
            .anyMatch(note -> note.contains("no execution samples")));
  }

  @Test
  void allocationChangesKeepNewAndDisappearingEvidenceSeparateFromMeasuredZero() throws Exception {
    var before = accumulator();
    var after = accumulator();
    allocation(before, "growing", 100);
    allocation(after, "growing", 150);
    allocation(before, "gone", 1000);
    allocation(after, "new", 500);
    allocation(before, "zero", 0);
    allocation(after, "zero", 10);
    var comparison = ComparisonDocument.create(document("b", before), document("c", after));

    assertEquals(
        List.of("gone", "new", "growing", "zero"),
        comparison.allocations().stream().map(delta -> delta.subject()).toList());
    var gone = comparison.allocations().get(0);
    assertEquals(ComparisonDocument.Presence.BASELINE_ONLY, gone.presence());
    assertEquals(1, gone.baselineEvents());
    assertEquals(0, gone.candidateEvents());
    assertEquals(-1000, gone.weightedBytesChange());
    assertEquals(-100.0, gone.weightedBytesPercentChange());
    var added = comparison.allocations().get(1);
    assertEquals(ComparisonDocument.Presence.CANDIDATE_ONLY, added.presence());
    assertEquals(0, added.baselineWeightedBytes());
    assertEquals(500, added.candidateWeightedBytes());
    assertNull(added.weightedBytesPercentChange());
    var growing = comparison.allocations().get(2);
    assertEquals(ComparisonDocument.Presence.BOTH, growing.presence());
    assertEquals(50, growing.weightedBytesChange());
    assertEquals(50.0, growing.weightedBytesPercentChange());
    var zero = comparison.allocations().get(3);
    assertEquals(ComparisonDocument.Presence.BOTH, zero.presence());
    assertNull(zero.weightedBytesPercentChange());
  }

  @Test
  void eachTimingSourceRetainsItsOwnTotalsAndQuantiles() throws Exception {
    var before = accumulator();
    var after = accumulator();
    for (var kind :
        List.of(
            NormalizedEvent.Kind.MONITOR, NormalizedEvent.Kind.PARK, NormalizedEvent.Kind.TIMING)) {
      timing(before, kind, "work", 100);
      timing(after, kind, "work", 200);
      timing(before, kind, "gone", 300);
      timing(after, kind, "new", 400);
    }
    var comparison = ComparisonDocument.create(document("b", before), document("c", after));
    for (var changes :
        List.of(comparison.monitors(), comparison.parks(), comparison.selectedTimings())) {
      assertEquals(
          List.of("new", "gone", "work"), changes.stream().map(delta -> delta.subject()).toList());
      var added = changes.getFirst();
      assertEquals(ComparisonDocument.Presence.CANDIDATE_ONLY, added.presence());
      assertNull(added.baselineP50Nanos());
      assertNull(added.baselineP95Nanos());
      assertNull(added.baselineP99Nanos());
      assertNull(added.p99PercentChange());
      assertNotNull(added.candidateP50Nanos());
      assertNotNull(added.candidateP95Nanos());
      assertNotNull(added.candidateP99Nanos());
      assertNull(added.totalNanosPercentChange());
      var gone = changes.get(1);
      assertEquals(ComparisonDocument.Presence.BASELINE_ONLY, gone.presence());
      assertNull(gone.candidateP50Nanos());
      assertNull(gone.candidateP95Nanos());
      assertNull(gone.candidateP99Nanos());
      assertNull(gone.p99PercentChange());
      assertEquals(-100.0, gone.totalNanosPercentChange());
      var both = changes.get(2);
      assertEquals(ComparisonDocument.Presence.BOTH, both.presence());
      assertEquals(100, both.baselineTotalNanos());
      assertEquals(200, both.candidateTotalNanos());
      assertEquals(100, both.totalNanosChange());
      assertEquals(100.0, both.totalNanosPercentChange());
      assertEquals(100.0, both.p99PercentChange());
    }
  }

  @Test
  void zeroDurationHasNoInventedInfiniteRelativeLatency() throws Exception {
    var before = accumulator();
    var after = accumulator();
    timing(before, NormalizedEvent.Kind.PARK, "zero", 0);
    timing(after, NormalizedEvent.Kind.PARK, "zero", 50);
    var delta =
        ComparisonDocument.create(document("b", before), document("c", after)).parks().getFirst();
    assertEquals(0, delta.baselineP99Nanos());
    assertEquals(50, delta.totalNanosChange());
    assertNull(delta.totalNanosPercentChange());
    assertNull(delta.p99PercentChange());
  }

  @Test
  void absentProfilesDoNotInventAggregateRows() throws Exception {
    var comparison =
        ComparisonDocument.create(document("b", accumulator()), document("c", accumulator()));
    assertTrue(comparison.samples().isEmpty());
    assertTrue(comparison.allocations().isEmpty());
    assertTrue(comparison.monitors().isEmpty());
    assertTrue(comparison.parks().isEmpty());
    assertTrue(comparison.selectedTimings().isEmpty());
    assertEquals(0, comparison.baselineCoverage().retainedWindows());
  }

  @Test
  void extremeLongValuesAreSubtractedWithoutOverflowOrLossOfSign() throws Exception {
    var before =
        withAllocations(
            Map.of(
                "large",
                new AllocationSummary.Allocation(1, Long.MAX_VALUE),
                "small",
                new AllocationSummary.Allocation(1, 1)));
    var after =
        withAllocations(
            Map.of(
                "large",
                new AllocationSummary.Allocation(1, 0),
                "small",
                new AllocationSummary.Allocation(1, Long.MAX_VALUE)));
    var changes = ComparisonDocument.create(before, after).allocations();
    assertEquals("large", changes.getFirst().subject());
    assertEquals(-Long.MAX_VALUE, changes.getFirst().weightedBytesChange());
    assertEquals(-100.0, changes.getFirst().weightedBytesPercentChange());
    assertEquals(Long.MAX_VALUE - 1, changes.get(1).weightedBytesChange());
    assertTrue(Double.isFinite(changes.get(1).weightedBytesPercentChange()));
  }

  @Test
  void timingExtremesAndEqualMagnitudeChangesHaveStableOrder() throws Exception {
    var before = withTimings(Map.of("z", new ContentionSummary.Timing(1, Long.MAX_VALUE, 1, 1, 1)));
    var after = withTimings(Map.of("a", new ContentionSummary.Timing(1, Long.MAX_VALUE, 1, 1, 1)));
    var changes = ComparisonDocument.create(before, after).selectedTimings();
    assertEquals(List.of("a", "z"), changes.stream().map(delta -> delta.subject()).toList());
    assertEquals(Long.MAX_VALUE, changes.getFirst().totalNanosChange());
    assertEquals(-Long.MAX_VALUE, changes.get(1).totalNanosChange());
    assertNull(changes.getFirst().totalNanosPercentChange());
    assertEquals(-100.0, changes.get(1).totalNanosPercentChange());
  }

  @Test
  void distinctMaximumAllocationMapsHaveABoundedCompleteUnion() throws Exception {
    Map<String, AllocationSummary.Allocation> before = new HashMap<>(), after = new HashMap<>();
    for (int index = 0; index < 10_000; index++) {
      before.put("b" + index, new AllocationSummary.Allocation(1, 1));
      after.put("c" + index, new AllocationSummary.Allocation(1, 1));
    }
    var comparison = ComparisonDocument.create(withAllocations(before), withAllocations(after));
    assertEquals(20_000, comparison.allocations().size());
    assertEquals(
        10_000,
        comparison.allocations().stream()
            .filter(delta -> delta.presence() == ComparisonDocument.Presence.BASELINE_ONLY)
            .count());
    assertEquals(
        10_000,
        comparison.allocations().stream()
            .filter(delta -> delta.presence() == ComparisonDocument.Presence.CANDIDATE_ONLY)
            .count());
  }

  @Test
  void snapshotsDoNotChangeWhenTheCallerMutatesInputMapsOrLists() throws Exception {
    var before = accumulator();
    allocation(before, "class", 100);
    var comparison = ComparisonDocument.create(document("b", before), document("c", accumulator()));
    allocation(before, "later", 200);
    assertEquals(1, comparison.allocations().size());
    assertThrows(UnsupportedOperationException.class, () -> comparison.allocations().clear());
    assertThrows(UnsupportedOperationException.class, () -> comparison.samples().clear());
    assertThrows(UnsupportedOperationException.class, () -> comparison.interpretation().clear());
    assertThrows(UnsupportedOperationException.class, () -> comparison.eventSettings().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> comparison.baselineCoverage().nonzeroAggregationCounters().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> comparison.baselineCoverage().nonzeroLossCounters().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> comparison.baselineCoverage().activeCollectionLimits().clear());
  }

  @Test
  void directConstructionAlsoFreezesCallerCollections() {
    Map<String, Long> losses = new HashMap<>(Map.of("lost", 2L));
    Map<String, Integer> limits = new HashMap<>(Map.of("queueCapacity", 2));
    var coverage =
        new ComparisonDocument.Coverage(0, 1, 0, 0, 0, 0, losses, losses, limits, "unknown");
    List<String> notes = new ArrayList<>(List.of("note"));
    Map<String, String> settings = new HashMap<>(SETTINGS);
    var comparison =
        new ComparisonDocument(
            1, "b", "c", settings, coverage, coverage, List.of(), List.of(), List.of(), List.of(),
            List.of(), notes);
    losses.clear();
    limits.clear();
    notes.clear();
    settings.clear();
    assertEquals(Map.of("lost", 2L), comparison.baselineCoverage().nonzeroLossCounters());
    assertEquals(
        Map.of("queueCapacity", 2), comparison.baselineCoverage().activeCollectionLimits());
    assertEquals(List.of("note"), comparison.interpretation());
    assertEquals(SETTINGS, comparison.eventSettings());
  }

  @Test
  void missingStacksAndWindowLossesRemainVisibleInBothCoverageAndInterpretation() throws Exception {
    var before = accumulator();
    before.accept(new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of()));
    before.accept(new NormalizedEvent(NormalizedEvent.Kind.LOSS, 1, 10, "loss", List.of()));
    var report = document("loss", before);
    var comparison = ComparisonDocument.create(report, report);
    var coverage = comparison.baselineCoverage();
    assertEquals(1, coverage.samplesWithoutRetainedSelfAttribution());
    assertEquals(1, coverage.incompleteWindows());
    assertEquals(10L, coverage.nonzeroAggregationCounters().get("jfrDataLossBytes"));
    assertEquals(1L, coverage.nonzeroAggregationCounters().get("missingSampleStacks"));
    assertTrue(
        comparison.interpretation().stream()
            .anyMatch(note -> note.startsWith("Coverage is incomplete")));
  }

  @Test
  void producerSuccessCountersAreDisplayedWithoutBeingMislabelledAsLoss() throws Exception {
    var profile = accumulator();
    samples(profile, "work", 2);
    var report =
        ReportDocument.create(
            "complete",
            profile.snapshot(),
            SETTINGS,
            Map.of(),
            Map.of(
                "observedTimingExits",
                20L,
                "observedExceptionalTimingExits",
                1L,
                "timingStatusComplete",
                1L,
                "timingSelectionCoverageKnown",
                1L,
                "timingProducerQuiesced",
                1L,
                "timingAcceptedReservations",
                20L,
                "timingProducerObservedEvents",
                20L,
                "rawTimingPrefixRowsOmitted",
                10L));
    var comparison = ComparisonDocument.create(report, report);
    assertEquals(8, comparison.baselineCoverage().nonzeroLossCounters().size());
    assertFalse(
        comparison.interpretation().stream()
            .anyMatch(note -> note.startsWith("Coverage is incomplete")));
  }

  @Test
  void explicitLossAndDifferingCollectionLimitsStayVisible() throws Exception {
    var profile = accumulator();
    samples(profile, "work", 2);
    var before =
        ReportDocument.create(
            "b",
            profile.snapshot(),
            SETTINGS,
            Map.of(),
            Map.of("queueDrops", 1L, "zero", 0L),
            Map.of("queueCapacity", 2));
    var after =
        ReportDocument.create(
            "c", profile.snapshot(), SETTINGS, Map.of(), Map.of(), Map.of("queueCapacity", 64));
    var comparison = ComparisonDocument.create(before, after);
    assertEquals(Map.of("queueDrops", 1L), comparison.baselineCoverage().nonzeroLossCounters());
    assertTrue(
        comparison.interpretation().stream()
            .anyMatch(note -> note.startsWith("Coverage is incomplete")));
    assertTrue(
        comparison.interpretation().stream()
            .anyMatch(note -> note.startsWith("Active collection limits differ")));
  }

  @Test
  void mismatchedUnknownAndEmptyProducerSettingsCannotProduceAComparison() {
    var profile = accumulator().snapshot();
    var before = ReportDocument.create("b", profile, SETTINGS, Map.of(), Map.of());
    for (Map<String, String> settings :
        List.of(
            Map.<String, String>of(),
            Map.of("producerSettings", "unknown"),
            Map.of("jdk.ExecutionSample#period", "10 ms"))) {
      var after = ReportDocument.create("c", profile, settings, Map.of(), Map.of());
      assertThrows(IllegalArgumentException.class, () -> ComparisonDocument.create(before, after));
      assertThrows(IllegalArgumentException.class, () -> ComparisonDocument.create(after, before));
    }
  }

  @Test
  void malformedProfilesCannotEnterTheComparisonModel() {
    var invalid = withAllocations(Map.of("impossible", new AllocationSummary.Allocation(0, 10)));
    var valid = document("valid", accumulator());
    assertThrows(IOException.class, () -> ComparisonDocument.create(invalid, valid));
    assertThrows(IOException.class, () -> ComparisonDocument.create(valid, invalid));
  }

  private static ProfileAccumulator accumulator() {
    return new ProfileAccumulator(100, 1_000_000_000L, 20);
  }

  private static void samples(ProfileAccumulator accumulator, String method, int count) {
    for (int i = 0; i < count; i++)
      accumulator.accept(
          new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of(method)));
  }

  private static void allocation(ProfileAccumulator accumulator, String subject, long bytes) {
    accumulator.accept(
        new NormalizedEvent(NormalizedEvent.Kind.ALLOCATION, 1, bytes, subject, List.of()));
  }

  private static void timing(
      ProfileAccumulator accumulator, NormalizedEvent.Kind kind, String subject, long duration) {
    accumulator.accept(new NormalizedEvent(kind, 1, duration, subject, List.of()));
  }

  private static ReportDocument document(String id, ProfileAccumulator accumulator) {
    var profile = accumulator.snapshot();
    return ReportDocument.create(id, profile, SETTINGS, Map.of(), profile.counters());
  }

  private static ReportDocument withAllocations(Map<String, AllocationSummary.Allocation> values) {
    return aggregate(values, Map.of());
  }

  private static ReportDocument withTimings(Map<String, ContentionSummary.Timing> values) {
    return aggregate(Map.of(), values);
  }

  private static ReportDocument aggregate(
      Map<String, AllocationSummary.Allocation> allocations,
      Map<String, ContentionSummary.Timing> timings) {
    var profile =
        new Profile(
            allocations.size(),
            0,
            0,
            0,
            new StackAggregator.Flame("[root]", 0, 0, List.of()),
            Map.of(),
            Map.of(),
            new TreeMap<>(allocations),
            Map.of(),
            Map.of(),
            new TreeMap<>(timings),
            Map.of());
    return ReportDocument.create("aggregates", profile, SETTINGS, Map.of(), Map.of());
  }
}
