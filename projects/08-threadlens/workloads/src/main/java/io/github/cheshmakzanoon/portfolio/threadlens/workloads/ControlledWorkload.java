package io.github.cheshmakzanoon.portfolio.threadlens.workloads;

import java.util.concurrent.locks.LockSupport;

/**
 * Named planted causes, no user input or secrets. Computation results escape through a volatile
 * sink.
 */
public final class ControlledWorkload {
  public static final class PlantedLock {}

  private static final PlantedLock LOCK = new PlantedLock();
  private static volatile long sink;
  private static volatile Object allocated;

  private ControlledWorkload() {}

  public static long plantedCpuHotspot(int intensity) {
    long value = sink | 1;
    for (int i = 0; i < intensity; i++) {
      value ^= value << 13;
      value ^= value >>> 7;
      value ^= value << 17;
    }
    sink = value;
    return value;
  }

  public static void plantedAllocationPressure(int bytes) {
    byte[] array = new byte[bytes];
    array[0] = 1;
    array[bytes - 1] = 2;
    allocated = array;
  }

  public static void plantedMonitorContention(long holdNanos) {
    synchronized (LOCK) {
      LockSupport.parkNanos(holdNanos);
      sink++;
    }
  }

  public static void operation(String kind, boolean regressed) {
    switch (kind) {
      case "cpu" -> plantedCpuHotspot(regressed ? 80_000 : 20_000);
      case "allocation" -> {
        plantedCpuHotspot(1_000);
        plantedAllocationPressure(regressed ? 131_072 : 4096);
      }
      case "contention", "virtual" -> plantedMonitorContention(regressed ? 4_000_000 : 100_000);
      case "mixed" -> {
        plantedCpuHotspot(10_000);
        plantedAllocationPressure(4096);
        plantedMonitorContention(100_000);
      }
      default ->
          throw new IllegalArgumentException("workload cpu|allocation|contention|virtual|mixed");
    }
  }
}
