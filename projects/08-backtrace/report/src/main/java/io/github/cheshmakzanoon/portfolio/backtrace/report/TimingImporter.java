package io.github.cheshmakzanoon.portfolio.backtrace.report;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.TreeMap;

/** Reads bounded histogram summaries preferentially; legacy raw TSV is only a fallback. */
public final class TimingImporter {
  private static final java.util.Set<String> STATUS_FIELDS =
      java.util.Set.of(
          "statusVersion",
          "statusComplete",
          "collectionHealthy",
          "queueDrops",
          "shutdownDiscarded",
          "incomplete",
          "probeFailures",
          "methodOverflow",
          "timingHistogramOverflow",
          "durationClipped",
          "observedTimingEvents",
          "acceptedTimingReservations",
          "producerQuiesced",
          "unfinishedProbeExits",
          "rawTimingRowsOmitted",
          "activeMethodIdLimit",
          "activeQueueCapacity",
          "skips");

  public record Result(
      Map<String, ContentionSummary.Timing> timings,
      long exits,
      long exceptionalExits,
      long unknownMethodIds,
      long overflow,
      long queueDrops,
      long probeFailures,
      long shutdownDiscarded,
      boolean statusPresent,
      boolean collectionHealthy,
      boolean incomplete,
      long rawRowsOmitted,
      long durationClipped,
      boolean statusComplete,
      long producerObservedEvents,
      long acceptedReservations,
      boolean producerQuiesced,
      long unaccountedEvents,
      long unfinishedProbeExits,
      long methodSelectionOverflow,
      Map<String, Long> selectionSkips,
      Map<String, Integer> activeLimits) {}

  public Result read(Path directory) throws IOException {
    try {
      Map<Integer, String> methods = methods(directory.resolve("methods.tsv"));
      TreeMap<String, ContentionSummary.Timing> timings = new TreeMap<>();
      long exits = 0, exceptions = 0, unknown = 0, overflow = 0;
      Path summary = directory.resolve("timing-summary.tsv");
      boolean fullSummary = Files.exists(summary);
      if (fullSummary) {
        if (Files.size(summary) > 65536) throw new IOException("timing summary bounds");
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        try (var reader = Files.newBufferedReader(summary, StandardCharsets.UTF_8)) {
          if (!"methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos"
              .equals(readBoundedLine(reader, 256)))
            throw new IOException("invalid histogram summary header");
          String line;
          while ((line = readBoundedLine(reader, 256)) != null) {
            if (line.length() > 256) throw new IOException("timing summary row bounds");
            String[] row = line.split("\\t", -1);
            if (row.length != 7 || ids.size() >= 128)
              throw new IOException("timing histogram cardinality");
            int id = Integer.parseInt(row[0]);
            if (!ids.add(id)) throw new IOException("duplicate timing histogram id");
            long count = nonnegative(row[1]),
                failed = nonnegative(row[2]),
                total = nonnegative(row[3]),
                p50 = nonnegative(row[4]),
                p95 = nonnegative(row[5]),
                p99 = nonnegative(row[6]);
            if (failed > count || p50 > p95 || p95 > p99)
              throw new IOException("invalid timing histogram counts/quantiles");
            String method = methods.get(id);
            if (method == null) {
              unknown = Math.addExact(unknown, count);
              continue;
            }
            timings.put(method, new ContentionSummary.Timing(count, total, p50, p95, p99));
            exits = Math.addExact(exits, count);
            exceptions = Math.addExact(exceptions, failed);
          }
        }
      } else {
        Path events = directory.resolve("timings.tsv");
        if (Files.size(events) > 256L * 1024 * 1024)
          throw new IOException("timing input budget exceeded");
        var histogram = new ContentionSummary(128);
        try (var reader = Files.newBufferedReader(events, StandardCharsets.UTF_8)) {
          if (!"methodId\tstartedMonotonicNanos\tdurationNanos\texceptional"
              .equals(readBoundedLine(reader, 128))) throw new IOException("invalid timing header");
          String line;
          while ((line = readBoundedLine(reader, 128)) != null) {
            if (line.length() > 128) throw new IOException("timing row bounds");
            String[] row = line.split("\\t", -1);
            if (row.length != 4) throw new IOException("invalid timing row");
            int id = Integer.parseInt(row[0]);
            Long.parseLong(row[1]);
            long duration = nonnegative(row[2]);
            boolean failed = bool(row[3]);
            String method = methods.get(id);
            if (method == null) {
              unknown++;
              continue;
            }
            histogram.accept(method, duration);
            exits++;
            if (failed) exceptions++;
          }
        }
        timings.putAll(histogram.snapshot());
        overflow = histogram.overflow();
      }
      long drops = 0,
          failures = 0,
          discarded = 0,
          rawOmitted = 0,
          clipped = 0,
          selectionOverflow = 0;
      Map<String, Long> skips = Map.of();
      long observed = -1, accepted = -1, unfinished = -1;
      int version = 0;
      boolean completeMarker = false, quiesced = false;
      java.util.Set<String> seen = new java.util.HashSet<>();
      boolean healthy = false, incomplete = true;
      Map<String, Integer> active = new TreeMap<>();
      Path status = directory.resolve("observer-status.txt");
      if (Files.exists(status)) {
        if (Files.size(status) > 16384) throw new IOException("observer status bounds");
        for (String line : Files.readAllLines(status)) {
          String[] row = line.split("=", 2);
          if (row.length != 2) continue;
          if (STATUS_FIELDS.contains(row[0]) && !seen.add(row[0]))
            throw new IOException("duplicate timing status field: " + row[0]);
          switch (row[0]) {
            case "statusVersion" -> version = Math.toIntExact(nonnegative(row[1]));
            case "statusComplete" -> completeMarker = bool(row[1]);
            case "queueDrops" -> drops = nonnegative(row[1]);
            case "probeFailures" -> failures = nonnegative(row[1]);
            case "shutdownDiscarded" -> discarded = nonnegative(row[1]);
            case "collectionHealthy" -> healthy = bool(row[1]);
            case "incomplete" -> incomplete = bool(row[1]);
            case "timingHistogramOverflow" ->
                overflow = Math.addExact(overflow, nonnegative(row[1]));
            case "rawTimingRowsOmitted" -> rawOmitted = nonnegative(row[1]);
            case "durationClipped" -> clipped = nonnegative(row[1]);
            case "methodOverflow" -> selectionOverflow = nonnegative(row[1]);
            case "observedTimingEvents" -> observed = nonnegative(row[1]);
            case "acceptedTimingReservations" -> accepted = nonnegative(row[1]);
            case "producerQuiesced" -> quiesced = bool(row[1]);
            case "unfinishedProbeExits" -> unfinished = nonnegative(row[1]);
            case "skips" -> skips = selectionSkips(row[1]);
            case "activeMethodIdLimit" ->
                active.put("methodIds", Math.toIntExact(nonnegative(row[1])));
            case "activeQueueCapacity" ->
                active.put("queueCapacity", Math.toIntExact(nonnegative(row[1])));
            default -> {}
          }
        }
      }
      boolean completeStatus = version == 2 && completeMarker && seen.containsAll(STATUS_FIELDS);
      long accounted = Math.addExact(exits, unknown);
      if (fullSummary) accounted = Math.addExact(accounted, overflow);
      if (observed >= 0 && accounted > observed)
        throw new IOException("timing summary exceeds producer observed total");
      long unaccounted = observed < 0 ? 0 : observed - accounted;
      boolean reservationMismatch =
          accepted >= 0 && observed >= 0 && accepted != Math.addExact(observed, discarded);
      return new Result(
          timings,
          exits,
          exceptions,
          unknown,
          overflow,
          drops,
          failures,
          discarded,
          Files.exists(status),
          healthy,
          incomplete
              || !completeStatus
              || !quiesced
              || unfinished > 0
              || unaccounted > 0
              || reservationMismatch
              || (!fullSummary && rawOmitted > 0)
              || !healthy
              || drops > 0
              || failures > 0
              || discarded > 0
              || unknown > 0
              || selectionOverflow > 0
              || overflow > 0
              || clipped > 0,
          rawOmitted,
          clipped,
          completeStatus,
          observed,
          accepted,
          quiesced,
          unaccounted,
          unfinished,
          selectionOverflow,
          skips,
          Map.copyOf(active));
    } catch (NumberFormatException | ArithmeticException invalid) {
      throw new IOException("invalid timing number", invalid);
    }
  }

  private static Map<Integer, String> methods(Path path) throws IOException {
    if (Files.size(path) > 4L * 1024 * 1024) throw new IOException("method registry budget");
    TreeMap<Integer, String> methods = new TreeMap<>();
    java.util.HashSet<String> identities = new java.util.HashSet<>();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      if (!"methodId\tidentity".equals(readBoundedLine(reader, 272)))
        throw new IOException("invalid method registry header");
      String line;
      while ((line = readBoundedLine(reader, 272)) != null) {
        String[] row = line.split("\\t", -1);
        if (row.length != 2 || row[1].length() > 256 || methods.size() >= 10_000)
          throw new IOException("method registry bounds");
        int id = Integer.parseInt(row[0]);
        if (id < 0 || id >= 10_000 || methods.putIfAbsent(id, row[1]) != null)
          throw new IOException("duplicate/invalid method id");
        if (row[1].isBlank() || !identities.add(row[1]))
          throw new IOException("duplicate/empty method identity");
      }
    }
    return methods;
  }

  /**
   * Enforces row limits while reading, before an untrusted line can allocate an unbounded String.
   */
  static String readBoundedLine(BufferedReader reader, int limit) throws IOException {
    StringBuilder line = new StringBuilder(Math.min(limit, 256));
    int value;
    while ((value = reader.read()) != -1) {
      if (value == '\n') return line.toString();
      if (value == '\r') {
        reader.mark(1);
        if (reader.read() != '\n') reader.reset();
        return line.toString();
      }
      if (line.length() == limit) throw new IOException("timing row exceeds character limit");
      line.append((char) value);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private static long nonnegative(String value) throws IOException {
    long parsed = Long.parseLong(value);
    if (parsed < 0) throw new IOException("negative timing value");
    return parsed;
  }

  private static boolean bool(String value) throws IOException {
    if (!value.equals("true") && !value.equals("false"))
      throw new IOException("invalid timing flag");
    return Boolean.parseBoolean(value);
  }

  private static Map<String, Long> selectionSkips(String value) throws IOException {
    if (value.length() > 4096 || !value.startsWith("{") || !value.endsWith("}"))
      throw new IOException("invalid selection skip metadata");
    TreeMap<String, Long> result = new TreeMap<>();
    String body = value.substring(1, value.length() - 1);
    if (body.isEmpty()) return Map.of();
    for (String item : body.split(", ", -1)) {
      String[] pair = item.split("=", -1);
      if (pair.length != 2 || !pair[0].matches("[A-Za-z][A-Za-z0-9]{0,63}") || result.size() == 32)
        throw new IOException("invalid selection skip entry");
      if (result.putIfAbsent(pair[0], nonnegative(pair[1])) != null)
        throw new IOException("duplicate selection skip reason");
    }
    return Map.copyOf(result);
  }

  public Profile merge(Profile profile, Result timing) {
    Map<String, Long> counters = new TreeMap<>(profile.counters());
    counters.put("observedTimingExits", timing.exits());
    counters.put("observedExceptionalTimingExits", timing.exceptionalExits());
    counters.put("timingUnknownIds", timing.unknownMethodIds());
    counters.put("timingOverflow", timing.overflow());
    counters.put("timingQueueDrops", timing.queueDrops());
    counters.put("probeFailures", timing.probeFailures());
    counters.put("timingShutdownDiscarded", timing.shutdownDiscarded());
    counters.put("timingStatusMissing", timing.statusPresent() ? 0L : 1L);
    counters.put("timingStatusComplete", timing.statusComplete() ? 1L : 0L);
    counters.put("timingSelectionCoverageKnown", timing.statusComplete() ? 1L : 0L);
    counters.put("timingProducerQuiesced", timing.producerQuiesced() ? 1L : 0L);
    if (timing.acceptedReservations() >= 0)
      counters.put("timingAcceptedReservations", timing.acceptedReservations());
    counters.put("timingProducerCountUnknown", timing.producerObservedEvents() < 0 ? 1L : 0L);
    if (timing.producerObservedEvents() >= 0)
      counters.put("timingProducerObservedEvents", timing.producerObservedEvents());
    counters.put("timingUnaccountedEvents", timing.unaccountedEvents());
    if (timing.unfinishedProbeExits() >= 0)
      counters.put("timingUnfinishedProbeExits", timing.unfinishedProbeExits());
    counters.put("timingCollectionUnhealthy", timing.collectionHealthy() ? 0L : 1L);
    counters.put("timingIncomplete", timing.incomplete() ? 1L : 0L);
    counters.put("rawTimingPrefixRowsOmitted", timing.rawRowsOmitted());
    counters.put("timingDurationClipped", timing.durationClipped());
    counters.put("timingMethodSelectionOverflow", timing.methodSelectionOverflow());
    timing
        .selectionSkips()
        .forEach((reason, count) -> counters.put("timingSelectionSkip." + reason, count));
    return new Profile(
        profile.eventCount(),
        profile.sampleCount(),
        profile.startNanos(),
        profile.endNanos(),
        profile.flame(),
        profile.methods(),
        profile.windows(),
        profile.allocations(),
        profile.monitors(),
        profile.parks(),
        timing.timings(),
        counters);
  }
}
