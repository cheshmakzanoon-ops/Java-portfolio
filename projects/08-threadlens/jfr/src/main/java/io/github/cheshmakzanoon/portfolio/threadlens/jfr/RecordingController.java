package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.Profile;
import io.github.cheshmakzanoon.portfolio.threadlens.analysis.ProfileAccumulator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import jdk.jfr.consumer.RecordingStream;

/** JFR callback only converts and offers; a separate bounded worker aggregates and checks disk. */
public final class RecordingController implements AutoCloseable {
  public static final long RETENTION_BYTES = 256L * 1024 * 1024,
      DIRECTORY_BYTES = 512L * 1024 * 1024;
  private final Path directory;
  private final EventQueue queue;
  private final EventNormalizer normalizer = new EventNormalizer();
  private final ProfileAccumulator accumulator =
      new ProfileAccumulator(10_000, 1_000_000_000L, 900);
  private final RecordingStream stream = new RecordingStream();
  private final AtomicBoolean running = new AtomicBoolean();
  private final LongAdder consumerFailures = new LongAdder(), callbackFailures = new LongAdder();
  private final Map<String, EventCapabilities.Capability> capabilities =
      EventCapabilities.discover();
  private Thread worker;
  private volatile String stopReason = "not started";
  private volatile Consumer<NormalizedEvent> observer = event -> {};

  public RecordingController(Path directory, int capacity) {
    this.directory = directory;
    queue = new EventQueue(capacity);
  }

  /**
   * Embedding/test seam: normalized events use the same queue and analysis path as JFR callbacks.
   */
  public boolean publish(NormalizedEvent event) {
    return queue.offer(event);
  }

  public void observer(Consumer<NormalizedEvent> callback) {
    observer = callback;
  }

  public void start() throws IOException {
    Files.createDirectories(directory);
    // Reserve a full retained recording before collection, rather than filling the budget to its
    // edge.
    if (!Files.isWritable(directory)
        || directoryBytes(directory) > DIRECTORY_BYTES - RETENTION_BYTES)
      throw new IOException("observer directory cannot reserve 256 MiB within 512 MiB budget");
    stream.setSettings(EventCapabilities.settings(capabilities));
    stream.setMaxAge(Duration.ofMinutes(15));
    stream.setMaxSize(RETENTION_BYTES);
    stream.setOrdered(true);
    stream.setReuse(true);
    for (String name : EventCapabilities.REQUESTED)
      if (capabilities.get(name).supported())
        stream.onEvent(
            name,
            event -> {
              try {
                normalizer.normalize(event).ifPresent(queue::offer);
              } catch (RuntimeException recoverable) {
                callbackFailures.increment();
              }
            });
    stream.onError(
        error -> {
          callbackFailures.increment();
          stopReason = "JFR callback failure";
          running.set(false);
        });
    running.set(true);
    stopReason = "running";
    worker = Thread.ofPlatform().daemon().name("threadlens-analysis").start(this::consume);
    stream.startAsync();
  }

  private void consume() {
    long nextCheck = System.nanoTime();
    while (running.get()) {
      NormalizedEvent event = queue.poll();
      if (event != null) {
        try {
          accumulator.accept(event);
          observer.accept(event);
        } catch (RuntimeException recoverable) {
          consumerFailures.increment();
          accumulator.markIncomplete();
        }
      } else LockSupport.parkNanos(500_000);
      if (System.nanoTime() >= nextCheck) {
        nextCheck = System.nanoTime() + 1_000_000_000L;
        try {
          if (directoryBytes(directory) > DIRECTORY_BYTES - RETENTION_BYTES) {
            stopReason = "observer directory budget";
            running.set(false);
            stream.close();
          }
        } catch (IOException recoverable) {
          stopReason = "observer directory unavailable";
          running.set(false);
          stream.close();
        }
      }
    }
    NormalizedEvent event;
    while ((event = queue.poll()) != null) {
      try {
        accumulator.accept(event);
      } catch (RuntimeException recoverable) {
        consumerFailures.increment();
      }
    }
    if (queue.drops() > 0
        || consumerFailures.sum() > 0
        || callbackFailures.sum() > 0
        || normalizer.unsupportedFields() > 0
        || normalizer.truncatedStacks() > 0) accumulator.markIncomplete();
  }

  public static long directoryBytes(Path path) throws IOException {
    long total = 0;
    try (var files = Files.walk(path, 8)) {
      var iterator = files.iterator();
      int entries = 0;
      while (iterator.hasNext()) {
        if (++entries > 10_000) throw new IOException("observer directory entry budget");
        Path file = iterator.next();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          total = Math.addExact(total, Files.size(file));
      }
    }
    return total;
  }

  public void dump(Path path) throws IOException {
    if (!path.toAbsolutePath()
        .normalize()
        .getParent()
        .equals(directory.toAbsolutePath().normalize()))
      throw new IOException("dump must be directly in observer directory");
    if (directoryBytes(directory) > DIRECTORY_BYTES - RETENTION_BYTES)
      throw new IOException("insufficient observer budget");
    stream.dump(path);
  }

  public void stopAndDump(Path path) throws IOException {
    stream.stop();
    dump(path);
    close();
  }

  @Override
  public void close() {
    stream.close();
    running.set(false);
    if (worker != null)
      try {
        worker.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    if (stopReason.equals("running")) stopReason = "closed";
  }

  public Profile profile() {
    if (running.get() || (worker != null && worker.isAlive()))
      throw new IllegalStateException("close/drain before snapshot");
    Profile p = accumulator.snapshot();
    Map<String, Long> counters = new java.util.TreeMap<>(p.counters());
    counters.put("queueDrops", queue.drops());
    counters.put("unsupportedFields", normalizer.unsupportedFields());
    counters.put("truncatedStacks", normalizer.truncatedStacks());
    counters.put("clippedStrings", normalizer.clippedStrings());
    counters.put("consumerFailures", consumerFailures.sum());
    counters.put("callbackFailures", callbackFailures.sum());
    return new Profile(
        p.eventCount(),
        p.sampleCount(),
        p.startNanos(),
        p.endNanos(),
        p.flame(),
        p.methods(),
        p.windows(),
        p.allocations(),
        p.monitors(),
        p.parks(),
        p.timings(),
        counters);
  }

  public long peakQueueBytes() {
    return queue.peakBytes();
  }

  public long queueRetainedBytes() {
    return queue.retainedBytes();
  }

  public long drops() {
    return queue.drops();
  }

  public long consumerFailures() {
    return consumerFailures.sum();
  }

  public long callbackFailures() {
    return callbackFailures.sum();
  }

  public String stopReason() {
    return stopReason;
  }

  public Map<String, EventCapabilities.Capability> capabilities() {
    return capabilities;
  }
}
