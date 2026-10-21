package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Builds and improves an independently valid same-input incumbent; it never marks a job terminal.
 */
final class PlanningTask implements Dispatcher.SolveTask {
  private final Store store;

  PlanningTask(Store store) {
    this.store = store;
  }

  @Override
  public void execute(Store.JobView job, long deadline, BooleanSupplier stop) {
    Problem p = store.load(job.tenant(), job.problemId(), job.problemVersion());
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
                  job.seed(), (int) Math.min(30000, remaining), job.steps(), job.sourceRevision()),
              stop,
              value -> store.record(job, value));
    }
  }
}
