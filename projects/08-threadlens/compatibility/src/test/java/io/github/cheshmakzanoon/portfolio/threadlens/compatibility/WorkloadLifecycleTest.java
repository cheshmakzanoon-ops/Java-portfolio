package io.github.cheshmakzanoon.portfolio.threadlens.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkloadLifecycleTest {
  @TempDir Path directory;

  @Test
  void interruptedMeasurementStopsEveryWorkerAndPreservesTheParentInterrupt() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean interrupted =
        new java.util.concurrent.atomic.AtomicBoolean();
    Thread parent =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    WorkloadMain.main(args("cpu", "false", "3600"));
                  } catch (Throwable error) {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (parent.isAlive()
          && parent.getState() != Thread.State.TIMED_WAITING
          && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(Thread.State.TIMED_WAITING, parent.getState());
      parent.interrupt();
      parent.join(3000);
      assertFalse(parent.isAlive());
      assertInstanceOf(InterruptedException.class, failure.get());
      assertTrue(interrupted.get());
      assertTrue(
          Thread.getAllStackTraces().keySet().stream()
              .noneMatch(
                  thread ->
                      thread.isAlive() && thread.getName().startsWith("threadlens-workload-")));
      assertFalse(Files.exists(directory.resolve("measurement.json")));
    } finally {
      parent.interrupt();
      parent.join(3000);
    }
  }

  @Test
  void invalidWorkloadAndDurationAreRejectedBeforeOutputOrWorkersExist() {
    for (String[] arguments :
        new String[][] {
          args("unknown", "false", "0"), args("cpu", "maybe", "0"), args("cpu", "false", "3601")
        }) assertThrows(IllegalArgumentException.class, () -> WorkloadMain.main(arguments));
    assertFalse(Files.exists(directory.resolve("measurement.json")));
  }

  private String[] args(String kind, String regressed, String warmup) {
    return new String[] {
      kind,
      regressed,
      warmup,
      "1",
      "baseline",
      directory.resolve("measurement.json").toString(),
      directory.resolve("recording.jfr").toString()
    };
  }
}
