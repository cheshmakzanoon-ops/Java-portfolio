package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

class VerificationTest {
  @Test
  void jmhFixturesExerciseCompleteFeasibleRoutesAtEveryCorpusSize() {
    for (int jobs : new int[] {100, 250, 500}) {
      ValidationBenchmark benchmark = new ValidationBenchmark();
      benchmark.jobs = jobs;
      benchmark.setup();
      var value = benchmark.fullReferenceValidation();
      assertTrue(value.valid());
      assertEquals(value.candidate().inputHash(), benchmark.immutableProblemImportValidation());
      assertTrue(value.candidate().unassigned().isEmpty());
      assertEquals(jobs, value.candidate().routes().stream().mapToInt(r -> r.jobs().size()).sum());
    }
  }

  @Test
  void completeOracleIncludesUnassignedAssignmentsAndEmptyRoutes() {
    Problem p = Fixtures.problem(2, 2, 42, "easy");
    ExactOracle.Result result = new ExactOracle().solve(p, 1000);
    assertTrue(result.complete());
    assertEquals(11, result.leaves());
    assertNotNull(result.optimum());
    assertEquals(0, result.optimum().score().unassignedPriority());
    assertFalse(new ExactOracle().solve(p, 1).complete());
    assertNull(new ExactOracle().solve(p, 1).optimum());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExactOracle().solve(Fixtures.problem(8, 2, 42, "easy"), 10000));
  }

  @Test
  void analyticOracleCostAndZeroCapacity() {
    Problem p = Fixtures.problem(1, 1, 42, "easy");
    var result = new ExactOracle().solve(p, 100);
    Arc outward = p.matrix().arc(0, 1), back = p.matrix().arc(1, 0);
    assertTrue(result.complete());
    assertEquals(2, result.leaves());
    assertEquals(
        (long) outward.meters() + back.meters() + outward.seconds() + back.seconds(),
        result.optimum().score().operatingCost());
    Vehicle v = p.vehicles().getFirst();
    Problem noLoad =
        new Problem(
            p.tenant(),
            p.id(),
            p.version(),
            p.serviceZone(),
            p.depot(),
            p.planningInstant(),
            p.matrix(),
            p.jobs(),
            List.of(
                new Vehicle(
                    v.id(),
                    0,
                    v.skills(),
                    v.shiftStart(),
                    v.shiftEnd(),
                    v.currentLocation(),
                    v.availableAt(),
                    v.onboard(),
                    0,
                    v.frozenPrefix(),
                    false)),
            p.completed(),
            p.previous(),
            p.objective(),
            p.audit());
    assertEquals(2, new ExactOracle().solve(noLoad, 100).optimum().score().unassignedPriority());
  }

  @Test
  void breakdownKeepsCompletedCargoAndUrgentAtDepot() {
    Problem p = Fixtures.demo();
    Validation baseline = new Heuristics().baseline(p);
    assertTrue(baseline.valid());
    assertEquals(0, baseline.candidate().unassigned().size());
    assertTrue(
        baseline.candidate().routes().stream()
            .filter(r -> r.vehicleId().equals("V5"))
            .findFirst()
            .orElseThrow()
            .jobs()
            .isEmpty());
    Timeline broken =
        baseline.timelines().stream().filter(t -> !t.stops().isEmpty()).findFirst().orElseThrow();
    var at = broken.stops().getFirst().departure();
    Job urgent = new Job("URGENT", 0, 1000, 30, at, at.plusSeconds(3600), Set.of("urgent"), 100);
    List<Replanner.Observation> observations = new ArrayList<>();
    for (Vehicle v : p.vehicles()) {
      Timeline t =
          baseline.timelines().stream()
              .filter(x -> x.vehicleId().equals(v.id()))
              .findFirst()
              .orElseThrow();
      int prefix = 0;
      while (prefix < t.stops().size() && !t.stops().get(prefix).departure().isAfter(at)) prefix++;
      observations.add(
          new Replanner.Observation(v.id(), prefix, v.id().equals(broken.vehicleId()), 1, true));
    }
    Problem revised =
        new Replanner().replan(p, baseline.candidate(), at, observations, List.of(urgent));
    assertTrue(
        revised.completed().stream()
            .anyMatch(c -> c.jobId().equals(broken.stops().getFirst().jobId())));
    assertTrue(revised.audit().stream().anyMatch(a -> a.contains("RELEASE_FAILED_COMMITMENTS")));
    Validation next = new Heuristics().baseline(revised);
    assertTrue(next.valid(), next.violations().toString());
    assertEquals(
        "STRANDED_CARGO",
        next.explanations().values().stream()
            .filter("STRANDED_CARGO"::equals)
            .findFirst()
            .orElseThrow());
    assertTrue(
        next.candidate().routes().stream()
            .filter(r -> r.vehicleId().equals("V5"))
            .findFirst()
            .orElseThrow()
            .jobs()
            .contains("URGENT"));
    assertFalse(
        next.candidate().routes().stream()
            .flatMap(r -> r.jobs().stream())
            .anyMatch(id -> revised.completed().stream().anyMatch(c -> c.jobId().equals(id))));
    assertThrows(IllegalArgumentException.class, () -> Heuristics.retain(revised, next, baseline));
  }

  @Test
  void maximumBoundExhaustsAll95901AssignmentsAndPermutations() {
    Problem p = Fixtures.problem(7, 2, 42, "easy");
    ExactOracle.Result result = new ExactOracle().solve(p, 100000);
    assertTrue(result.complete());
    assertEquals(95901, result.leaves());
    assertNotNull(result.optimum());
    assertTrue(result.optimum().valid());
    assertEquals(0, result.optimum().score().unassignedPriority());
  }

  @Test
  void exhaustedOracleCanProveNoDispatchableCandidateAndInterruptedCannot() {
    Problem template = Fixtures.problem(1, 1, 42, "easy");
    Vehicle v = template.vehicles().getFirst();
    Problem impossible =
        new Problem(
            template.tenant(),
            template.id(),
            1,
            template.serviceZone(),
            0,
            template.planningInstant(),
            template.matrix(),
            List.of(),
            List.of(
                new Vehicle(
                    v.id(),
                    v.capacityGrams(),
                    v.skills(),
                    v.shiftStart(),
                    v.shiftStart(),
                    1,
                    v.shiftStart(),
                    Set.of(),
                    0,
                    List.of(),
                    false)),
            List.of(),
            Map.of(),
            template.objective(),
            List.of());
    ExactOracle.Result result = new ExactOracle().solve(impossible, 100);
    assertTrue(result.complete());
    assertNull(result.optimum());
    assertEquals("INFEASIBLE", result.status());
    assertEquals(
        "INCONCLUSIVE", new ExactOracle().solve(Fixtures.problem(2, 2, 42, "easy"), 1).status());
  }
}
