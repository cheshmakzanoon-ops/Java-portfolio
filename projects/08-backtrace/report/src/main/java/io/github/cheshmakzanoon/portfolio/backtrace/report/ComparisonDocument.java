package io.github.cheshmakzanoon.portfolio.backtrace.report;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.AllocationSummary;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.ContentionSummary;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.ProfileComparator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A comparison of retained observations, not a causal diagnosis or an overhead certificate.
 * Profiles are validated before construction; every output collection is bounded by the union of
 * the corresponding two input collections. Absolute changes never infer an unobserved workload.
 */
public record ComparisonDocument(
    int schemaVersion,
    String baselineRecordingId,
    String candidateRecordingId,
    Map<String, String> eventSettings,
    Coverage baselineCoverage,
    Coverage candidateCoverage,
    List<ProfileComparator.Delta> samples,
    List<AllocationDelta> allocations,
    List<TimingDelta> monitors,
    List<TimingDelta> parks,
    List<TimingDelta> selectedTimings,
    List<String> interpretation) {

  /** Observed absence is distinct from a measured zero in an existing aggregate. */
  public enum Presence {
    BOTH,
    BASELINE_ONLY,
    CANDIDATE_ONLY
  }

  public record AllocationDelta(
      String subject,
      Presence presence,
      long baselineEvents,
      long candidateEvents,
      long baselineWeightedBytes,
      long candidateWeightedBytes,
      long weightedBytesChange,
      Double weightedBytesPercentChange) {}

  /**
   * Totals include zero retained observations on an absent side. Quantiles are null for an absent
   * side; inventing a zero latency there would create a false latency improvement/regression.
   */
  public record TimingDelta(
      String subject,
      Presence presence,
      long baselineEvents,
      long candidateEvents,
      long baselineTotalNanos,
      long candidateTotalNanos,
      long totalNanosChange,
      Double totalNanosPercentChange,
      Long baselineP50Nanos,
      Long candidateP50Nanos,
      Long baselineP95Nanos,
      Long candidateP95Nanos,
      Long baselineP99Nanos,
      Long candidateP99Nanos,
      Double p99PercentChange) {}

  /**
   * The span is the first/last observed event start, not recording wall time or an exposure
   * denominator. Producer/platform identity is unavailable; the supplied manifests are
   * declarations.
   */
  public record Coverage(
      long firstEventNanos,
      long lastEventNanos,
      long samples,
      long samplesWithoutRetainedSelfAttribution,
      int retainedWindows,
      int incompleteWindows,
      Map<String, Long> nonzeroLossCounters,
      Map<String, Long> nonzeroAggregationCounters,
      Map<String, Integer> activeCollectionLimits,
      String missingEventStatus) {
    public Coverage {
      nonzeroLossCounters = immutableSorted(nonzeroLossCounters);
      nonzeroAggregationCounters = immutableSorted(nonzeroAggregationCounters);
      activeCollectionLimits = immutableSorted(activeCollectionLimits);
    }
  }

  public ComparisonDocument {
    eventSettings = immutableSorted(eventSettings);
    samples = List.copyOf(samples);
    allocations = List.copyOf(allocations);
    monitors = List.copyOf(monitors);
    parks = List.copyOf(parks);
    selectedTimings = List.copyOf(selectedTimings);
    interpretation = List.copyOf(interpretation);
  }

  public static ComparisonDocument create(ReportDocument baseline, ReportDocument candidate)
      throws IOException {
    ReportValidator.validate(baseline);
    ReportValidator.validate(candidate);
    if (baseline.eventSettings().isEmpty()
        || candidate.eventSettings().isEmpty()
        || baseline.eventSettings().containsKey("producerSettings")
        || candidate.eventSettings().containsKey("producerSettings"))
      throw new IllegalArgumentException(
          "producer settings unknown; supply matching recording manifests before comparison");
    if (!baseline.eventSettings().equals(candidate.eventSettings()))
      throw new IllegalArgumentException(
          "recording settings differ; comparison would be misleading");

    Coverage before = coverage(baseline), after = coverage(candidate);
    List<ProfileComparator.Delta> samples =
        new ArrayList<>(ProfileComparator.compare(baseline.profile(), candidate.profile()));
    samples.sort(
        Comparator.<ProfileComparator.Delta>comparingDouble(
                delta ->
                    delta.percentagePointDelta() == null
                        ? -1
                        : Math.abs(delta.percentagePointDelta()))
            .reversed()
            .thenComparing(ProfileComparator.Delta::method));
    List<String> notes = new ArrayList<>();
    notes.add(
        "Execution changes are percentage points of self samples, not CPU-time percentages or invocation counts.");
    notes.add(
        "Allocation weights are sampled bytes; totals depend on workload volume, observation duration and producer sampling.");
    notes.add(
        "Monitor and park durations can overlap across threads; their sum is not elapsed wall time.");
    notes.add(
        "Selected method timings have a separate collection interval. No rate uses the JFR event span as its denominator.");
    notes.add(
        "An absent subject has no retained observations on that side. It does not prove that the application stopped allocating, waiting or executing it.");
    notes.add(
        "A percent change is unavailable when the baseline is absent or zero. Missing quantiles remain unavailable, not zero.");
    notes.add(
        "Matching declared producer settings do not establish matching workload, hardware or producer platform, statistical significance or causation.");
    if (before.samples() == 0 || after.samples() == 0)
      notes.add(
          "At least one recording has no execution samples; self-sample deltas are unavailable.");
    if (before.incompleteWindows() > 0
        || after.incompleteWindows() > 0
        || before.samplesWithoutRetainedSelfAttribution() > 0
        || after.samplesWithoutRetainedSelfAttribution() > 0
        || hasLoss(before)
        || hasLoss(after))
      notes.add(
          "Coverage is incomplete or has reported losses; changes in retained observations may reflect lost evidence.");
    if (!before.activeCollectionLimits().equals(after.activeCollectionLimits()))
      notes.add(
          "Active collection limits differ. Selected timing coverage is not directly comparable even though JFR settings match.");

    return new ComparisonDocument(
        1,
        baseline.recordingId(),
        candidate.recordingId(),
        baseline.eventSettings(),
        before,
        after,
        samples,
        allocationDeltas(baseline.profile().allocations(), candidate.profile().allocations()),
        timingDeltas(baseline.profile().monitors(), candidate.profile().monitors()),
        timingDeltas(baseline.profile().parks(), candidate.profile().parks()),
        timingDeltas(baseline.profile().timings(), candidate.profile().timings()),
        notes);
  }

  private static Coverage coverage(ReportDocument document) {
    long attributed = 0;
    for (var method : document.profile().methods().values())
      attributed = Math.addExact(attributed, method.self());
    int incomplete = 0;
    for (var window : document.profile().windows().values()) if (window.incomplete()) incomplete++;
    return new Coverage(
        document.startNanos(),
        document.endNanos(),
        document.sampleCount(),
        document.sampleCount() - attributed,
        document.profile().windows().size(),
        incomplete,
        nonzero(document.lossCounters()),
        nonzero(document.profile().counters()),
        document.activeCollectionLimits(),
        document.missingEventStatus());
  }

  private static List<AllocationDelta> allocationDeltas(
      Map<String, AllocationSummary.Allocation> baseline,
      Map<String, AllocationSummary.Allocation> candidate) {
    List<AllocationDelta> changes = new ArrayList<>();
    for (String subject : union(baseline, candidate)) {
      var before = baseline.get(subject);
      var after = candidate.get(subject);
      long b = before == null ? 0 : before.observedWeightedBytes();
      long c = after == null ? 0 : after.observedWeightedBytes();
      changes.add(
          new AllocationDelta(
              subject,
              presence(before, after),
              before == null ? 0 : before.events(),
              after == null ? 0 : after.events(),
              b,
              c,
              c - b,
              percent(b, c)));
    }
    changes.sort(
        Comparator.<AllocationDelta>comparingLong(delta -> Math.abs(delta.weightedBytesChange()))
            .reversed()
            .thenComparing(AllocationDelta::subject));
    return changes;
  }

  private static List<TimingDelta> timingDeltas(
      Map<String, ContentionSummary.Timing> baseline,
      Map<String, ContentionSummary.Timing> candidate) {
    List<TimingDelta> changes = new ArrayList<>();
    for (String subject : union(baseline, candidate)) {
      var before = baseline.get(subject);
      var after = candidate.get(subject);
      long b = before == null ? 0 : before.totalNanos();
      long c = after == null ? 0 : after.totalNanos();
      changes.add(
          new TimingDelta(
              subject,
              presence(before, after),
              before == null ? 0 : before.events(),
              after == null ? 0 : after.events(),
              b,
              c,
              c - b,
              percent(b, c),
              before == null ? null : before.p50Nanos(),
              after == null ? null : after.p50Nanos(),
              before == null ? null : before.p95Nanos(),
              after == null ? null : after.p95Nanos(),
              before == null ? null : before.p99Nanos(),
              after == null ? null : after.p99Nanos(),
              before == null || after == null
                  ? null
                  : percent(before.p99Nanos(), after.p99Nanos())));
    }
    changes.sort(
        Comparator.<TimingDelta>comparingLong(delta -> Math.abs(delta.totalNanosChange()))
            .reversed()
            .thenComparing(TimingDelta::subject));
    return changes;
  }

  private static Presence presence(Object baseline, Object candidate) {
    return baseline == null
        ? Presence.CANDIDATE_ONLY
        : candidate == null ? Presence.BASELINE_ONLY : Presence.BOTH;
  }

  private static Double percent(long baseline, long candidate) {
    return baseline == 0 ? null : ((double) (candidate - baseline) / baseline) * 100;
  }

  private static TreeSet<String> union(Map<String, ?> baseline, Map<String, ?> candidate) {
    TreeSet<String> keys = new TreeSet<>(baseline.keySet());
    keys.addAll(candidate.keySet());
    return keys;
  }

  private static Map<String, Long> nonzero(Map<String, Long> source) {
    Map<String, Long> result = new TreeMap<>();
    source.forEach(
        (name, value) -> {
          if (value != 0) result.put(name, value);
        });
    return result;
  }

  private static boolean hasLoss(Coverage coverage) {
    // Existing schema-1 reports carry both measurements and loss indicators in these maps.
    // A successful producer status or a counted invocation is not itself missing evidence.
    var informational =
        java.util.Set.of(
            "observedTimingExits",
            "observedExceptionalTimingExits",
            "timingStatusComplete",
            "timingSelectionCoverageKnown",
            "timingProducerQuiesced",
            "timingAcceptedReservations",
            "timingProducerObservedEvents",
            "rawTimingPrefixRowsOmitted");
    return coverage.nonzeroLossCounters().keySet().stream()
            .anyMatch(name -> !informational.contains(name))
        || coverage.nonzeroAggregationCounters().keySet().stream()
            .anyMatch(name -> !informational.contains(name));
  }

  private static <V> Map<String, V> immutableSorted(Map<String, V> values) {
    return Collections.unmodifiableMap(new TreeMap<>(values));
  }
}
