package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Closed-loop system driver. It intentionally reports core and local-force service separately. */
public final class Workload {
  private Workload() {}

  private static Command init(int a) {
    return new Command(
        "init", "a" + a, Type.INIT, null, null, 0, 0, null, false, 1_000_000_000_000L, 100_000_000);
  }

  private static Command next(Random r, int i) {
    String account = "a" + r.nextInt(64),
        order = "o" + (i % 100 < 60 ? i : r.nextInt(Math.max(1, i)));
    int action = i % 100;
    Side side = r.nextBoolean() ? Side.BUY : Side.SELL;
    long price = 9900 + r.nextInt(201), qty = 1 + r.nextInt(10);
    return new Command(
        "c" + i,
        account,
        action < 60 ? Type.NEW : action < 85 ? Type.CANCEL : Type.REPLACE,
        order,
        side,
        price,
        qty,
        Tif.GTC,
        false,
        0,
        0);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3)
      throw new IllegalArgumentException("usage: Workload core|durable count journal");
    String mode = args[0];
    int count = Integer.parseInt(args[1]);
    if (count < 100 || count > 1_000_000) throw new IllegalArgumentException("count 100..1000000");
    Path path = Path.of(args[2]);
    long[] latency = new long[count];
    Random random = new Random(42006006);
    Map<String, Long> businessResults = new TreeMap<>();
    int duplicates = 0, peakQueue = 0;
    long errors = 0;
    Book book = mode.equals("core") ? new Book() : null;
    Gateway gateway =
        mode.equals("durable") ? new Gateway(path, "benchmark", 256, 1_100_000) : null;
    if (book == null && gateway == null) throw new IllegalArgumentException("unknown mode");
    long sequence = 0;
    try {
      for (int a = 0; a < 64; a++) {
        Command c = init(a);
        if (book != null) book.apply(++sequence, c);
        else gateway.submit(c.accountId(), c).get(30, TimeUnit.SECONDS);
      }
      com.sun.management.ThreadMXBean bean =
          (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
      long allocatedBefore = allocated(bean);
      long start = System.nanoTime();
      for (int i = 0; i < count; i++) {
        Command c = next(random, i);
        long before = System.nanoTime();
        Result r;
        if (book != null) r = book.apply(++sequence, c);
        else {
          var future = gateway.submit(c.accountId(), c);
          peakQueue = Math.max(peakQueue, gateway.queueDepth());
          r = future.get(30, TimeUnit.SECONDS);
        }
        latency[i] = System.nanoTime() - before;
        businessResults.merge(r.code(), 1L, Math::addExact);
        if (r.code().equals("RISK")
            || r.code().equals("OVERFLOW")
            || r.code().equals("OPEN_ORDER_CAP")) errors++;
        if (i % 1000 == 0) {
          Result duplicate =
              book != null
                  ? book.lookup(c)
                  : gateway.submit(c.accountId(), c).get(30, TimeUnit.SECONDS);
          if (!r.equals(duplicate)) throw new AssertionError("dedup mismatch");
          duplicates++;
        }
      }
      long elapsed = System.nanoTime() - start, allocatedAfter = allocated(bean);
      View state = book != null ? book.view() : gateway.fullView();
      long cash = state.accounts().values().stream().mapToLong(Balance::cash).sum(),
          lots = state.accounts().values().stream().mapToLong(Balance::lots).sum();
      if (cash != 64_000_000_000_000L || lots != 6_400_000_000L)
        throw new AssertionError("conservation");
      if (businessResults.values().stream().mapToLong(Long::longValue).sum() != count)
        throw new AssertionError("every prescribed command must have a recorded business result");
      Arrays.sort(latency);
      long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long recovery = 0;
      if (gateway != null) {
        gateway.close();
        gateway = null;
        long before = System.nanoTime();
        try (Gateway restored = new Gateway(path, "benchmark", 256, 1_100_000)) {
          if (!state.digest().equals(restored.fullView().digest()))
            throw new AssertionError("recovery digest");
        }
        recovery = System.nanoTime() - before;
      }
      System.out.printf(
          Locale.ROOT,
          "{\"mode\":\"%s\",\"seed\":42006006,\"commands\":%d,\"duplicateInjections\":%d,\"newPercent\":60,\"cancelPercent\":25,\"replacePercent\":15,\"accounts\":64,\"openOrderCap\":100000,\"priceDistribution\":\"uniform integral ticks 9900..10100\",\"elapsedNanos\":%d,\"commandsPerSecond\":%.3f,\"p50Nanos\":%d,\"p95Nanos\":%d,\"p99Nanos\":%d,\"batchSize\":1,\"forces\":%d,\"peakObservedQueue\":%d,\"riskOrCapacityRejections\":%d,\"businessResultCounts\":%s,\"allocationBytesPerCommand\":%.3f,\"usedHeapBytes\":%d,\"recoveryNanos\":%d,\"finalDigest\":\"%s\"}%n",
          mode,
          count,
          duplicates,
          elapsed,
          count * 1e9 / elapsed,
          latency[count / 2],
          latency[(int) (count * .95)],
          latency[(int) (count * .99)],
          mode.equals("durable") ? count + 64 : 0,
          peakQueue,
          errors,
          new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(businessResults),
          (allocatedAfter - allocatedBefore) / (double) count,
          usedHeap,
          recovery,
          state.digest());
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
}
