package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
import io.github.cheshmakzanoon.portfolio.threadlens.jfr.*;
import io.github.cheshmakzanoon.portfolio.threadlens.report.*;
import io.github.cheshmakzanoon.portfolio.threadlens.workloads.ControlledWorkload;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Final end-to-end stress: real live JFR plus explicitly synthetic high-cardinality ingress. */
public final class ObserverStress {
  private ObserverStress() {}

  public static void main(String[] args) throws Exception {
    int seconds = Integer.parseInt(args[0]);
    Path directory = Path.of(args[1]);
    Files.createDirectories(directory);
    if (seconds < 1) throw new IllegalArgumentException("positive seconds");
    var controller = new RecordingController(directory, 65_536);
    controller.observer(e -> LockSupport.parkNanos(10_000_000));
    var progress = new java.util.concurrent.atomic.AtomicLongArray(4);
    AtomicBoolean hostFailed = new AtomicBoolean();
    AtomicBoolean running = new AtomicBoolean(true);
    List<Thread> hosts = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int worker = i;
      hosts.add(
          (i < 2 ? Thread.ofPlatform() : Thread.ofVirtual())
              .name("fixture-secret-never-export-" + i)
              .unstarted(
                  () -> {
                    while (running.get()) {
                      ControlledWorkload.operation(
                          worker == 0 ? "cpu" : worker == 1 ? "allocation" : "contention", true);
                      progress.incrementAndGet(worker);
                      LockSupport.parkNanos(100_000);
                    }
                  }));
    }
    for (Thread host : hosts)
      host.setUncaughtExceptionHandler((thread, failure) -> hostFailed.set(true));
    controller.start();
    hosts.forEach(Thread::start);
    long began = System.nanoTime(),
        deadline = began + seconds * 1_000_000_000L,
        offered = 0,
        peakHeap = 0,
        peakDirectory = 0;
    while (System.nanoTime() < deadline) {
      long now = EventNormalizer.epochNanos(Instant.now());
      for (int i = 0; i < 1000; i++) {
        long identity = offered++;
        controller.publish(
            new NormalizedEvent(
                NormalizedEvent.Kind.SAMPLE,
                now,
                1,
                "[synthetic stress sample]",
                List.of("[synthetic ingress]", "cardinality-" + identity)));
      }
      peakHeap =
          Math.max(
              peakHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
      peakDirectory = Math.max(peakDirectory, RecordingController.directoryBytes(directory));
      LockSupport.parkNanos(20_000_000);
    }
    running.set(false);
    for (Thread host : hosts) host.join(10_000);
    controller.stopAndDump(directory.resolve("observer.jfr"));
    Profile profile = controller.profile();
    Map<String, Object> capabilities = new TreeMap<>();
    controller.capabilities().forEach(capabilities::put);
    ReportDocument report =
        ReportDocument.create(
            "live-jfr-plus-explicit-synthetic-cardinality-stress",
            profile,
            EventCapabilities.settings(controller.capabilities()),
            capabilities,
            Map.of(
                "queueDrops",
                controller.drops(),
                "consumerFailures",
                controller.consumerFailures(),
                "callbackFailures",
                controller.callbackFailures()),
            Map.of(
                "queueCapacity",
                65536,
                "queueConservativePayloadBytes",
                64 * 1024 * 1024,
                "stackNodes",
                10000,
                "windowCount",
                900));
    ReportWriter.json(directory.resolve("report.json"), report);
    ReportWriter.html(directory.resolve("report.html"), report);
    String export = Files.readString(directory.resolve("report.json"));
    boolean secretAbsent = !export.contains("fixture-secret-never-export");
    boolean incomplete =
        profile.windows().values().stream().allMatch(WindowAccumulator.Window::incomplete);
    long bytes = RecordingController.directoryBytes(directory);
    double elapsed = (System.nanoTime() - began) / 1e9;
    var result = new TreeMap<String, Object>();
    result.put(
        "hostOperations",
        List.of(progress.get(0), progress.get(1), progress.get(2), progress.get(3)));
    result.put("hostWorkerFailed", hostFailed.get());
    result.put("requestedSeconds", seconds);
    result.put("elapsedSeconds", elapsed);
    result.put("syntheticIngressOffered", offered);
    result.put("queueDrops", controller.drops());
    result.put("peakConservativeQueuePayloadBytes", controller.peakQueueBytes());
    result.put("queueRetainedBytesAfterDrain", controller.queueRetainedBytes());
    result.put("peakObservedHeapBytes", peakHeap);
    result.put("observerDirectoryBytes", bytes);
    result.put("peakObserverDirectoryBytes", Math.max(bytes, peakDirectory));
    result.put("recordingBytes", Files.size(directory.resolve("observer.jfr")));
    result.put("profileEventCount", profile.eventCount());
    result.put("boundedMethods", profile.methods().size());
    result.put("boundedWindows", profile.windows().size());
    result.put("overflowCounters", profile.counters());
    result.put("allWindowsIncomplete", incomplete);
    result.put("fixtureSecretAbsent", secretAbsent);
    result.put("stopReason", controller.stopReason());
    result.put("contention", "functional stress on shared build host; not overhead evidence");
    boolean hostProgress = !hostFailed.get();
    for (int i = 0; i < 4; i++) hostProgress &= progress.get(i) > 0 && !hosts.get(i).isAlive();
    boolean passed =
        hostProgress
            && elapsed >= seconds
            && controller.peakQueueBytes() <= EventQueue.DEFAULT_BYTES
            && controller.queueRetainedBytes() == 0
            && controller.drops() > 0
            && incomplete
            && secretAbsent
            && bytes <= RecordingController.DIRECTORY_BYTES
            && profile.methods().size() <= 10_000
            && profile.windows().size() <= 900;
    result.put("passed", passed);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("stress-summary.json").toFile(), result);
    if (!passed) throw new AssertionError("observer stress invariant failed");
  }
}
