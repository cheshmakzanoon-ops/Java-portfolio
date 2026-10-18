package io.github.cheshmakzanoon.portfolio.indexforge;

import com.sun.management.OperatingSystemMXBean;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PerformanceHarness {
  private PerformanceHarness() {}

  public static void run(Path data, Path output, int documents, int seconds) throws Exception {
    if (Runtime.getRuntime().maxMemory() > 2147483648L)
      throw new IllegalArgumentException("declare heap at most2GiB");
    AuthorizationFilter auth = new AuthorizationFilter();
    List<AuthorizationFilter.Access> access = new ArrayList<>();
    for (int t = 0; t < 3; t++)
      access.add(auth.update("load" + t, "tenant" + t, Set.of("public"), true));
    Files.createDirectories(output);
    try (SearchEngine engine = new SearchEngine(data, auth)) {
      var generated = WorkloadGenerator.generate(engine.contentStore(), documents, 7007);
      for (int t = 0; t < 3; t++)
        engine.ingest(
            access.get(t),
            "benchmark-" + documents,
            generated.manifests().get(t),
            GenerationBuilder.Fault.none(),
            () -> false);
      for (int i = 0; i < 150; i++) engine.search("load" + (i % 3), request(i, documents));
      OperatingSystemMXBean os =
          (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      List<String> runs = new ArrayList<>();
      for (int run = 0; run < 3; run++) {
        long gcBefore = gcTime(), cpuBefore = os.getProcessCpuTime(), start = System.nanoTime();
        int requests = seconds * 30;
        long[] latency = new long[requests];
        AtomicInteger errors = new AtomicInteger(),
            queued = new AtomicInteger(),
            peak = new AtomicInteger();
        AtomicLong allocated = new AtomicLong();
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocationSupported = threads.isThreadAllocatedMemorySupported();
        if (allocationSupported && !threads.isThreadAllocatedMemoryEnabled())
          threads.setThreadAllocatedMemoryEnabled(true);
        try (ExecutorService clients = Executors.newFixedThreadPool(8)) {
          List<Future<?>> tasks = new ArrayList<>();
          for (int i = 0; i < requests; i++) {
            long scheduled = start + (long) i * 1_000_000_000L / 30;
            long delay = scheduled - System.nanoTime();
            if (delay > 0) java.util.concurrent.locks.LockSupport.parkNanos(delay);
            final int index = i;
            int q = queued.incrementAndGet();
            peak.accumulateAndGet(q, Math::max);
            tasks.add(
                clients.submit(
                    () -> {
                      long allocatedBefore =
                          allocationSupported
                              ? threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                              : 0;
                      try {
                        engine.search("load" + (index % 3), request(index, documents));
                      } catch (Exception e) {
                        errors.incrementAndGet();
                      } finally {
                        latency[index] = System.nanoTime() - scheduled;
                        if (allocationSupported)
                          allocated.addAndGet(
                              Math.max(
                                  0,
                                  threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
                                      - allocatedBefore));
                        queued.decrementAndGet();
                      }
                    }));
          }
          for (Future<?> task : tasks) task.get(60, TimeUnit.SECONDS);
        }
        long duration = System.nanoTime() - start;
        long cpu = os.getProcessCpuTime() - cpuBefore;
        Arrays.sort(latency);
        StringBuilder raw = new StringBuilder("latencyNanos\n");
        for (long l : latency) raw.append(l).append('\n');
        Files.writeString(output.resolve("run-" + run + "-latencies.csv"), raw);
        String json =
            "{\"run\":"
                + run
                + ",\"requests\":"
                + requests
                + ",\"offeredRps\":30,\"completedRps\":"
                + (requests * 1e9 / duration)
                + ",\"errors\":"
                + errors
                + ",\"p50Ms\":"
                + percentile(latency, .5)
                + ",\"p95Ms\":"
                + percentile(latency, .95)
                + ",\"p99Ms\":"
                + percentile(latency, .99)
                + ",\"gcMillis\":"
                + (gcTime() - gcBefore)
                + ",\"processCpuNanos\":"
                + cpu
                + ",\"threadAllocatedBytes\":"
                + allocated
                + ",\"allocationSupported\":"
                + allocationSupported
                + ",\"queuePeak\":"
                + peak
                + ",\"durationNanos\":"
                + duration
                + "}";
        runs.add(json);
        System.out.println(json);
      }
      String metadata =
          "{\"schemaVersion\":1,\"boundary\":\"shared application search including leases ACL facets explanations and warm bounded response cache; excludes HTTP\",\"seed\":7007,\"documents\":"
              + documents
              + ",\"sourceBytes\":"
              + generated.bytes()
              + ",\"heapMaxBytes\":"
              + Runtime.getRuntime().maxMemory()
              + ",\"warmupRequests\":150,\"runs\":["
              + String.join(",", runs)
              + "]}";
      Files.writeString(output.resolve("summary.json"), metadata);
    }
  }

  private static int runOffset(int i) {
    return i * 17;
  }

  private static SearchEngine.Request request(int i, int documents) {
    return new SearchEngine.Request(
        i % 4 == 0
            ? "atomic publication"
            : i % 4 == 1
                ? "source repository"
                : i % 4 == 2
                    ? "resolveGeneration"
                        + (((i * 31) % Math.max(1, (documents - 3) / 3)) * 3 + i % 3)
                    : "reader lease",
        10,
        null,
        Map.of());
  }

  private static long gcTime() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(GarbageCollectorMXBean::getCollectionTime)
        .filter(n -> n >= 0)
        .sum();
  }

  private static double percentile(long[] sorted, double p) {
    return sorted[(int) Math.ceil(sorted.length * p) - 1] / 1e6;
  }
}
