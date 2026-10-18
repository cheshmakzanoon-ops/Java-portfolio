package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import java.util.UUID;
import java.util.concurrent.*;

/** One CPU worker and sixteen durable waiting jobs; no CPU solving on HTTP or virtual threads. */
public final class Dispatcher implements AutoCloseable {
  public static final int MAX_OUTSTANDING = 17;
  private final Store store;
  private final String sourceRevision;
  private final String owner = UUID.randomUUID().toString();
  private final ThreadPoolExecutor cpu =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final Semaphore capacity = new Semaphore(1);

  public Dispatcher(Store store, String sourceRevision) {
    Model.text(sourceRevision);
    this.store = store;
    this.sourceRevision = sourceRevision;
    store.recover();
    clock.scheduleWithFixedDelay(this::poll, 0, 200, TimeUnit.MILLISECONDS);
  }

  private void poll() {
    if (!capacity.tryAcquire()) return;
    boolean submitted = false;
    try {
      Store.JobView job = store.claim(owner, 15);
      if (job != null) {
        cpu.execute(() -> run(job));
        submitted = true;
      }
    } catch (RuntimeException e) {
      System.err.println("RouteWeaver dispatch poll failed: " + e.getClass().getSimpleName());
    } finally {
      if (!submitted) capacity.release();
    }
  }

  private void run(Store.JobView job) {
    ScheduledFuture<?> heartbeat =
        clock.scheduleWithFixedDelay(() -> store.heartbeat(job, 15), 3, 3, TimeUnit.SECONDS);
    try {
      if (!sourceRevision.equals(job.sourceRevision())) {
        String submitted = job.sourceRevision();
        String reason =
            "SOURCE_REVISION_MISMATCH submitted="
                + submitted.substring(0, Math.min(200, submitted.length()))
                + " current="
                + sourceRevision;
        store.finish(job, reason);
        return;
      }
      Problem p = store.load(job.tenant(), job.problemId(), job.problemVersion());
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(job.budgetMs());
      CancellationPoll cancellation =
          new CancellationPoll(() -> store.cancellation(job), System::nanoTime);
      java.util.function.BooleanSupplier stop =
          () ->
              Thread.currentThread().isInterrupted()
                  || System.nanoTime() >= deadline
                  || cancellation.getAsBoolean();
      Validation baseline = new Heuristics().baseline(p, stop);
      store.record(job, baseline);
      if (!p.previous().isEmpty()) {
        java.util.List<Route> prior =
            p.vehicles().stream()
                .map(
                    v ->
                        new Route(
                            v.id(),
                            p.previous().entrySet().stream()
                                .filter(e -> e.getValue().vehicleId().equals(v.id()))
                                .sorted(
                                    java.util.Comparator.comparingInt(
                                            (java.util.Map.Entry<String, Previous> e) ->
                                                e.getValue().index())
                                        .thenComparing(java.util.Map.Entry::getKey))
                                .map(java.util.Map.Entry::getKey)
                                .toList()))
                .toList();
        Validation repaired = new Replanner().repair(p, Model.candidate(p, prior));
        store.record(job, repaired);
        baseline = Heuristics.retain(p, baseline, repaired);
      }
      if (!stop.getAsBoolean()) {
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        new TimefoldAdapter()
            .solve(
                p,
                baseline,
                new TimefoldAdapter.Settings(
                    job.seed(),
                    (int) Math.min(30000, remaining),
                    job.steps(),
                    job.sourceRevision()),
                stop,
                value -> store.record(job, value));
      }
      store.finish(job, null);
    } catch (RuntimeException failure) {
      String reason =
          failure instanceof IllegalArgumentException
                  && failure.getMessage() != null
                  && failure.getMessage().startsWith("BLOCKED_")
              ? failure.getMessage()
              : "SOLVE_FAILURE: " + failure.getClass().getSimpleName();
      store.finish(job, reason.substring(0, Math.min(500, reason.length())));
    } finally {
      heartbeat.cancel(false);
      capacity.release();
    }
  }

  @Override
  public void close() {
    clock.shutdownNow();
    cpu.shutdownNow();
  }
}
