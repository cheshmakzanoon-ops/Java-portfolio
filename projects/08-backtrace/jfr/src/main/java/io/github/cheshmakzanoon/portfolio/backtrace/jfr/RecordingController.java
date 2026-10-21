package io.github.cheshmakzanoon.portfolio.backtrace.jfr;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import jdk.jfr.consumer.RecordingStream;

/** JFR callback only converts and offers; a separate bounded worker aggregates and checks disk. */
public final class RecordingController implements AutoCloseable {
  public static final long RETENTION_BYTES = 256L * 1024 * 1024,
      DIRECTORY_BYTES = 512L * 1024 * 1024;
  private final Path directory;
  private final EventProcessor processor;
  private final EventNormalizer normalizer = new EventNormalizer();
  private final RecordingStream stream;
  private final LongAdder callbackFailures = new LongAdder();
  private final Map<String, EventCapabilities.Capability> capabilities;
  private volatile String stopReason = "not started";
  private boolean started;
  private volatile boolean closed;

  public RecordingController(Path directory, int capacity) {
    this.directory = java.util.Objects.requireNonNull(directory);
    processor = new EventProcessor(capacity);
    capabilities = EventCapabilities.discover();
    stream = new RecordingStream();
  }

  /**
   * Embedding/test seam: normalized events use the same queue and analysis path as JFR callbacks.
   */
  public boolean publish(NormalizedEvent event) {
    return processor.publish(event);
  }

  public void observer(Consumer<NormalizedEvent> callback) {
    processor.observer(callback);
  }

  public synchronized void start() throws IOException {
    if (started || closed) throw new IllegalStateException("recording already started or closed");
    try {
      Files.createDirectories(directory);
      if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))
        throw new IOException("observer directory cannot contain symbolic links");
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
                  normalizer.normalize(event).ifPresent(processor::publish);
                } catch (RuntimeException | LinkageError recoverable) {
                  callbackFailures.increment();
                }
              });
      stream.onError(
          error -> {
            callbackFailures.increment();
            stopReason = "JFR callback failure";
            processor.requestStop();
          });
      stopReason = "running";
      processor.start(this::checkBudget);
      stream.startAsync();
      started = true;
    } catch (IOException | RuntimeException | LinkageError failure) {
      try {
        close();
      } catch (RuntimeException | LinkageError cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void checkBudget() {
    try {
      if (directoryBytes(directory) <= DIRECTORY_BYTES - RETENTION_BYTES) return;
      stopReason = "observer directory budget";
    } catch (IOException | RuntimeException recoverable) {
      stopReason = "observer directory unavailable";
    }
    processor.requestStop();
    stream.close();
  }

  public static long directoryBytes(Path path) throws IOException {
    long total = 0;
    try (var files = Files.walk(path, 8)) {
      var iterator = files.iterator();
      int entries = 0;
      while (iterator.hasNext()) {
        if (++entries > 10_000) throw new IOException("observer directory entry budget");
        Path file = iterator.next();
        if (path.relativize(file).getNameCount() == 8
            && Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("observer directory depth budget");
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          total = Math.addExact(total, Files.size(file));
      }
    }
    return total;
  }

  public void dump(Path path) throws IOException {
    Path parent = path.toAbsolutePath().getParent();
    if (!java.util.Objects.equals(parent, directory.toAbsolutePath())
        && !java.util.Objects.equals(parent, directory.toRealPath()))
      throw new IOException("dump must be directly in observer directory");
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("dump destination already exists");
    if (directoryBytes(directory) > DIRECTORY_BYTES - RETENTION_BYTES)
      throw new IOException("insufficient observer budget");
    stream.dump(path);
  }

  public void stopAndDump(Path path) throws IOException {
    try {
      stream.stop();
      dump(path);
    } catch (IOException | RuntimeException | LinkageError failure) {
      try {
        close();
      } catch (RuntimeException | LinkageError cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    close();
  }

  @Override
  public void close() {
    if (closed) {
      processor.close();
      return;
    }
    Throwable failure = null;
    try {
      stream.close();
    } catch (RuntimeException | LinkageError cleanup) {
      failure = cleanup;
    }
    try {
      processor.close();
    } catch (RuntimeException | LinkageError cleanup) {
      stopReason = "analysis shutdown incomplete";
      if (failure == null) failure = cleanup;
      else failure.addSuppressed(cleanup);
    }
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof LinkageError linkage) throw linkage;
    closed = true;
    if (stopReason.equals("running") || stopReason.equals("not started")) stopReason = "closed";
  }

  public Profile profile() {
    if (!closed) throw new IllegalStateException("close/drain before snapshot");
    Profile p =
        processor.profile(
            callbackFailures.sum() > 0
                || normalizer.unsupportedFields() > 0
                || normalizer.truncatedStacks() > 0);
    Map<String, Long> counters = new java.util.TreeMap<>(p.counters());
    counters.put("queueDrops", processor.drops());
    counters.put("shutdownDiscarded", processor.discarded());
    counters.put("unsupportedFields", normalizer.unsupportedFields());
    counters.put("truncatedStacks", normalizer.truncatedStacks());
    counters.put("clippedStrings", normalizer.clippedStrings());
    counters.put("consumerFailures", processor.failures());
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
    return processor.peakBytes();
  }

  public long queueRetainedBytes() {
    return processor.retainedBytes();
  }

  public long drops() {
    return processor.drops();
  }

  public long consumerFailures() {
    return processor.failures();
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
