package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Full independent validation including all directed arcs, loads, windows, scores and partition.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms512m", "-Xmx768m"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class ValidationBenchmark {
  @Param({"100", "250", "500"})
  public int jobs;

  private Problem problem;
  private Candidate candidate;
  private Validator validator;
  private String inputJson;

  @Setup(Level.Trial)
  public void setup() {
    int vehicles = jobs == 100 ? 5 : jobs == 250 ? 10 : 25;
    problem = Fixtures.problem(jobs, vehicles, 7000 + jobs, "easy");
    List<List<String>> orders = new ArrayList<>();
    for (int i = 0; i < vehicles; i++) orders.add(new ArrayList<>());
    for (int i = 0; i < jobs; i++) orders.get(i % vehicles).add(problem.jobs().get(i).id());
    List<Route> routes = new ArrayList<>();
    for (int i = 0; i < vehicles; i++)
      routes.add(new Route(problem.vehicles().get(i).id(), orders.get(i)));
    candidate = Model.candidate(problem, routes);
    inputJson = Json.write(problem);
    validator = new Validator();
    Validation checked = validator.validate(problem, candidate);
    if (!checked.valid() || !checked.candidate().unassigned().isEmpty())
      throw new IllegalStateException(
          "benchmark needs an independently feasible complete route set: " + checked.violations());
  }

  @Benchmark
  public String immutableProblemImportValidation() {
    return Json.read(inputJson, Problem.class).canonicalHash();
  }

  @Benchmark
  public Validation fullReferenceValidation() {
    return validator.validate(problem, candidate);
  }
}
