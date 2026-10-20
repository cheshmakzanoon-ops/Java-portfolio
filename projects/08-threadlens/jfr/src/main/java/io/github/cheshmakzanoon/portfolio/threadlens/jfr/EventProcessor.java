package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owns the single analysis consumer, sealed admission and bounded shutdown drain. */
final class EventProcessor implements AutoCloseable {
  private final EventQueue queue;
  private final ProfileAccumulator accumulator =
      new ProfileAccumulator(10_000, 1_000_000_000L, 900);
  private final LongAdder failures = new LongAdder();
  private final AtomicBoolean running = new AtomicBoolean();
  private final long drainNanos, joinMillis;
  private final LongSupplier clock;
  private volatile Consumer<NormalizedEvent> observer = event -> {};
  private volatile long deadline = Long.MAX_VALUE, discarded;
  private Thread worker;
  private volatile boolean closed;
  private boolean stopping;

  EventProcessor(int capacity) {
    this(capacity, 2_000_000_000L, 3000);
  }

  EventProcessor(int capacity, long drainNanos, long joinMillis) {
    this(capacity, drainNanos, joinMillis, System::nanoTime);
  }

  EventProcessor(int capacity, long drainNanos, long joinMillis, LongSupplier clock) {
    if (drainNanos < 0 || drainNanos > 5_000_000_000L || joinMillis < 1 || joinMillis > 5000)
      throw new IllegalArgumentException("analysis shutdown bounds");
    queue = new EventQueue(capacity);
    this.drainNanos = drainNanos;
    this.joinMillis = joinMillis;
    this.clock = Objects.requireNonNull(clock);
  }

  boolean publish(NormalizedEvent event) {
    return queue.offer(event);
  }

  void observer(Consumer<NormalizedEvent> callback) {
    observer = Objects.requireNonNull(callback);
  }

  synchronized void start(Runnable checkBudget) {
    if (closed || stopping || worker != null)
      throw new IllegalStateException("analysis already started or closed");
    running.set(true);
    worker =
        Thread.ofPlatform().daemon().name("threadlens-analysis").start(() -> consume(checkBudget));
  }

  private void consume(Runnable checkBudget) {
    long nextCheck = clock.getAsLong();
    try {
      while (running.get() || queue.pending() > 0) {
        if (!running.get() && clock.getAsLong() - deadline >= 0) {
          discarded = queue.pending();
          accumulator.markIncomplete();
          break;
        }
        NormalizedEvent event = queue.poll();
        if (event != null) {
          try {
            accumulator.accept(event);
            if (running.get()) observer.accept(event);
          } catch (RuntimeException | LinkageError recoverable) {
            failures.increment();
            accumulator.markIncomplete();
          }
        } else LockSupport.parkNanos(500_000);
        if (running.get() && clock.getAsLong() - nextCheck >= 0) {
          nextCheck = clock.getAsLong() + TimeUnit.SECONDS.toNanos(1);
          checkBudget.run();
        }
      }
    } catch (RuntimeException | LinkageError recoverable) {
      failures.increment();
      queue.seal();
      running.set(false);
      discarded = queue.pending();
      accumulator.markIncomplete();
    } finally {
      if (queue.drops() > 0 || failures.sum() > 0 || discarded > 0) accumulator.markIncomplete();
      closed = true;
    }
  }

  @Override
  public void close() {
    Thread owned;
    synchronized (this) {
      if (closed && (worker == null || !worker.isAlive())) return;
      stopping = true;
      requestStop();
      owned = worker;
      if (owned == null) {
        consume(() -> {});
        closed = true;
        return;
      }
    }
    // An observer may close its own controller; it must be able to return before another closer
    // joins.
    if (owned == Thread.currentThread()) return;
    LockSupport.unpark(owned);
    try {
      owned.join(joinMillis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("analysis shutdown interrupted", interrupted);
    }
    if (owned.isAlive()) {
      failures.increment();
      throw new IllegalStateException("analysis consumer did not terminate");
    }
    closed = true;
  }

  synchronized Profile profile() {
    return profile(false);
  }

  synchronized Profile profile(boolean incomplete) {
    if (!closed || worker != null && worker.isAlive())
      throw new IllegalStateException("close/drain before snapshot");
    if (incomplete) accumulator.markIncomplete();
    return accumulator.snapshot();
  }

  long failures() {
    return failures.sum();
  }

  long discarded() {
    return discarded;
  }

  long drops() {
    return queue.drops();
  }

  long peakBytes() {
    return queue.peakBytes();
  }

  long retainedBytes() {
    return queue.retainedBytes();
  }

  long pending() {
    return queue.pending();
  }

  void requestStop() {
    queue.seal();
    deadline = clock.getAsLong() + drainNanos;
    running.set(false);
  }
}
