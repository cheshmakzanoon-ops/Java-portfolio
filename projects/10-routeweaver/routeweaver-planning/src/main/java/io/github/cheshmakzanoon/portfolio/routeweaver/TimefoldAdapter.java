package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.solution.*;
import ai.timefold.solver.core.api.domain.solution.cloner.SolutionCloner;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.calculator.EasyScoreCalculator;
import ai.timefold.solver.core.api.solver.*;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/**
 * Mutable objects are confined to this adapter; all emitted incumbents are immutable and validated.
 */
public final class TimefoldAdapter {
  public record Settings(long seed, int milliseconds, int steps, String sourceRevision) {
    public Settings {
      if (milliseconds < 1 || milliseconds > 30000 || steps < 1 || steps > 100000)
        throw new IllegalArgumentException("bounded solve budget required");
      Model.text(sourceRevision);
    }
  }

  public Validation solve(
      Problem p,
      Validation baseline,
      Settings settings,
      BooleanSupplier cancel,
      Consumer<Validation> onIncumbent) {
    return solveControlled(p, baseline, settings, cancel, onIncumbent, () -> {});
  }

  Validation solveControlled(
      Problem p,
      Validation baseline,
      Settings settings,
      BooleanSupplier cancel,
      Consumer<Validation> onIncumbent,
      Runnable beforeStart) {
    Heuristics.retain(p, baseline, baseline);
    long started = System.nanoTime();
    long deadline = started + Duration.ofMillis(settings.milliseconds()).toNanos();
    BooleanSupplier stopping = () -> cancel.getAsBoolean() || System.nanoTime() >= deadline;
    AtomicReference<Validation> best = new AtomicReference<>(baseline);
    onIncumbent.accept(baseline);
    if (!stopping.getAsBoolean()) {
      Validation originalMoves =
          new Heuristics()
              .improve(p, baseline, Math.min(2000, Math.max(100, p.jobs().size() * 10L)), stopping);
      if (originalMoves != baseline) {
        best.set(originalMoves);
        onIncumbent.accept(originalMoves);
      }
    }
    long remaining = Math.max(1, (deadline - System.nanoTime()) / 1000000);
    SolverConfig config =
        new SolverConfig()
            .withSolutionClass(WorkingPlan.class)
            .withEntityClasses(Assignment.class)
            .withEasyScoreCalculatorClass(Scorer.class)
            .withRandomSeed(settings.seed())
            .withTerminationConfig(
                new TerminationConfig()
                    .withSpentLimit(Duration.ofMillis(remaining))
                    .withStepCountLimit(settings.steps()));
    Solver<WorkingPlan> solver = SolverFactory.<WorkingPlan>create(config).buildSolver();
    solver.addEventListener(
        event -> {
          Validation candidate =
              new Validator().validate(p, event.getNewBestSolution().candidate());
          Validation retained = Heuristics.retain(p, best.get(), candidate);
          if (retained != best.get()) {
            best.set(retained);
            onIncumbent.accept(retained);
          }
        });
    // Cancellation is cooperative. The watcher only requests solver termination; it never marks a
    // job terminal.
    Thread watcher =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  try {
                    while (!Thread.currentThread().isInterrupted()) {
                      if (stopping.getAsBoolean()) {
                        solver.terminateEarly();
                      }
                      Thread.sleep(20);
                    }
                  } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                  }
                });
    try {
      if (!stopping.getAsBoolean()) {
        beforeStart.run();
        WorkingPlan result = solver.solve(WorkingPlan.from(p, best.get().candidate()));
        best.set(Heuristics.retain(p, best.get(), new Validator().validate(p, result.candidate())));
      }
      return best.get();
    } finally {
      watcher.interrupt();
    }
  }

  @PlanningEntity
  public static class Assignment {
    @PlanningId public String id;

    @PlanningVariable(valueRangeProviderRefs = "vehicles")
    public Integer vehicle;

    @PlanningVariable(valueRangeProviderRefs = "positions")
    public Integer position;

    public Assignment() {}

    Assignment(String id, Integer vehicle, Integer position) {
      this.id = id;
      this.vehicle = vehicle;
      this.position = position;
    }
  }

  @PlanningSolution(solutionCloner = Cloner.class)
  public static class WorkingPlan {
    @ProblemFactProperty public Problem problem;

    @ValueRangeProvider(id = "vehicles")
    @ProblemFactCollectionProperty
    public List<Integer> vehicleValues;

    @ValueRangeProvider(id = "positions")
    @ProblemFactCollectionProperty
    public List<Integer> positionValues;

    @PlanningEntityCollectionProperty public List<Assignment> assignments;
    @PlanningScore public HardMediumSoftScore score;

    public WorkingPlan() {}

    static WorkingPlan from(Problem p, Candidate c) {
      WorkingPlan plan = new WorkingPlan();
      plan.problem = p;
      plan.vehicleValues = new ArrayList<>();
      plan.vehicleValues.add(-1);
      for (int i = 0; i < p.vehicles().size(); i++) plan.vehicleValues.add(i);
      plan.positionValues = new ArrayList<>();
      for (int i = 0; i < Math.max(1, p.jobs().size()); i++) plan.positionValues.add(i);
      plan.assignments = new ArrayList<>();
      for (Job j : p.jobs()) {
        int vehicle = -1, index = 0;
        for (Route r : c.routes()) {
          int at = r.jobs().indexOf(j.id());
          if (at >= 0) {
            index = at;
            for (int i = 0; i < p.vehicles().size(); i++)
              if (p.vehicles().get(i).id().equals(r.vehicleId())) vehicle = i;
          }
        }
        plan.assignments.add(new Assignment(j.id(), vehicle, index));
      }
      return plan;
    }

    Candidate candidate() {
      List<Route> routes = new ArrayList<>();
      for (int i = 0; i < problem.vehicles().size(); i++) {
        int vi = i;
        routes.add(
            new Route(
                problem.vehicles().get(i).id(),
                assignments.stream()
                    .filter(a -> a.vehicle != null && a.vehicle == vi)
                    .sorted(
                        Comparator.comparing((Assignment a) -> a.position == null ? 0 : a.position)
                            .thenComparing(a -> a.id))
                    .map(a -> a.id)
                    .toList()));
      }
      return Model.candidate(problem, routes);
    }
  }

  public static final class Cloner implements SolutionCloner<WorkingPlan> {
    public WorkingPlan cloneSolution(WorkingPlan original) {
      WorkingPlan p = new WorkingPlan();
      p.problem = original.problem;
      p.vehicleValues = original.vehicleValues;
      p.positionValues = original.positionValues;
      p.score = original.score;
      p.assignments =
          original.assignments.stream()
              .map(a -> new Assignment(a.id, a.vehicle, a.position))
              .toList();
      return p;
    }
  }

  /**
   * Separate search scoring implementation. The reference validator does not call this calculator.
   */
  public static final class Scorer
      implements EasyScoreCalculator<WorkingPlan, HardMediumSoftScore> {
    public HardMediumSoftScore calculateScore(WorkingPlan plan) {
      Problem p = plan.problem;
      Candidate c = plan.candidate();
      Map<String, Job> jobs = p.jobMap();
      Map<String, String> cargo = new HashMap<>();
      for (Vehicle v : p.vehicles()) for (String j : v.onboard()) cargo.put(j, v.id());
      long hard = 0, unassigned = 0, soft = 0;
      for (String id : c.unassigned().keySet()) {
        unassigned = Math.addExact(unassigned, jobs.get(id).priority());
        if (p.previous().containsKey(id))
          soft = Math.addExact(soft, p.objective().disruptionWeight());
      }
      for (int vi = 0; vi < p.vehicles().size(); vi++) {
        Vehicle v = p.vehicles().get(vi);
        List<String> order = c.routes().get(vi).jobs();
        if (v.failed() && !order.isEmpty()) hard++;
        if (order.size() < v.frozenPrefix().size()
            || !order
                .subList(0, Math.min(order.size(), v.frozenPrefix().size()))
                .equals(v.frozenPrefix())) hard++;
        long load = v.remainingLoad();
        for (String id : order)
          if (!v.onboard().contains(id)) load = Math.addExact(load, jobs.get(id).grams());
        if (load > v.capacityGrams()) hard++;
        long time = v.availableAt().getEpochSecond();
        int at = v.currentLocation(), index = 0;
        for (String id : order) {
          Job j = jobs.get(id);
          if (!v.skills().containsAll(j.skills())) hard++;
          if (cargo.containsKey(id) && !cargo.get(id).equals(v.id())) hard++;
          if (!v.onboard().contains(id) && v.currentLocation() != p.depot()) hard++;
          Arc arc = p.matrix().arc(at, j.location());
          soft =
              Math.addExact(
                  soft,
                  Math.addExact(
                      Math.multiplyExact((long) arc.meters(), p.objective().metersWeight()),
                      Math.multiplyExact((long) arc.seconds(), p.objective().secondsWeight())));
          time = Math.max(Math.addExact(time, arc.seconds()), j.windowStart().getEpochSecond());
          if (time > j.windowEnd().getEpochSecond()) hard++;
          time = Math.addExact(time, j.serviceSeconds());
          if (time > v.shiftEnd().getEpochSecond()) hard++;
          load = Math.subtractExact(load, j.grams());
          if (load < 0) hard++;
          at = j.location();
          Previous old = p.previous().get(id);
          if (old != null && (!old.vehicleId().equals(v.id()) || old.index() != index))
            soft = Math.addExact(soft, p.objective().disruptionWeight());
          index++;
        }
        if (!v.failed()) {
          Arc arc = p.matrix().arc(at, p.depot());
          time = Math.addExact(time, arc.seconds());
          if (time > v.shiftEnd().getEpochSecond()) hard++;
          soft =
              Math.addExact(
                  soft,
                  Math.addExact(
                      Math.multiplyExact((long) arc.meters(), p.objective().metersWeight()),
                      Math.multiplyExact((long) arc.seconds(), p.objective().secondsWeight())));
        }
      }
      return HardMediumSoftScore.of(-hard, -unassigned, -soft);
    }
  }
}
