package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
import io.github.cheshmakzanoon.portfolio.threadlens.jfr.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Real elapsed-time, intentionally slow-consumer stress; synthetic cardinality, never fake JFR
 * samples.
 */
public final class BoundedStress {
  private BoundedStress() {}

  public static void main(String[] args) throws Exception {
    int seconds = Integer.parseInt(args[0]);
    Path output = Path.of(args[1]);
    if (seconds < 1) throw new IllegalArgumentException("positive seconds");
    EventQueue queue = new EventQueue(65_536);
    ProfileAccumulator accumulator = new ProfileAccumulator(10_000, 1_000_000_000L, 900);
    long started = System.nanoTime(),
        deadline = started + seconds * 1_000_000_000L,
        produced = 0,
        consumed = 0,
        peakHeap = 0;
    String wallStart = Instant.now().toString();
    while (System.nanoTime() < deadline) {
      for (int n = 0; n < 1000; n++) {
        long id = produced++;
        queue.offer(
            new NormalizedEvent(
                NormalizedEvent.Kind.SAMPLE,
                id * 1_000_000_000L,
                1,
                "synthetic",
                List.of("root", "cardinality-" + id)));
      }
      for (int n = 0; n < 10; n++) {
        var event = queue.poll();
        if (event != null) {
          accumulator.accept(event);
          consumed++;
        }
      }
      peakHeap =
          Math.max(
              peakHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      LockSupport.parkNanos(10_000_000);
    }
    NormalizedEvent event;
    while ((event = queue.poll()) != null) {
      accumulator.accept(event);
      consumed++;
    }
    accumulator.markIncomplete();
    Profile profile = accumulator.snapshot();
    double elapsed = (System.nanoTime() - started) / 1e9;
    if (queue.drops() == 0
        || profile.methods().size() > 10_000
        || profile.windows().size() > 900
        || profile.flame().children().size() > 10_000
        || elapsed < seconds) throw new AssertionError("bounded stress invariant");
    Map<String, Object> report =
        Map.ofEntries(
            Map.entry("schemaVersion", 1),
            Map.entry("durationSeconds", elapsed),
            Map.entry("requestedSeconds", seconds),
            Map.entry("started", wallStart),
            Map.entry("ended", Instant.now().toString()),
            Map.entry("contention", "shared coding/build host; not reference performance evidence"),
            Map.entry("produced", produced),
            Map.entry("consumed", consumed),
            Map.entry("queueDrops", queue.drops()),
            Map.entry("peakObservedHeapBytes", peakHeap),
            Map.entry("methods", profile.methods().size()),
            Map.entry("windows", profile.windows().size()),
            Map.entry("counters", profile.counters()),
            Map.entry(
                "allWindowsIncomplete",
                profile.windows().values().stream().allMatch(w -> w.incomplete())));
    Files.createDirectories(output.toAbsolutePath().getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }
}
