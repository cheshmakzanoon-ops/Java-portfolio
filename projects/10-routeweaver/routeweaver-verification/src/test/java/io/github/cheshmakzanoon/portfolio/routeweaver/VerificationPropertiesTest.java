package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class VerificationPropertiesTest {

  @Property(tries = 20, seed = "1042017")
  void independentlyValidatedHeuristicNeverBeatsCompletedOracle(
      @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10000) int seed) {
    Problem p = Fixtures.problem(3, 2, seed, "easy");
    Validation baseline = new Heuristics().baseline(p);
    ExactOracle.Result exact = new ExactOracle().solve(p, 100000);
    assertTrue(exact.complete());
    assertTrue(baseline.valid());
    assertTrue(baseline.score().compareTo(exact.optimum().score()) >= 0);
    List<Route> routes = new ArrayList<>(baseline.candidate().routes());
    Route nonempty = routes.stream().filter(r -> !r.jobs().isEmpty()).findFirst().orElseThrow();
    List<String> duplicated = new ArrayList<>(nonempty.jobs());
    duplicated.add(duplicated.getFirst());
    routes.set(routes.indexOf(nonempty), new Route(nonempty.vehicleId(), duplicated));
    assertTrue(
        new Validator()
            .validate(p, Model.candidate(p, routes)).violations().stream()
                .anyMatch(v -> v.code().equals("DUPLICATE_JOB")));
  }

  @Property(tries = 20, seed = "1042020")
  void generatedSingleConstraintMutationsAreRejected(
      @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10000) int seed) {
    Problem p = Fixtures.problem(3, 2, seed, "easy");
    Validation feasible = new Heuristics().baseline(p);
    assertTrue(feasible.valid());
    String assigned =
        feasible.candidate().routes().stream()
            .flatMap(r -> r.jobs().stream())
            .findFirst()
            .orElseThrow();
    Job selected = p.jobMap().get(assigned);
    for (int mutation = 0; mutation < 3; mutation++) {
      int kind = mutation;
      List<Job> jobs =
          p.jobs().stream()
              .map(
                  j ->
                      !j.id().equals(assigned)
                          ? j
                          : new Job(
                              j.id(),
                              j.location(),
                              kind == 0 ? 1000000 : j.grams(),
                              j.serviceSeconds(),
                              j.windowStart(),
                              kind == 1 ? j.windowStart() : j.windowEnd(),
                              kind == 2 ? Set.of("impossible-skill") : j.skills(),
                              j.priority()))
              .toList();
      Problem changed =
          new Problem(
              p.tenant(),
              p.id(),
              p.version(),
              p.serviceZone(),
              p.depot(),
              p.planningInstant(),
              p.matrix(),
              jobs,
              p.vehicles(),
              p.completed(),
              p.previous(),
              p.objective(),
              p.audit());
      Validation invalid =
          new Validator()
              .validate(changed, Model.candidate(changed, feasible.candidate().routes()));
      String expected = kind == 0 ? "CAPACITY" : kind == 1 ? "WINDOW" : "SKILLS";
      assertTrue(
          invalid.violations().stream().anyMatch(v -> v.code().equals(expected)),
          invalid.violations().toString());
    }
    Route route =
        feasible.candidate().routes().stream()
            .filter(r -> !r.jobs().isEmpty())
            .findFirst()
            .orElseThrow();
    String different =
        p.jobs().stream()
            .map(Job::id)
            .filter(j -> !j.equals(route.jobs().getFirst()))
            .findFirst()
            .orElseThrow();
    List<Vehicle> vehicles =
        p.vehicles().stream()
            .map(
                v ->
                    !v.id().equals(route.vehicleId())
                        ? v
                        : new Vehicle(
                            v.id(),
                            v.capacityGrams(),
                            v.skills(),
                            v.shiftStart(),
                            v.shiftEnd(),
                            v.currentLocation(),
                            v.availableAt(),
                            v.onboard(),
                            v.remainingLoad(),
                            List.of(different),
                            v.failed()))
            .toList();
    Problem frozen =
        new Problem(
            p.tenant(),
            p.id(),
            p.version(),
            p.serviceZone(),
            p.depot(),
            p.planningInstant(),
            p.matrix(),
            p.jobs(),
            vehicles,
            p.completed(),
            p.previous(),
            p.objective(),
            p.audit());
    assertTrue(
        new Validator()
                .validate(frozen, Model.candidate(frozen, feasible.candidate().routes()))
                .violations()
                .stream()
                .anyMatch(v -> v.code().equals("FROZEN_PREFIX")));
  }
}
