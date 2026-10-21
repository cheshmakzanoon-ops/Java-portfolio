package io.github.cheshmakzanoon.portfolio.switchback;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one CPU task, its heartbeat and its shutdown fence; HTTP threads never solve. */
public final class Dispatcher implements AutoCloseable {
  public static final int MAX_OUTSTANDING = 17;
  private static final int LEASE_SECONDS = 15;
  private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

  @FunctionalInterface
  interface SolveTask {
    void execute(Store.JobView job, long deadline, BooleanSupplier stop);
  }

  private final Store store;
  private final String sourceRevision;
  private final SolveTask task;
  private final String owner = UUID.randomUUID().toString();
  private final ThreadPoolExecutor cpu;
  private final ScheduledExecutorService clock;
  private final ScheduledFuture<?> polling;
  private final Duration heartbeatInterval;
  private final Semaphore capacity = new Semaphore(1);
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicReference<Store.JobView> active = new AtomicReference<>();

  public Dispatcher(Store store, String sourceRevision) {
    this(
        store,
        sourceRevision,
        new PlanningTask(store),
        Duration.ofMillis(200),
        Duration.ofSeconds(3));
  }

  Dispatcher(
      Store store,
      String sourceRevision,
      SolveTask task,
      Duration pollInterval,
      Duration heartbeatInterval) {
    Model.text(sourceRevision);
    if (pollInterval.isZero()
        || pollInterval.isNegative()
        || heartbeatInterval.isZero()
        || heartbeatInterval.isNegative()
        || heartbeatInterval.compareTo(Duration.ofSeconds(LEASE_SECONDS)) >= 0)
      throw new IllegalArgumentException(
          "positive polling and sub-lease heartbeat intervals required");
    this.store = Objects.requireNonNull(store);
    this.sourceRevision = sourceRevision;
    this.task = Objects.requireNonNull(task);
    this.heartbeatInterval = heartbeatInterval;
    // Do not allocate owned threads before the startup database check succeeds.
    store.recover();
    cpu =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().name("switchback-" + owner + "-cpu-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    clock =
        Executors.newScheduledThreadPool(
            2, Thread.ofPlatform().name("switchback-" + owner + "-clock-", 0).factory());
    polling =
        clock.scheduleWithFixedDelay(this::poll, 0, pollInterval.toNanos(), TimeUnit.NANOSECONDS);
  }

  private void poll() {
    if (closing.get() || !capacity.tryAcquire()) return;
    Store.JobView claimed = null;
    boolean submitted = false;
    try {
      claimed = store.claim(owner, LEASE_SECONDS);
      if (claimed == null) return;
      active.set(claimed);
      if (closing.get()) {
        store.release(claimed, "SERVICE_SHUTDOWN_REQUEUED");
        active.compareAndSet(claimed, null);
        return;
      }
      Store.JobView job = claimed;
      cpu.execute(() -> run(job));
      submitted = true;
    } catch (RuntimeException failure) {
      if (claimed != null) {
        releaseQuietly(claimed, "EXECUTOR_REJECTED_REQUEUED");
        active.compareAndSet(claimed, null);
      }
      LOG.warn("Dispatch poll failed; owner={}", owner, failure);
    } finally {
      if (!submitted) capacity.release();
    }
  }

  private void run(Store.JobView job) {
    ScheduledFuture<?> heartbeat = null;
    AtomicReference<String> leaseFailure = new AtomicReference<>();
    try {
      // Registration is inside the guarded region: close may have stopped the scheduler already.
      heartbeat =
          clock.scheduleWithFixedDelay(
              () -> {
                try {
                  if (!store.heartbeat(job, LEASE_SECONDS))
                    leaseFailure.compareAndSet(null, "LEASE_LOST");
                } catch (RuntimeException failure) {
                  leaseFailure.compareAndSet(null, "LEASE_HEARTBEAT_FAILURE");
                  LOG.warn("Lease heartbeat failed; job={}", job.id(), failure);
                }
              },
              heartbeatInterval.toNanos(),
              heartbeatInterval.toNanos(),
              TimeUnit.NANOSECONDS);
      if (!sourceRevision.equals(job.sourceRevision())) {
        store.finish(
            job,
            "SOURCE_REVISION_MISMATCH submitted="
                + job.sourceRevision()
                + " current="
                + sourceRevision);
        return;
      }
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(job.budgetMs());
      CancellationPoll cancellation =
          new CancellationPoll(() -> store.cancellation(job), System::nanoTime);
      BooleanSupplier stop =
          () ->
              closing.get()
                  || Thread.currentThread().isInterrupted()
                  || leaseFailure.get() != null
                  || System.nanoTime() >= deadline
                  || cancellation.getAsBoolean();
      if (!closing.get()) task.execute(job, deadline, stop);
      if (closing.get() || Thread.currentThread().isInterrupted())
        store.release(job, "SERVICE_SHUTDOWN_REQUEUED");
      else store.finish(job, leaseFailure.get());
    } catch (RuntimeException failure) {
      if (closing.get() || Thread.currentThread().isInterrupted())
        releaseQuietly(job, "SERVICE_SHUTDOWN_REQUEUED");
      else {
        String reason =
            failure instanceof IllegalArgumentException
                    && failure.getMessage() != null
                    && failure.getMessage().startsWith("BLOCKED_")
                ? failure.getMessage()
                : "SOLVE_FAILURE: " + failure.getClass().getSimpleName();
        try {
          store.finish(job, reason.substring(0, Math.min(500, reason.length())));
        } catch (RuntimeException terminalFailure) {
          LOG.warn(
              "Could not persist solve failure; lease recovery remains authoritative; job={}",
              job.id(),
              terminalFailure);
        }
      }
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      active.compareAndSet(job, null);
      capacity.release();
    }
  }

  private void releaseQuietly(Store.JobView job, String reason) {
    try {
      store.release(job, reason);
    } catch (RuntimeException failure) {
      LOG.warn(
          "Could not release owned claim; database lease expiry will recover it; job={}",
          job.id(),
          failure);
    }
  }

  @Override
  public void close() {
    if (!closing.compareAndSet(false, true)) return;
    polling.cancel(false);
    cpu.shutdownNow();
    Store.JobView job = active.get();
    if (job != null) releaseQuietly(job, "SERVICE_SHUTDOWN_REQUEUED");
    clock.shutdownNow();
    boolean interrupted = false;
    try {
      if (!cpu.awaitTermination(15, TimeUnit.SECONDS))
        LOG.warn("CPU task exceeded shutdown wait; its claim is fenced; owner={}", owner);
      if (!clock.awaitTermination(5, TimeUnit.SECONDS))
        LOG.warn(
            "Scheduler exceeded shutdown wait; database timeouts still apply; owner={}", owner);
    } catch (InterruptedException failure) {
      interrupted = true;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  boolean isClosing() {
    return closing.get();
  }

  boolean terminated() {
    return cpu.isTerminated() && clock.isTerminated();
  }
}
