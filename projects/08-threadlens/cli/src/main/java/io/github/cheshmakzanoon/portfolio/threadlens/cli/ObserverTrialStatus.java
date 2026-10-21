package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Validate the actual producer's terminal status, not inferred success from the presence of a
 * summary file. Incomplete instrumentation cannot be used for overhead acceptance even if the
 * application's throughput and latency look favorable.
 */
final class ObserverTrialStatus {
  record Totals(long accepted, long observed) {}

  private static final int MAX_STATUS_BYTES = 65_536;
  private static final Set<String> REQUIRED =
      Set.of(
          "statusVersion",
          "statusComplete",
          "collectionHealthy",
          "queueDrops",
          "shutdownDiscarded",
          "incomplete",
          "probeFailures",
          "methodOverflow",
          "timingHistogramOverflow",
          "observedTimingEvents",
          "acceptedTimingReservations",
          "producerQuiesced",
          "unfinishedProbeExits",
          "activeMethodIdLimit",
          "activeQueueCapacity");

  private ObserverTrialStatus() {}

  static Totals requireComplete(Path path) throws IOException {
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
      throw new IOException("missing or linked terminal observer status");
    if (Files.size(path) > MAX_STATUS_BYTES)
      throw new IOException("observer status exceeds its bounded output limit");
    Map<String, String> fields = new HashMap<>();
    for (String line : Files.readAllLines(path)) {
      if (line.isEmpty()) continue;
      int equals = line.indexOf('=');
      if (equals <= 0 || equals == line.length() - 1)
        throw new IOException("invalid observer status field");
      String name = line.substring(0, equals);
      String value = line.substring(equals + 1);
      if (!name.matches("[a-zA-Z][a-zA-Z0-9]*")
          || fields.putIfAbsent(name, value) != null)
        throw new IOException("duplicate or malformed observer status field");
    }
    if (!fields.keySet().containsAll(REQUIRED))
      throw new IOException("incomplete observer status field set");
    require(fields, "statusVersion", "2");
    require(fields, "statusComplete", "true");
    require(fields, "collectionHealthy", "true");
    require(fields, "incomplete", "false");
    require(fields, "producerQuiesced", "true");
    require(fields, "activeMethodIdLimit", "3");
    require(fields, "activeQueueCapacity", "65536");
    for (String name :
        Set.of(
            "queueDrops",
            "shutdownDiscarded",
            "probeFailures",
            "methodOverflow",
            "timingHistogramOverflow",
            "unfinishedProbeExits")) {
      if (parseUnsigned(fields, name) != 0)
        throw new IOException("instrumentation incomplete: " + name);
    }
    long accepted = parseUnsigned(fields, "acceptedTimingReservations");
    long observed = parseUnsigned(fields, "observedTimingEvents");
    if (accepted == 0 || observed != accepted)
      throw new IOException("accepted probe timings were not all observed");
    return new Totals(accepted, observed);
  }

  private static void require(Map<String, String> fields, String name, String expected)
      throws IOException {
    if (!expected.equals(fields.get(name)))
      throw new IOException("invalid terminal observer state: " + name);
  }

  private static long parseUnsigned(Map<String, String> fields, String name) throws IOException {
    try {
      String value = fields.get(name);
      if (value == null || !value.matches("(?:0|[1-9][0-9]*)"))
        throw new NumberFormatException(name);
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IOException("invalid nonnegative observer counter: " + name, error);
    }
  }
}
