package io.github.cheshmakzanoon.portfolio.threadlens.workloads;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.HdrHistogram.Histogram;

/**
 * Closed-loop application benchmark; operation latency omits queue delay and is explicitly
 * labelled.
 */
public final class WorkloadMain {
  public static final String COLLECTION_CONFIGURATION = "v3-allocation50-park-class-only";

  private WorkloadMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 7)
      throw new IllegalArgumentException(
          "kind regressed warmupSeconds measurementSeconds mode outputJson recordingJfr");
    String kind = args[0], mode = args[4];
    boolean regressed = Boolean.parseBoolean(args[1]);
    int warmup = Integer.parseInt(args[2]), seconds = Integer.parseInt(args[3]);
    Path output = Path.of(args[5]);
    if (warmup < 0
        || seconds < 1
        || seconds > 3600
        || !List.of("baseline", "jfr", "instrumented").contains(mode))
      throw new IllegalArgumentException("invalid duration/mode");
    Recording recording = null;
    if (!mode.equals("baseline")) {
      recording = new Recording();
      recording.setName("ThreadLens controlled workload");
      recording.setMaxAge(Duration.ofMinutes(15));
      recording.setMaxSize(256L * 1024 * 1024);
      recording.setToDisk(true);
      List<String> available =
          FlightRecorder.getFlightRecorder().getEventTypes().stream()
              .map(EventType::getName)
              .toList();
      if (available.contains("jdk.ExecutionSample"))
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20)).withStackTrace();
      if (available.contains("jdk.ObjectAllocationSample"))
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "50/s").withStackTrace();
      for (String name : List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark"))
        if (available.contains(name)) {
          var setting = recording.enable(name).withThreshold(Duration.ofMillis(2));
          if (name.equals("jdk.ThreadPark")) setting.withoutStackTrace();
          else setting.withStackTrace();
        }
      for (String name : List.of("jdk.GarbageCollection", "jdk.DataLoss"))
        if (available.contains(name)) recording.enable(name).withoutStackTrace();
      recording.start();
    }
    int workers = kind.equals("cpu") || kind.equals("allocation") ? 1 : 4;
    run(kind, regressed, warmup, workers, false);
    // Recording includes the warmup for inspection; report explicitly declares measurement
    // boundaries.
    long gcBefore = gcCount(), gcMsBefore = gcMillis();
    String wall = Instant.now().toString();
    long nano = System.nanoTime();
    Result result = run(kind, regressed, seconds, workers, true);
    double elapsed = (System.nanoTime() - nano) / 1e9;
    if (recording != null) {
      recording.stop();
      recording.dump(Path.of(args[6]));
      writeSettings(recording, Path.of(args[6] + ".settings.json"));
      recording.close();
    }
    Files.createDirectories(output.toAbsolutePath().getParent());
    String json =
        "{\n\"collectionConfiguration\":\""
            + COLLECTION_CONFIGURATION
            + "\",\"kind\":\""
            + kind
            + "\",\"regressed\":"
            + regressed
            + ",\"mode\":\""
            + mode
            + "\",\"started\":\""
            + wall
            + "\",\"warmupSeconds\":"
            + warmup
            + ",\"requestedMeasurementSeconds\":"
            + seconds
            + ",\"actualMeasurementSeconds\":"
            + elapsed
            + ",\"workers\":"
            + workers
            + ",\"operations\":"
            + result.operations
            + ",\"throughputOpsPerSecond\":"
            + (result.operations / elapsed)
            + ",\"latencyP50Nanos\":"
            + result.histogram.getValueAtPercentile(50)
            + ",\"latencyP95Nanos\":"
            + result.histogram.getValueAtPercentile(95)
            + ",\"latencyP99Nanos\":"
            + result.histogram.getValueAtPercentile(99)
            + ",\"gcCollections\":"
            + (gcCount() - gcBefore)
            + ",\"gcMillis\":"
            + (gcMillis() - gcMsBefore)
            + ",\"rssPeakKiB\":"
            + rssPeak()
            + ",\"coordinatedOmission\":\"closed-loop workers; no arrival-queue delay measured\",\"recordingIncludesWarmup\":true}\n";
    Files.writeString(output, json);
    if (result.operations < 1) throw new AssertionError("workload made no progress");
  }

  private record Result(long operations, Histogram histogram) {}

  private static Result run(
      String kind, boolean regression, int seconds, int workers, boolean measure)
      throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    long[] counts = new long[workers];
    Histogram[] histograms = new Histogram[workers];
    for (int i = 0; i < workers; i++) {
      int index = i;
      histograms[i] = new Histogram(1, 60_000_000_000L, 3);
      Runnable job =
          () -> {
            ready.countDown();
            try {
              start.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            while (running.get()) {
              long before = System.nanoTime();
              ControlledWorkload.operation(kind, regression);
              if (measure)
                histograms[index].recordValue(
                    Math.min(60_000_000_000L, System.nanoTime() - before));
              counts[index]++;
            }
          };
      threads.add(
          (kind.equals("virtual") || (kind.equals("mixed") && i >= 2)
                  ? Thread.ofVirtual()
                  : Thread.ofPlatform())
              .unstarted(job));
    }
    threads.forEach(Thread::start);
    ready.await();
    start.countDown();
    Thread.sleep(seconds * 1000L);
    running.set(false);
    for (Thread thread : threads) thread.join(10_000);
    Histogram merged = new Histogram(1, 60_000_000_000L, 3);
    long operations = 0;
    for (int i = 0; i < workers; i++) {
      operations += counts[i];
      merged.add(histograms[i]);
      if (threads.get(i).isAlive()) throw new AssertionError("worker failed to stop");
    }
    return new Result(operations, merged);
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0, b.getCollectionCount()))
        .sum();
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0, b.getCollectionTime()))
        .sum();
  }

  private static long rssPeak() {
    try {
      return Files.readAllLines(Path.of("/proc/self/status")).stream()
          .filter(s -> s.startsWith("VmHWM:"))
          .map(s -> s.replaceAll("[^0-9]", ""))
          .mapToLong(Long::parseLong)
          .findFirst()
          .orElse(-1);
    } catch (IOException unsupported) {
      return -1;
    }
  }

  private static void writeSettings(Recording recording, Path output) throws IOException {
    StringBuilder json = new StringBuilder("{");
    boolean comma = false;
    for (var entry : new java.util.TreeMap<>(recording.getSettings()).entrySet()) {
      if (comma) json.append(',');
      comma = true;
      json.append('"').append(entry.getKey()).append("\":\"").append(entry.getValue()).append('"');
    }
    json.append('}');
    Files.writeString(output, json);
  }
}
