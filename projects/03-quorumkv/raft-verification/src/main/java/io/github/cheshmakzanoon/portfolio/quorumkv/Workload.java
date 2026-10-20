package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Closed-loop system driver: TLS round trip + replicated fsync + quorum read barriers. */
public final class Workload {
  private Workload() {}

  public static void run(Path directory, int port, int operations, int runs) throws Exception {
    if (operations < 1 || operations > 100_000 || runs < 1 || runs > 3)
      throw new IllegalArgumentException("WORKLOAD_BOUNDS: operations 1..100000, runs 1..3");
    Files.createDirectories(directory);
    if (Files.exists(directory.resolve("environment.txt"))
        || Files.exists(directory.resolve("workload.csv")))
      throw new java.io.IOException("WORKLOAD_OUTPUT_EXISTS: use a fresh directory");
    StringBuilder raw =
        new StringBuilder(
            "run,profile,clients,operations,payloadBytes,elapsedSeconds,opsPerSecond,p50Micros,p95Micros,p99Micros,errors,clientGcCount,clientGcMs,replicationLag,maxSampledLag,clientCpuSeconds,membersCpuSeconds,workerAllocatedBytes,snapshotMicros,lagSamples,lagSampleFailures\n");
    Files.writeString(
        directory.resolve("environment.txt"),
        "timestamp="
            + java.time.Instant.now()
            + "\nos="
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch")
            + "\njdk="
            + System.getProperty("java.runtime.version")
            + " "
            + System.getProperty("java.vendor")
            + "\nprocessors="
            + Runtime.getRuntime().availableProcessors()
            + "\nclientMaxHeap="
            + Runtime.getRuntime().maxMemory()
            + "\nflags="
            + safeFlags(ManagementFactory.getRuntimeMXBean().getInputArguments())
            + "\nflagsPolicy=heap and ActiveProcessorCount only; application properties are excluded"
            + "\nsourceFingerprint="
            + System.getProperty("quorumkv.source", "unrecorded")
            + "\nseed=42\nfsync=every state transaction; atomic snapshot manifest + directory fsync\nmeasurement=closed-loop TLS replicated commit/quorum read; coordinated omission applies\nCPU="
            + Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                .filter(l -> l.startsWith("model name"))
                .findFirst()
                .orElse("unknown")
            + "\nmemberFlags=-Xms32m -Xmx512m; JFR profile enabled when quorumkv.record=true\nquota="
            + Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim()
            + "\nmemoryLimit="
            + Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim()
            + "\n",
        StandardOpenOption.CREATE_NEW);
    Files.writeString(
        directory.resolve("workload.csv"), raw.toString(), StandardOpenOption.CREATE_NEW);
    for (int run = 1; run <= runs; run++)
      for (String profile : List.of("read-heavy", "mixed"))
        for (int clients : new int[] {1, 8, 32}) {
          Path scenario = directory.resolve("run-" + run + "-" + profile + "-" + clients);
          try (ProcessCluster cluster = new ProcessCluster(scenario, port)) {
            cluster.start();
            try (KvClient warmup = new KvClient(cluster.settings(), "warmup")) {
              ClusterTool.leader(warmup, -1);
              for (int i = 0; i < 128; i++)
                ClusterTool.require(
                    warmup.mutate(Kind.PUT, key(i), payload(i), null).code().equals("OK"),
                    "warmup write");
            }
            long[] latencies = new long[operations];
            AtomicInteger cursor = new AtomicInteger(), errors = new AtomicInteger();
            long gc = gcCount(), gcMs = gcMillis();
            long clientCpu = processCpu(), memberCpu = cluster.cpuNanos();
            AtomicLong allocated = new AtomicLong(),
                maxLag = new AtomicLong(),
                lagSamples = new AtomicLong(),
                lagFailures = new AtomicLong();
            ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
            KvClient metricClient = new KvClient(cluster.settings(), "metrics");
            sampler.scheduleAtFixedRate(
                () -> {
                  try {
                    long high = 0, low = Long.MAX_VALUE;
                    for (int n = 0; n < 3; n++) {
                      var v = ClusterTool.status(metricClient, n);
                      high = Math.max(high, Long.parseLong(v.get("commit")));
                      low = Math.min(low, Long.parseLong(v.get("applied")));
                    }
                    maxLag.accumulateAndGet(Math.max(0, high - low), Math::max);
                    lagSamples.incrementAndGet();
                  } catch (Exception | AssertionError ignored) {
                    lagFailures.incrementAndGet();
                  }
                },
                100,
                250,
                TimeUnit.MILLISECONDS);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            List<Future<?>> futures = new ArrayList<>();
            long begin = System.nanoTime();
            for (int c = 0; c < clients; c++) {
              int identity = c;
              futures.add(
                  pool.submit(
                      () -> {
                        try (KvClient client =
                            new KvClient(cluster.settings(), "load-" + identity)) {
                          Random random = new Random(42L + identity);
                          start.await();
                          long allocationStart = threadAllocated();
                          while (true) {
                            int op = cursor.getAndIncrement();
                            if (op >= operations) break;
                            int k = random.nextInt(128);
                            boolean write =
                                random.nextInt(100) < (profile.equals("read-heavy") ? 10 : 50);
                            long t = System.nanoTime();
                            Result result =
                                write
                                    ? client.mutate(Kind.PUT, key(k), payload(k), null)
                                    : client.get(key(k));
                            latencies[op] = System.nanoTime() - t;
                            if (!result.code().equals("OK")) errors.incrementAndGet();
                          }
                          allocated.addAndGet(Math.max(0, threadAllocated() - allocationStart));
                        } catch (Exception e) {
                          throw new CompletionException(e);
                        }
                      }));
            }
            begin = System.nanoTime();
            start.countDown();
            try {
              for (Future<?> f : futures) f.get(45, TimeUnit.MINUTES);
            } finally {
              pool.shutdownNow();
              sampler.shutdownNow();
              sampler.awaitTermination(5, TimeUnit.SECONDS);
              metricClient.close();
            }
            double seconds = (System.nanoTime() - begin) / 1e9;
            Arrays.sort(latencies);
            long lag, snapshotMicros = 0;
            try (KvClient observation = new KvClient(cluster.settings(), "observe")) {
              long high = 0, low = Long.MAX_VALUE;
              for (int n = 0; n < 3; n++) {
                var status = ClusterTool.status(observation, n);
                snapshotMicros =
                    Math.max(snapshotMicros, Long.parseLong(status.get("snapshotMicros")));
                high = Math.max(high, Long.parseLong(status.get("commit")));
                low = Math.min(low, Long.parseLong(status.get("applied")));
                Files.writeString(
                    scenario.resolve("status-" + n + ".txt"), status.toString() + "\n");
              }
              lag = high - low;
            }
            String line =
                String.format(
                    Locale.ROOT,
                    "%d,%s,%d,%d,1024,%.6f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.6f,%.6f,%d,%d,%d,%d%n",
                    run,
                    profile,
                    clients,
                    operations,
                    seconds,
                    operations / seconds,
                    latencies[(int) (operations * .50)] / 1000.0,
                    latencies[Math.min(operations - 1, (int) (operations * .95))] / 1000.0,
                    latencies[Math.min(operations - 1, (int) (operations * .99))] / 1000.0,
                    errors.get(),
                    gcCount() - gc,
                    gcMillis() - gcMs,
                    lag,
                    maxLag.get(),
                    (processCpu() - clientCpu) / 1e9,
                    (cluster.cpuNanos() - memberCpu) / 1e9,
                    allocated.get(),
                    snapshotMicros,
                    lagSamples.get(),
                    lagFailures.get());
            cluster.dumpDiagnostics();
            raw.append(line);
            Files.writeString(directory.resolve("workload.csv"), raw.toString());
            System.out.print(line);
            ClusterTool.require(errors.get() == 0, "workload rejected/failed operations=" + errors);
          }
        }
  }

  static List<String> safeFlags(List<String> arguments) {
    return arguments.stream()
        .filter(
            flag ->
                flag.matches("-Xm[sx][0-9]+[kKmMgG]?")
                    || flag.matches("-XX:ActiveProcessorCount=[0-9]+"))
        .toList();
  }

  private static long processCpu() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }

  private static long threadAllocated() {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
  }

  private static Key key(int n) {
    return new Key(("key-" + n).getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] payload(int n) {
    byte[] b = new byte[1024];
    Arrays.fill(b, (byte) n);
    return b;
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
}
