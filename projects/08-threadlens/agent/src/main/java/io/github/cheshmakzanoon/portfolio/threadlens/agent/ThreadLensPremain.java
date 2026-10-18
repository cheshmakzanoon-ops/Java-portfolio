package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import io.github.cheshmakzanoon.portfolio.threadlens.probe.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class ThreadLensPremain {
  private static final AtomicBoolean DIAGNOSTIC = new AtomicBoolean();

  private ThreadLensPremain() {}

  public static void premain(String argument, Instrumentation instrumentation) {
    try {
      AgentConfig config = AgentConfig.read(argument);
      Files.createDirectories(config.outputDirectory());
      if (directoryBytes(config.outputDirectory()) > 256L * 1024 * 1024)
        throw new java.io.IOException("observer directory cannot reserve output budget");
      // Validate output and initialize observer dependencies before installing transformations.
      var writer =
          Files.newBufferedWriter(
              config.outputDirectory().resolve("timings.tsv"),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE_NEW);
      var statistics = new TimingStatistics();
      var registry = new MethodRegistry(config.maxMethods());
      var policy = new InstrumentationPolicy(config.allowPackages(), config.methodNames());
      var buffer = new TimingBuffer(config.queueCapacity());
      AtomicBoolean running = new AtomicBoolean(true), healthy = new AtomicBoolean(true);
      AtomicLong drainDeadline = new AtomicLong(Long.MAX_VALUE),
          shutdownDiscarded = new AtomicLong(),
          rawOmitted = new AtomicLong();
      TimingProbe.configure(buffer);
      instrumentation.addTransformer(new TimingTransformer(policy, registry), false);
      Thread consumer =
          Thread.ofPlatform()
              .daemon()
              .name("threadlens-probe-consumer")
              .start(
                  () -> {
                    try (writer) {
                      writer.write("methodId\tstartedMonotonicNanos\tdurationNanos\texceptional\n");
                      long rawRows = 0, nextDiskCheck = System.nanoTime();
                      while (running.get() || buffer.pending() > 0) {
                        if (!running.get() && System.nanoTime() >= drainDeadline.get()) {
                          shutdownDiscarded.set(buffer.pending());
                          healthy.set(false);
                          break;
                        }
                        var timing = buffer.poll();
                        if (timing == null) {
                          LockSupport.parkNanos(500_000);
                        } else {
                          statistics.accept(timing);
                          if (rawRows < TimingStatistics.RAW_ROWS) {
                            writer.write(
                                timing.methodId()
                                    + "\t"
                                    + timing.startedNanos()
                                    + "\t"
                                    + timing.durationNanos()
                                    + "\t"
                                    + timing.exceptional()
                                    + "\n");
                            rawRows++;
                          } else rawOmitted.incrementAndGet();
                        }
                        if (System.nanoTime() >= nextDiskCheck) {
                          nextDiskCheck = System.nanoTime() + 1_000_000_000L;
                          if (directoryBytes(config.outputDirectory())
                              > 512L * 1024 * 1024 - 32L * 1024 * 1024) {
                            healthy.set(false);
                            TimingProbe.disable();
                            diagnostic("observer directory budget reached");
                            break;
                          }
                        }
                      }
                      statistics.write(config.outputDirectory());
                    } catch (Exception | LinkageError recoverable) {
                      healthy.set(false);
                      TimingProbe.disable();
                      diagnostic("timing consumer disabled");
                    }
                  });
      Runtime.getRuntime()
          .addShutdownHook(
              Thread.ofPlatform()
                  .unstarted(
                      () -> {
                        TimingProbe.disable();
                        drainDeadline.set(System.nanoTime() + 2_000_000_000L);
                        running.set(false);
                        try {
                          consumer.join(3000);
                          if (consumer.isAlive()) {
                            shutdownDiscarded.set(buffer.pending());
                            healthy.set(false);
                          }
                          StringBuilder methods = new StringBuilder("methodId\tidentity\n");
                          registry.snapshot().entrySet().stream()
                              .sorted(java.util.Map.Entry.comparingByKey())
                              .forEach(
                                  e ->
                                      methods
                                          .append(e.getKey())
                                          .append('\t')
                                          .append(
                                              e.getValue()
                                                  .replace("\t", "\\t")
                                                  .replace("\n", "\\n")
                                                  .replace("\r", "\\r"))
                                          .append('\n'));
                          Files.writeString(
                              config.outputDirectory().resolve("methods.tsv"),
                              methods,
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE_NEW);
                          Files.writeString(
                              config.outputDirectory().resolve("observer-status.txt"),
                              "collectionHealthy="
                                  + healthy.get()
                                  + "\nqueueDrops="
                                  + buffer.dropped()
                                  + "\nshutdownDiscarded="
                                  + Math.max(shutdownDiscarded.get(), buffer.pending())
                                  + "\nincomplete="
                                  + (buffer.dropped() > 0
                                      || buffer.pending() > 0
                                      || !healthy.get()
                                      || statistics.overflow() > 0)
                                  + "\nprobeFailures="
                                  + TimingProbe.failures()
                                  + "\nmethodOverflow="
                                  + registry.overflow()
                                  + "\ntimingHistogramOverflow="
                                  + statistics.overflow()
                                  + "\ndurationClipped="
                                  + statistics.clipped()
                                  + "\nobservedTimingEvents="
                                  + statistics.observed()
                                  + "\nrawTimingRowsOmitted="
                                  + rawOmitted.get()
                                  + "\nactiveMethodIdLimit="
                                  + config.maxMethods()
                                  + "\nactiveQueueCapacity="
                                  + config.queueCapacity()
                                  + "\nskips="
                                  + policy.reasons()
                                  + "\n",
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE_NEW);
                        } catch (Exception recoverable) {
                          diagnostic("observer shutdown output unavailable");
                        }
                      }));
    } catch (Exception | LinkageError recoverable) {
      TimingProbe.disable();
      diagnostic("initialization disabled; host may continue");
    }
  }

  private static long directoryBytes(Path directory) throws java.io.IOException {
    long bytes = 0;
    int entries = 0;
    try (var paths = Files.walk(directory, 8)) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (++entries > 10_000) throw new java.io.IOException("observer directory entry budget");
        Path path = iterator.next();
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          bytes = Math.addExact(bytes, Files.size(path));
      }
    }
    return bytes;
  }

  private static void diagnostic(String text) {
    if (DIAGNOSTIC.compareAndSet(false, true)) System.err.println("ThreadLens: " + text);
  }
}
