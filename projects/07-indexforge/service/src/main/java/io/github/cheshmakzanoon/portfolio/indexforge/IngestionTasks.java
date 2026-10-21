package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns admitted ingestion attempts until the worker exits, including cooperative cancellation.
 * Durable registration and completion remain the engine's responsibility. All task transitions,
 * submission and shutdown admission linearize under one monitor; user work runs outside it.
 */
final class IngestionTasks implements AutoCloseable {
  enum State {
    REGISTERED,
    QUEUED,
    RUNNING,
    CANCELLING,
    COMPLETE,
    CANCELLED,
    FAILED
  }

  @FunctionalInterface
  interface Work {
    void execute() throws Exception;
  }

  static final class Stopping extends RejectedExecutionException {
    Stopping() {
      super("ingestion service is stopping");
    }
  }

  record Counts(long failures, int queued, int running, int cancelling) {}

  private final Object monitor = new Object();
  private final ThreadPoolExecutor executor;
  private final int retainedOutcomes;
  private final long shutdownNanos;
  private final Map<String, Attempt> active = new HashMap<>();
  private final LinkedHashMap<String, State> outcomes = new LinkedHashMap<>();
  private final Map<String, Long> failures = new HashMap<>();
  private boolean stopping;

  IngestionTasks() {
    this(2, 8, 512, Duration.ofSeconds(10));
  }

  IngestionTasks(int workers, int queueCapacity, int retainedOutcomes, Duration shutdownTimeout) {
    if (workers < 1 || queueCapacity < 1 || retainedOutcomes < 0)
      throw new IllegalArgumentException("ingestion task limits");
    Objects.requireNonNull(shutdownTimeout);
    if (shutdownTimeout.isNegative() || shutdownTimeout.isZero())
      throw new IllegalArgumentException("ingestion shutdown timeout");
    this.shutdownNanos = shutdownTimeout.toNanos();
    this.retainedOutcomes = retainedOutcomes;
    executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().name("indexforge-ingest-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Returns the existing attempt if a duplicate is still owned. A cancellation request is not
   * termination: resuming it cannot admit a second execution before its finally block completes.
   */
  State submit(String tenant, String id, Work work) {
    requireIdentity(tenant, id);
    Objects.requireNonNull(work);
    synchronized (monitor) {
      if (stopping) throw new Stopping();
      Attempt existing = active.get(id);
      if (existing != null) {
        if (!existing.tenant.equals(tenant)) throw new SecurityException("access denied");
        return existing.state;
      }
      Attempt attempt = new Attempt(tenant, id, work);
      active.put(id, attempt);
      try {
        executor.execute(attempt);
      } catch (RejectedExecutionException rejected) {
        active.remove(id, attempt);
        // The durable registration exists, but this attempt was never admitted. A prior
        // terminal outcome remains meaningful when a retry cannot enter the queue.
        throw rejected;
      }
      outcomes.remove(id);
      return State.QUEUED;
    }
  }

  State state(String id) {
    synchronized (monitor) {
      Attempt attempt = active.get(id);
      return attempt == null ? outcomes.getOrDefault(id, State.REGISTERED) : attempt.state;
    }
  }

  State cancel(String id) {
    synchronized (monitor) {
      Attempt attempt = active.get(id);
      if (attempt != null) cancel(attempt);
      return state(id);
    }
  }

  private void cancel(Attempt attempt) {
    if (attempt.state == State.QUEUED) {
      // A worker may already have dequeued this runnable but cannot claim it until it takes
      // our monitor. Its identity check below keeps it inert even if a retry gets admitted.
      executor.remove(attempt);
      finish(attempt, State.CANCELLED);
    } else if (attempt.state == State.RUNNING) {
      attempt.state = State.CANCELLING;
      // Interrupt while ownership is locked: the worker cannot exit and reuse its thread
      // for an unrelated job between selecting this thread and sending the interrupt.
      attempt.runner.interrupt();
    }
  }

  Counts counts(String tenant) {
    synchronized (monitor) {
      int queued = 0, running = 0, cancelling = 0;
      for (Attempt attempt : active.values()) {
        if (!attempt.tenant.equals(tenant)) continue;
        switch (attempt.state) {
          case QUEUED -> queued++;
          case RUNNING -> running++;
          case CANCELLING -> cancelling++;
          default -> throw new IllegalStateException("terminal attempt retained as active");
        }
      }
      return new Counts(failures.getOrDefault(tenant, 0L), queued, running, cancelling);
    }
  }

  private void finish(Attempt attempt, State result) {
    if (!active.remove(attempt.id, attempt)) return;
    attempt.state = result;
    attempt.runner = null;
    if (result == State.FAILED) failures.merge(attempt.tenant, 1L, Long::sum);
    if (retainedOutcomes > 0) {
      outcomes.remove(attempt.id);
      outcomes.put(attempt.id, result);
      while (outcomes.size() > retainedOutcomes)
        outcomes.remove(outcomes.keySet().iterator().next());
    }
  }

  private final class Attempt implements Runnable {
    final String tenant;
    final String id;
    final Work work;
    State state = State.QUEUED;
    Thread runner;

    Attempt(String tenant, String id, Work work) {
      this.tenant = tenant;
      this.id = id;
      this.work = work;
    }

    @Override
    public void run() {
      synchronized (monitor) {
        if (active.get(id) != this || state != State.QUEUED) return;
        runner = Thread.currentThread();
        state = State.RUNNING;
      }
      boolean completed = false;
      try {
        work.execute();
        completed = true;
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        // HTTP state deliberately contains no exception text or source paths. The durable
        // job can be inspected and resumed through the same authorization boundary.
      } finally {
        synchronized (monitor) {
          // Successful work may have committed before observing the interrupt. Cancellation
          // is cooperative and must never relabel an acknowledged publication as cancelled.
          finish(
              this,
              completed
                  ? State.COMPLETE
                  : state == State.CANCELLING ? State.CANCELLED : State.FAILED);
        }
      }
    }
  }

  private static void requireIdentity(String tenant, String id) {
    if (tenant == null || tenant.isBlank() || id == null || id.isBlank())
      throw new IllegalArgumentException("ingestion task identity");
  }

  @Override
  public void close() throws IOException {
    synchronized (monitor) {
      stopping = true;
      for (Attempt attempt : new ArrayList<>(active.values())) cancel(attempt);
      // Queued attempts have already been retired under the monitor. shutdownNow also
      // prevents a worker from taking any remaining stale runnable after shutdown starts.
      executor.shutdownNow();
    }
    try {
      if (!executor.awaitTermination(shutdownNanos, TimeUnit.NANOSECONDS))
        throw new IOException("ingestion shutdown deadline exceeded");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("ingestion shutdown interrupted", interrupted);
    }
  }
}
