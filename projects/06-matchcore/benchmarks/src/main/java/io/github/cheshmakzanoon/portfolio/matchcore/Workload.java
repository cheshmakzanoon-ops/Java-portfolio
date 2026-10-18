package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Closed-loop driver; generation/tracker are included in elapsed time, setup and recovery are not.
 */
public final class Workload {
  private Workload() {}

  private static Command init(int a) {
    return new Command(
        "init", "a" + a, Type.INIT, null, null, 0, 0, null, false, 1_000_000_000_000L, 100_000_000);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("usage: Workload core|durable count journal");
    String mode = args[0];
    int count = Integer.parseInt(args[1]);
    if (count < 100 || count > 1_000_000 || count % 100 != 0)
      throw new IllegalArgumentException("count must be a multiple of100 in100..1000000");
    if (!mode.equals("core") && !mode.equals("durable"))
      throw new IllegalArgumentException("unknown mode");
    Path path = Path.of(args[2]);
    long[] latency = new long[count];
    SeededHistory history = new SeededHistory(42006006);
    Map<String, Long> businessResults = new TreeMap<>();
    int duplicates = 0, peakQueue = 0;
    long riskOrCapacity = 0, sequence = 0;
    Book book = mode.equals("core") ? new Book() : null;
    Gateway gateway =
        mode.equals("durable") ? new Gateway(path, "benchmark", 256, 1_100_000) : null;
    try {
      for (int a = 0; a < 64; a++) {
        Command c = init(a);
        if (book != null) book.apply(++sequence, c);
        else gateway.submit(c.accountId(), c).get(30, TimeUnit.SECONDS);
      }
      com.sun.management.ThreadMXBean allocation =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      com.sun.management.OperatingSystemMXBean os =
          (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      long allocatedBefore = allocated(allocation),
          cpuBefore = os.getProcessCpuTime(),
          gcBefore = gcCount(),
          gcMillisBefore = gcMillis(),
          start = System.nanoTime();
      for (int i = 0; i < count; i++) {
        Command command = history.next(i);
        long before = System.nanoTime();
        Result result;
        if (book != null) result = book.apply(++sequence, command);
        else {
          var future = gateway.submit(command.accountId(), command);
          peakQueue = Math.max(peakQueue, gateway.queueDepth());
          result = future.get(30, TimeUnit.SECONDS);
        }
        latency[i] = System.nanoTime() - before;
        history.observed(command, result);
        businessResults.merge(result.code(), 1L, Math::addExact);
        if (Set.of("RISK", "OVERFLOW", "OPEN_ORDER_CAP").contains(result.code())) riskOrCapacity++;
        if (i % 1000 == 0) {
          Result duplicate =
              book != null
                  ? book.lookup(command)
                  : gateway.submit(command.accountId(), command).get(30, TimeUnit.SECONDS);
          if (!result.equals(duplicate)) throw new AssertionError("dedup mismatch");
          duplicates++;
        }
      }
      long elapsed = System.nanoTime() - start,
          cpu = os.getProcessCpuTime() - cpuBefore,
          allocatedBytes = allocated(allocation) - allocatedBefore,
          gcCollections = gcCount() - gcBefore,
          gcTimeMillis = gcMillis() - gcMillisBefore;
      long usedHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      View state = book != null ? book.view() : gateway.fullView();
      history.verify(state);
      if (state.accounts().values().stream().mapToLong(Balance::cash).sum() != 64_000_000_000_000L
          || state.accounts().values().stream().mapToLong(Balance::lots).sum() != 6_400_000_000L)
        throw new AssertionError("conservation");
      if (businessResults.values().stream().mapToLong(Long::longValue).sum() != count)
        throw new AssertionError("missing result");
      String digest = state.digest();
      long recovery = 0;
      if (gateway != null) {
        gateway.close();
        gateway = null;
        long before = System.nanoTime();
        Gateway restored = new Gateway(path, "benchmark", 256, 1_100_000);
        recovery = System.nanoTime() - before;
        try (restored) {
          if (!digest.equals(restored.fullView().digest()))
            throw new AssertionError("recovery digest");
        }
      }
      Arrays.sort(latency);
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("schemaVersion", 2);
      output.put("generatorVersion", "v2-live-identity-shuffled-block");
      output.put("mode", mode);
      output.put("seed", 42006006);
      output.put("commands", count);
      output.put("initializationCommands", 64);
      output.put("duplicateInjections", duplicates);
      output.put("mix", Map.of("NEW", 60, "CANCEL", 25, "REPLACE", 15));
      output.put("accounts", 64);
      output.put("openOrderCap", 100000);
      output.put(
          "priceDistribution",
          "NEW/REPLACE uniform integral ticks9900..10100; lot quantities1..10");
      output.put("activeTargetSelections", history.activeTargets());
      output.put("emptyTargetSelections", history.emptyTargets());
      output.put("peakOpenOrders", history.peakOpen());
      output.put("finalOpenOrders", state.orders().size());
      output.put("elapsedNanos", elapsed);
      output.put("commandsPerSecond", count * 1e9 / elapsed);
      output.put("p50Nanos", latency[count / 2]);
      output.put("p95Nanos", latency[(int) (count * .95)]);
      output.put("p99Nanos", latency[(int) (count * .99)]);
      output.put("acknowledgementSamples", count);
      output.put("clientConcurrency", 1);
      output.put("batchSize", 1);
      output.put("measuredForceCalls", mode.equals("durable") ? count : 0);
      output.put("totalForceCalls", mode.equals("durable") ? count + 64 : 0);
      output.put("forceFrequencyHz", mode.equals("durable") ? count * 1e9 / elapsed : 0);
      output.put("peakObservedQueue", peakQueue);
      output.put("riskOrCapacityRejections", riskOrCapacity);
      output.put("businessResultCounts", businessResults);
      output.put("allocationBytesPerCommand", allocatedBytes / (double) count);
      output.put("processCpuNanos", cpu);
      output.put("gcCollections", gcCollections);
      output.put("gcCollectionMillis", gcTimeMillis);
      output.put("usedHeapBytes", usedHeap);
      output.put("recoveryNanos", recovery);
      output.put(
          "recoveryBoundary", "Gateway constructor only; digest comparison and close excluded");
      output.put("finalDigest", digest);
      System.out.println(new ObjectMapper().writeValueAsString(output));
    } finally {
      if (gateway != null) gateway.close();
    }
  }

  private static long allocated(com.sun.management.ThreadMXBean bean) {
    long total = 0;
    for (long id : bean.getAllThreadIds()) {
      long n = bean.getThreadAllocatedBytes(id);
      if (n > 0) total += n;
    }
    return total;
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
