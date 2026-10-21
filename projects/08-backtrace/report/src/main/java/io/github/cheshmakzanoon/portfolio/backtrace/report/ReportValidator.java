package io.github.cheshmakzanoon.portfolio.backtrace.report;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.*;
import java.io.IOException;
import java.util.*;

/** Validates report denominators and bounded aggregates before rendering or comparison. */
final class ReportValidator {
  private ReportValidator() {}

  static void validate(ReportDocument document) throws IOException {
    try {
      require(document != null && document.schemaVersion() == 1, "unsupported report schema");
      text(document.recordingId(), 256);
      text(document.jdkVendor(), 1024);
      text(document.jdkVersion(), 1024);
      text(document.os(), 1024);
      text(document.architecture(), 1024);
      text(document.missingEventStatus(), 4096);
      properties(document.eventSettings(), 128);
      properties(document.units(), 32);
      require(
          document.supportedCapabilities() != null && document.supportedCapabilities().size() <= 64,
          "capability bounds");
      counters(document.lossCounters(), 128);
      counters(document.aggregationLimits(), 32);
      counters(document.activeCollectionLimits(), 32);
      Profile profile = Objects.requireNonNull(document.profile());
      nonnegative(
          profile.eventCount(), profile.sampleCount(), profile.startNanos(), profile.endNanos());
      require(
          profile.sampleCount() <= profile.eventCount()
              && profile.startNanos() <= profile.endNanos(),
          "profile coordinates");
      require(
          document.sampleCount() == profile.sampleCount()
              && document.startNanos() == profile.startNanos()
              && document.endNanos() == profile.endNanos(),
          "report/profile coordinates differ");
      require(profile.flame().inclusive() == profile.sampleCount(), "flame sample denominator");
      flame(profile.flame(), 0, new int[1]);
      require(profile.methods().size() <= 10_000, "method cardinality");
      long self = 0;
      for (var entry : profile.methods().entrySet()) {
        text(entry.getKey(), 256);
        var method = entry.getValue();
        nonnegative(method.inclusive(), method.self());
        require(
            method.self() <= method.inclusive() && method.inclusive() <= profile.sampleCount(),
            "method sample counts");
        self = Math.addExact(self, method.self());
      }
      require(self <= profile.sampleCount(), "self sample denominator");
      require(profile.windows().size() <= 900, "window cardinality");
      long windowSamples = 0;
      long previousEnd = -1;
      for (var entry : profile.windows().entrySet()) {
        var window = entry.getValue();
        nonnegative(
            window.startNanos(),
            window.endNanos(),
            window.samples(),
            window.allocatedBytes(),
            window.gcNanos(),
            window.contentionNanos(),
            window.parkNanos());
        require(
            entry.getKey() == window.startNanos() && window.startNanos() < window.endNanos(),
            "window coordinates");
        require(window.samples() <= profile.sampleCount(), "window sample denominator");
        windowSamples = Math.addExact(windowSamples, window.samples());
        require(windowSamples <= profile.sampleCount(), "windows duplicate observed samples");
        require(window.startNanos() >= previousEnd, "overlapping event windows");
        require(
            window.startNanos() <= profile.endNanos() && window.endNanos() > profile.startNanos(),
            "window outside observed event span");
        previousEnd = window.endNanos();
      }
      require(profile.allocations().size() <= 10_000, "allocation cardinality");
      for (var entry : profile.allocations().entrySet()) {
        text(entry.getKey(), 256);
        nonnegative(entry.getValue().events(), entry.getValue().observedWeightedBytes());
        require(
            entry.getValue().events() != 0 || entry.getValue().observedWeightedBytes() == 0,
            "allocation weight without an observed event");
      }
      timings(profile.monitors(), 64);
      timings(profile.parks(), 64);
      timings(profile.timings(), 128);
      counters(profile.counters(), 128);
    } catch (NullPointerException | ArithmeticException invalid) {
      throw new IOException("invalid report aggregate", invalid);
    }
  }

  private static void flame(StackAggregator.Flame node, int depth, int[] count) throws IOException {
    require(depth <= 128 && ++count[0] <= 10_001, "flame depth/node bounds");
    text(node.frame(), 256);
    nonnegative(node.inclusive(), node.self());
    long accounted = node.self();
    Set<String> names = new HashSet<>();
    for (var child : node.children()) {
      require(names.add(child.frame()), "duplicate flame child");
      accounted = Math.addExact(accounted, child.inclusive());
      flame(child, depth + 1, count);
    }
    require(accounted <= node.inclusive(), "flame inclusive/self counts");
  }

  private static void timings(Map<String, ContentionSummary.Timing> values, int limit)
      throws IOException {
    require(values.size() <= limit, "histogram cardinality");
    for (var entry : values.entrySet()) {
      text(entry.getKey(), 256);
      var timing = entry.getValue();
      nonnegative(
          timing.events(),
          timing.totalNanos(),
          timing.p50Nanos(),
          timing.p95Nanos(),
          timing.p99Nanos());
      require(
          timing.p50Nanos() <= timing.p95Nanos() && timing.p95Nanos() <= timing.p99Nanos(),
          "histogram quantile order");
      require(
          timing.events() != 0 || timing.totalNanos() == 0 && timing.p99Nanos() == 0,
          "timing measurement without an observed event");
      require(timing.totalNanos() != 0 || timing.p99Nanos() == 0, "quantile without duration");
    }
  }

  private static void properties(Map<String, String> values, int limit) throws IOException {
    require(values != null && values.size() <= limit, "metadata cardinality");
    for (var entry : values.entrySet()) {
      text(entry.getKey(), 256);
      text(entry.getValue(), 4096);
    }
  }

  private static void counters(Map<String, ? extends Number> values, int limit) throws IOException {
    require(values != null && values.size() <= limit, "counter cardinality");
    for (var entry : values.entrySet()) {
      text(entry.getKey(), 256);
      nonnegative(entry.getValue().longValue());
    }
  }

  private static void nonnegative(long... values) throws IOException {
    for (long value : values) require(value >= 0, "negative aggregate");
  }

  private static void text(String value, int maximum) throws IOException {
    require(value != null && !value.isEmpty() && value.length() <= maximum, "report text bounds");
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }
}
