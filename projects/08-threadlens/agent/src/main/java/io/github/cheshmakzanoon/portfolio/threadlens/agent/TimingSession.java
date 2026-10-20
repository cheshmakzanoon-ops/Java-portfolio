package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import io.github.cheshmakzanoon.portfolio.threadlens.probe.*;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Owns startup installation, its writer/consumer and the shutdown publication barrier. */
final class TimingSession implements AutoCloseable {
  interface Hooks {
    void add(Thread hook);

    void remove(Thread hook);
  }

  private static final Hooks RUNTIME_HOOKS =
      new Hooks() {
        public void add(Thread hook) {
          Runtime.getRuntime().addShutdownHook(hook);
        }

        public void remove(Thread hook) {
          Runtime.getRuntime().removeShutdownHook(hook);
        }
      };
  private final AgentConfig config;
  private final Instrumentation instrumentation;
  private final Hooks hooks;
  private final BufferedWriter writer;
  private final TimingStatistics statistics = new TimingStatistics();
  private final MethodRegistry registry;
  private final InstrumentationPolicy policy;
  private final TimingBuffer buffer;
  private final TimingTransformer transformer;
  private final AtomicBoolean running = new AtomicBoolean(true), healthy = new AtomicBoolean(true);
  private final AtomicLong drainDeadline = new AtomicLong(Long.MAX_VALUE),
      shutdownDiscarded = new AtomicLong(),
      rawOmitted = new AtomicLong();
  private final Object activation = new Object();
  private final Thread consumer, hook;
  private boolean hookAdded, transformerAttempted, installed, closed;

  static TimingSession start(AgentConfig config, Instrumentation instrumentation) throws Exception {
    return start(config, instrumentation, RUNTIME_HOOKS);
  }

  static TimingSession start(AgentConfig config, Instrumentation instrumentation, Hooks hooks)
      throws Exception {
    validateOutput(config.outputDirectory());
    TimingSession session = new TimingSession(config, instrumentation, hooks);
    try {
      session.install();
      return session;
    } catch (Exception | LinkageError failure) {
      session.abort(failure);
      throw failure;
    }
  }

  private TimingSession(AgentConfig config, Instrumentation instrumentation, Hooks hooks)
      throws IOException {
    this.config = config;
    this.instrumentation = Objects.requireNonNull(instrumentation);
    this.hooks = Objects.requireNonNull(hooks);
    registry = new MethodRegistry(config.maxMethods());
    policy = new InstrumentationPolicy(config.allowPackages(), config.methodNames());
    buffer = new TimingBuffer(config.queueCapacity());
    transformer = new TimingTransformer(policy, registry);
    consumer =
        Thread.ofPlatform().daemon().name("threadlens-probe-consumer").unstarted(this::consume);
    hook =
        Thread.ofPlatform()
            .name("threadlens-probe-shutdown")
            .unstarted(
                () -> {
                  try {
                    close();
                  } catch (Exception | LinkageError failure) {
                    ThreadLensPremain.diagnostic("observer shutdown output unavailable");
                  }
                });
    writer =
        Files.newBufferedWriter(
            config.outputDirectory().resolve("timings.tsv"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
  }

  private synchronized void install() throws IOException {
    hooks.add(hook);
    hookAdded = true;
    consumer.start();
    transformerAttempted = true;
    instrumentation.addTransformer(transformer, false);
    // Enable publication only after every resource and shutdown owner has been established.
    synchronized (activation) {
      if (!healthy.get()) throw new IOException("timing consumer unavailable during installation");
      TimingProbe.configure(buffer);
    }
    installed = true;
  }

  private synchronized void abort(Throwable failure) {
    healthy.set(false);
    TimingProbe.disable();
    if (transformerAttempted)
      cleanup(failure, () -> instrumentation.removeTransformer(transformer));
    if (hookAdded) cleanup(failure, () -> hooks.remove(hook));
    cleanup(failure, this);
  }

  private static void cleanup(Throwable primary, AutoCloseable resource) {
    try {
      resource.close();
    } catch (Exception | LinkageError cleanup) {
      if (cleanup != primary) primary.addSuppressed(cleanup);
    }
  }

  private void disableCollection() {
    synchronized (activation) {
      healthy.set(false);
      TimingProbe.disable();
    }
  }

  private void consume() {
    try (writer) {
      writer.write("methodId\tstartedMonotonicNanos\tdurationNanos\texceptional\n");
      long nextDiskCheck = System.nanoTime();
      long[] raw = new long[2]; // Single-consumer rows and omissions.
      TimingBuffer.TimingConsumer sink =
          (method, start, duration, exceptional) -> {
            statistics.accept(method, start, duration, exceptional);
            if (raw[0] < TimingStatistics.RAW_ROWS) {
              writer.write(method + "\t" + start + "\t" + duration + "\t" + exceptional + "\n");
              raw[0]++;
            } else {
              // Only this consumer writes; publish without an atomic RMW.
              rawOmitted.set(++raw[1]);
            }
          };
      while (running.get() || buffer.pending() > 0) {
        if (!running.get() && System.nanoTime() >= drainDeadline.get()) {
          shutdownDiscarded.set(buffer.pending());
          healthy.set(false);
          break;
        }
        int drained = 0;
        // Bound bookkeeping latency even when producers keep the queue nonempty.
        for (int batch = 0; batch < 64 && buffer.pollTo(sink); batch++) drained++;
        if (drained == 0) {
          LockSupport.parkNanos(500_000);
        }
        if (System.nanoTime() >= nextDiskCheck) {
          nextDiskCheck = System.nanoTime() + 1_000_000_000L;
          if (directoryBytes(config.outputDirectory()) > 512L * 1024 * 1024 - 32L * 1024 * 1024) {
            disableCollection();
            ThreadLensPremain.diagnostic("observer directory budget reached");
            break;
          }
        }
      }
      statistics.write(config.outputDirectory());
    } catch (Exception | LinkageError recoverable) {
      disableCollection();
      ThreadLensPremain.diagnostic("timing consumer disabled");
    }
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    boolean producerQuiesced = TimingProbe.disableAndAwait(2_000_000_000L);
    int unfinishedExits = TimingProbe.inFlightExits();
    long acceptedReservations = buffer.seal();
    if (!producerQuiesced || unfinishedExits > 0) healthy.set(false);
    drainDeadline.set(System.nanoTime() + 2_000_000_000L);
    running.set(false);
    InterruptedException interrupted = null;
    if (consumer.getState() == Thread.State.NEW) writer.close();
    else {
      LockSupport.unpark(consumer);
      try {
        consumer.join(3000);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        healthy.set(false);
        interrupted = failure;
      }
    }
    boolean terminated = !consumer.isAlive();
    if (!terminated) {
      shutdownDiscarded.set(buffer.pending());
      healthy.set(false);
    }
    writeStatus(producerQuiesced, unfinishedExits, acceptedReservations, terminated);
    closed = true;
    if (interrupted != null) throw interrupted;
  }

  private void writeStatus(boolean quiesced, int unfinished, long accepted, boolean terminated)
      throws IOException {
    StringBuilder methods = new StringBuilder("methodId\tidentity\n");
    registry.snapshot().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            e ->
                methods
                    .append(e.getKey())
                    .append('\t')
                    .append(
                        e.getValue().replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"))
                    .append('\n'));
    Files.writeString(
        config.outputDirectory().resolve("methods.tsv"),
        methods,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    Map<String, String> status = new TreeMap<>();
    status.put("statusVersion", "2");
    status.put("statusComplete", Boolean.toString(installed && terminated));
    status.put("collectionHealthy", Boolean.toString(healthy.get()));
    status.put("queueDrops", Long.toString(buffer.dropped()));
    status.put(
        "shutdownDiscarded", Long.toString(Math.max(shutdownDiscarded.get(), buffer.pending())));
    status.put(
        "incomplete",
        Boolean.toString(
            buffer.dropped() > 0
                || buffer.pending() > 0
                || !healthy.get()
                || TimingProbe.failures() > 0
                || !terminated
                || (terminated && statistics.overflow() > 0)));
    status.put("probeFailures", Long.toString(TimingProbe.failures()));
    status.put("methodOverflow", Long.toString(registry.overflow()));
    // Never read mutable consumer-owned statistics after a timed-out join.
    if (terminated) {
      status.put("timingHistogramOverflow", Long.toString(statistics.overflow()));
      status.put("durationClipped", Long.toString(statistics.clipped()));
      status.put("observedTimingEvents", Long.toString(statistics.observed()));
    }
    status.put("acceptedTimingReservations", Long.toString(accepted));
    status.put("producerQuiesced", Boolean.toString(quiesced));
    status.put("unfinishedProbeExits", Integer.toString(unfinished));
    status.put("rawTimingRowsOmitted", Long.toString(rawOmitted.get()));
    status.put("activeMethodIdLimit", Integer.toString(config.maxMethods()));
    status.put("activeQueueCapacity", Integer.toString(config.queueCapacity()));
    status.put("skips", policy.reasons().toString());
    StringBuilder text = new StringBuilder();
    status.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
    Files.writeString(
        config.outputDirectory().resolve("observer-status.txt"),
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
  }

  private static void validateOutput(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory))
      throw new IOException("observer output directory cannot be a symbolic link");
    try (var entries = Files.list(directory)) {
      if (entries.findAny().isPresent())
        throw new IOException("observer output directory must be empty");
    }
  }

  private static long directoryBytes(Path directory) throws IOException {
    long bytes = 0;
    int entries = 0;
    try (var paths = Files.walk(directory, 8)) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        if (++entries > 10_000) throw new IOException("observer directory entry budget");
        Path path = iterator.next();
        if (directory.relativize(path).getNameCount() == 8
            && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("observer directory depth budget");
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          bytes = Math.addExact(bytes, Files.size(path));
      }
    }
    return bytes;
  }
}
