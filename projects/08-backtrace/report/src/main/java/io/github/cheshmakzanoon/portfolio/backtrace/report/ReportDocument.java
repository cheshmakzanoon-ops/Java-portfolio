package io.github.cheshmakzanoon.portfolio.backtrace.report;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.Profile;
import java.util.Map;

public record ReportDocument(
    int schemaVersion,
    String recordingId,
    String jdkVendor,
    String jdkVersion,
    String os,
    String architecture,
    Map<String, String> eventSettings,
    long startNanos,
    long endNanos,
    long sampleCount,
    Map<String, Long> lossCounters,
    Map<String, Object> supportedCapabilities,
    Map<String, Integer> aggregationLimits,
    Map<String, Integer> activeCollectionLimits,
    Map<String, String> units,
    String missingEventStatus,
    Profile profile) {
  public static ReportDocument create(
      String id,
      Profile profile,
      Map<String, String> settings,
      Map<String, Object> capabilities,
      Map<String, Long> losses) {
    return create(id, profile, settings, capabilities, losses, Map.of());
  }

  public static ReportDocument create(
      String id,
      Profile profile,
      Map<String, String> settings,
      Map<String, Object> capabilities,
      Map<String, Long> losses,
      Map<String, Integer> activeLimits) {
    return new ReportDocument(
        1,
        id,
        System.getProperty("java.vendor"),
        System.getProperty("java.runtime.version"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        settings,
        profile.startNanos(),
        profile.endNanos(),
        profile.sampleCount(),
        losses,
        capabilities,
        Map.of(
            "queueCapacityCeiling",
            65_536,
            "methodIdCeiling",
            10_000,
            "stackDepth",
            128,
            "stringCharacters",
            256,
            "stackNodes",
            10_000,
            "windows",
            900,
            "histogramSubjects",
            256),
        Map.copyOf(activeLimits),
        Map.of(
            "execution",
            "observed samples; proportion denominator is all execution sample events",
            "allocation",
            "observed weighted bytes; configuration-dependent sampling coverage",
            "contention",
            "observed event wall duration in nanoseconds; threshold declared in settings; unknown without manifest",
            "parking",
            "observed event wall duration in nanoseconds; threshold declared in settings; unknown without manifest"
                + ("false".equals(settings.get("jdk.ThreadPark#stackTrace"))
                    ? "; parked class attribution only, producer park stacks disabled"
                    : ""),
            "timing",
            "selected observed invocations, wall nanoseconds inside injected bytecode; implicit synchronized-method monitor acquisition before bytecode excluded; collection interval is separate from the imported recording",
            "timeline",
            "fixed one-second event-start-time buckets; duration assigned to start bucket"),
        profile.counters().getOrDefault("timingIncomplete", 0L) > 0
            ? "Selected-method timing coverage incomplete or unknown; consult timing loss counters. Execution samples remain sampled evidence."
            : profile.sampleCount() == 0
                ? "No execution samples observed: unavailable or insufficient recording, not zero CPU activity"
                : "See capabilities and loss counters; observed samples do not measure exact CPU time or invocation counts",
        profile);
  }
}
