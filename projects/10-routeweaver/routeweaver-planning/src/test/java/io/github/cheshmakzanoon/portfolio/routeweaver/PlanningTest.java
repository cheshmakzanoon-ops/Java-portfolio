package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanningTest {
  static final Instant NOW = Instant.parse("2026-10-17T23:00:00Z");

  static Problem fixture() {
    return problem(
        List.of(job("A", 1), job("B", 2)),
        List.of(vehicle("V", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(1000))),
        Map.of());
  }

  static Job job(String id, int location) {
    return new Job(id, location, 1000, 5, NOW, NOW.plusSeconds(900), Set.of("normal"), 1);
  }

  static Vehicle vehicle(
      String id,
      int at,
      Set<String> onboard,
      int load,
      List<String> frozen,
      boolean failed,
      Instant end) {
    return new Vehicle(
        id, 2000, Set.of("normal"), NOW, end, at, NOW, onboard, load, frozen, failed);
  }

  static Problem problem(List<Job> jobs, List<Vehicle> vs, Map<String, Previous> previous) {
    return new Problem(
        "tenant",
        "P",
        1,
        ZoneId.of("America/Halifax"),
        0,
        NOW,
        new Matrix(
            "m1",
            List.of(
                List.of(new Arc(0, 0), new Arc(10, 100), new Arc(100, 1000)),
                List.of(new Arc(10, 100), new Arc(0, 0), new Arc(10, 100)),
                List.of(new Arc(10, 100), new Arc(100, 1000), new Arc(0, 0)))),
        jobs,
        vs,
        List.of(),
        previous,
        new Objective("o1", 1, 1, 1000),
        List.of());
  }

  static Validation checked(Problem p, String... ids) {
    Validation checked =
        new Validator().validate(p, Model.candidate(p, List.of(new Route("V", List.of(ids)))));
    if (new HashSet<>(List.of(ids)).size() == ids.length
        && Arrays.stream(ids).allMatch(p.jobMap()::containsKey)
        && p.vehicles().size() == 1) {
      var working = TimefoldAdapter.WorkingPlan.from(p, checked.candidate());
      var score = new TimefoldAdapter.Scorer().calculateScore(working);
      assertEquals(-checked.score().hard(), score.hardScore(), checked.violations().toString());
      assertEquals(-checked.score().unassignedPriority(), score.mediumScore());
      assertEquals(-checked.score().soft(), score.softScore());
    }
    return checked;
  }

  static void code(Validation v, String code) {
    assertFalse(v.valid());
    assertTrue(
        v.violations().stream().anyMatch(e -> e.code().equals(code)), v.violations().toString());
  }

  @Test
  void analyticDirectedCostAndFullReversal() {
    Problem p = fixture();
    Validation forward = checked(p, "A", "B"), reverse = checked(p, "B", "A");
    assertTrue(forward.valid());
    assertEquals(330, forward.score().operatingCost());
    assertEquals(2310, reverse.score().operatingCost());
    assertEquals(NOW.plusSeconds(40), forward.timelines().getFirst().returnedAt());
    assertEquals(0, forward.timelines().getFirst().stops().getLast().loadAfter());
    assertEquals(
        List.of("B", "A"),
        Heuristics.reverse(forward.candidate().routes(), 0, 0, 1).getFirst().jobs());
  }

  @Test
  void partitionDuplicateAndUnknown() {
    Problem p = fixture();
    code(checked(p, "A", "A"), "DUPLICATE_JOB");
    code(checked(p, "completed"), "UNKNOWN_JOB");
    Candidate missing =
        new Candidate(p.canonicalHash(), List.of(new Route("V", List.of("A"))), Map.of());
    code(new Validator().validate(p, missing), "PARTITION");
    Candidate overlap =
        new Candidate(
            p.canonicalHash(),
            List.of(new Route("V", List.of("A"))),
            Map.of("A", "x", "B", "x", "unknown", "x"));
    code(new Validator().validate(p, overlap), "UNKNOWN_UNASSIGNED");
    code(
        new Validator().validate(p, new Candidate("wrong", List.of(), Map.of("A", "x", "B", "x"))),
        "INPUT_VERSION");
    code(
        new Validator()
            .validate(p, new Candidate(p.canonicalHash(), List.of(), Map.of("A", "x", "B", "x"))),
        "MISSING_ROUTE");
    code(
        new Validator()
            .validate(
                p,
                Model.candidate(
                    p,
                    List.of(
                        new Route("unknown", List.of()),
                        new Route("V", List.of()),
                        new Route("V", List.of())))),
        "DUPLICATE_ROUTE");
  }

  @Test
  void skillsCapacityWindowsShiftAndReturn() {
    Problem p = fixture();
    Job a = p.jobs().getFirst();
    Problem skill =
        problem(
            List.of(new Job("A", 1, 1000, 5, NOW, NOW.plusSeconds(900), Set.of("hazmat"), 1)),
            p.vehicles(),
            Map.of());
    code(checked(skill, "A"), "SKILLS");
    assertEquals("SKILLS_OR_VEHICLE_UNAVAILABLE", checked(skill).explanations().get("A"));
    Problem capacity =
        problem(
            List.of(new Job("A", 1, 3000, 5, NOW, NOW.plusSeconds(900), Set.of("normal"), 1)),
            p.vehicles(),
            Map.of());
    code(checked(capacity, "A"), "CAPACITY");
    assertEquals("CAPACITY", checked(capacity).explanations().get("A"));
    Problem window =
        problem(
            List.of(new Job("A", 1, 1000, 5, NOW, NOW.plusSeconds(9), a.skills(), 1)),
            p.vehicles(),
            Map.of());
    code(checked(window, "A"), "WINDOW");
    Problem shift =
        problem(
            List.of(a),
            List.of(vehicle("V", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(14))),
            Map.of());
    code(checked(shift, "A"), "SHIFT");
    Problem back =
        problem(
            List.of(a),
            List.of(vehicle("V", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(24))),
            Map.of());
    code(checked(back, "A"), "RETURN_SHIFT");
    Problem waiting =
        problem(
            List.of(
                new Job("A", 1, 1000, 5, NOW.plusSeconds(20), NOW.plusSeconds(20), a.skills(), 1)),
            p.vehicles(),
            Map.of());
    assertEquals(
        NOW.plusSeconds(20),
        checked(waiting, "A").timelines().getFirst().stops().getFirst().serviceStart());
    assertTrue(checked(waiting, "A").valid());
  }

  @Test
  void residualCargoFreezeAndStranded() {
    Problem p = fixture();
    Problem moving =
        problem(
            p.jobs(),
            List.of(vehicle("V", 1, Set.of("A"), 1000, List.of("A"), false, NOW.plusSeconds(1000))),
            Map.of());
    assertTrue(checked(moving, "A").valid());
    code(checked(moving, "A", "B"), "DEPOT_CARGO");
    code(checked(moving, "B", "A"), "FROZEN_PREFIX");
    Problem failed =
        problem(
            p.jobs(),
            List.of(vehicle("V", 1, Set.of("A"), 1000, List.of(), true, NOW.plusSeconds(1000))),
            Map.of());
    code(checked(failed, "A"), "FAILED_VEHICLE");
    assertEquals("STRANDED_CARGO", checked(failed).explanations().get("A"));
    assertTrue(checked(failed).valid());
    Problem two =
        problem(
            p.jobs(),
            List.of(
                vehicle("V", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(1000)),
                vehicle("W", 1, Set.of("A"), 1000, List.of(), false, NOW.plusSeconds(1000))),
            Map.of());
    code(
        new Validator()
            .validate(
                two,
                Model.candidate(
                    two, List.of(new Route("V", List.of("A")), new Route("W", List.of())))),
        "CARGO_LOCATION");
  }

  @Test
  void objectiveAndIncumbentRetention() {
    Problem p = problem(fixture().jobs(), fixture().vehicles(), Map.of("A", new Previous("V", 0)));
    Validation original = checked(p, "A", "B"), worse = checked(p, "B", "A");
    assertEquals(1000, worse.score().disruption());
    assertSame(original, Heuristics.retain(p, original, worse));
    assertEquals(1000, checked(p).score().disruption());
    assertEquals("FULLY_FEASIBLE", original.feasibility());
    assertEquals("PARTIAL_FEASIBLE", checked(p, "A").feasibility());
    assertEquals("HARD_VIOLATIONS", checked(p, "A", "A").feasibility());
    assertTrue(new Score(0, 1, 100000, 0).compareTo(new Score(1, 0, 0, 0)) < 0);
    assertTrue(new Score(0, 0, 100000, 0).compareTo(new Score(0, 1, 0, 0)) < 0);
    assertThrows(ArithmeticException.class, () -> new Score(0, 0, Long.MAX_VALUE, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> Heuristics.retain(p, original, checked(fixture(), "A", "B")));
  }

  @Test
  void baselineMovesAndBoundedTimefold() {
    Problem p = fixture();
    Validation b = new Heuristics().baseline(p);
    assertEquals(330, b.score().operatingCost());
    assertTrue(b.valid());
    Validation improved = new Heuristics().improve(p, checked(p, "B", "A"), 1000, () -> false);
    assertEquals(330, improved.score().operatingCost());
    assertEquals(
        List.of("B", "A"), Heuristics.swap(b.candidate().routes(), 0, 0, 0, 1).getFirst().jobs());
    assertEquals(
        List.of("B", "A"),
        Heuristics.relocate(b.candidate().routes(), 0, 0, 0, 2).getFirst().jobs());
    assertTrue(new Heuristics().baseline(p, () -> true).candidate().unassigned().size() == 2);
    List<Validation> events = new ArrayList<>();
    Validation result =
        new TimefoldAdapter()
            .solve(
                p,
                b,
                new TimefoldAdapter.Settings(42, 1000, 30, "test-source"),
                () -> false,
                events::add);
    assertTrue(result.valid());
    assertTrue(result.score().compareTo(b.score()) <= 0);
    assertFalse(events.isEmpty());
  }

  @Test
  void sameConstraintsSeparateTimefoldScorer() {
    Problem p = fixture();
    Validation v = checked(p, "B", "A");
    var working = TimefoldAdapter.WorkingPlan.from(p, v.candidate());
    var score = new TimefoldAdapter.Scorer().calculateScore(working);
    assertEquals(-v.score().hard(), score.hardScore());
    assertEquals(-v.score().unassignedPriority(), score.mediumScore());
    assertEquals(-v.score().soft(), score.softScore());
    var clone = new TimefoldAdapter.Cloner().cloneSolution(working);
    clone.assignments.getFirst().vehicle = -1;
    assertNotEquals(working.candidate(), clone.candidate());
  }

  @Test
  void residualRevisionRejectsUnsupportedObservationsAndPreservesFrozenCargo() {
    Problem p = fixture();
    Validation prior = checked(p, "A", "B");
    Replanner replanner = new Replanner();
    Instant boundary = NOW.plusSeconds(15);
    assertThrows(
        IllegalArgumentException.class,
        () -> replanner.replan(p, prior.candidate(), NOW.minusSeconds(1), List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(
                p,
                prior.candidate(),
                NOW,
                List.of(new Replanner.Observation("missing", 0, false, 0, false)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(
                p,
                prior.candidate(),
                NOW,
                List.of(
                    new Replanner.Observation("V", 0, false, 0, false),
                    new Replanner.Observation("V", 0, false, 0, false)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(
                p,
                prior.candidate(),
                NOW,
                List.of(new Replanner.Observation("V", 3, false, 0, false)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(
                p,
                prior.candidate(),
                NOW,
                List.of(new Replanner.Observation("V", 1, false, 0, false)),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(
                p,
                prior.candidate(),
                boundary,
                List.of(new Replanner.Observation("V", 1, true, 1, false)),
                List.of()));
    Problem active =
        replanner.replan(
            p,
            prior.candidate(),
            boundary,
            List.of(new Replanner.Observation("V", 1, false, 1, false)),
            List.of());
    assertEquals(List.of("B"), active.vehicles().getFirst().frozenPrefix());
    assertEquals(Set.of("B"), active.vehicles().getFirst().onboard());
    assertEquals(1000, active.vehicles().getFirst().remainingLoad());
    assertEquals(1, active.completed().size());
    assertEquals("A", active.completed().getFirst().jobId());
    assertEquals(1, active.vehicles().getFirst().currentLocation());
    assertTrue(replanner.repair(active, prior.candidate()).valid());
    assertTrue(new Heuristics().baseline(active).valid());
    Problem failed =
        replanner.replan(
            p,
            prior.candidate(),
            boundary,
            List.of(new Replanner.Observation("V", 1, true, 1, true)),
            List.of(
                new Job(
                    "C", 0, 1000, 5, boundary, boundary.plusSeconds(100), Set.of("normal"), 100)));
    assertEquals(2, failed.version());
    assertTrue(failed.audit().stream().anyMatch(a -> a.contains("RELEASE_FAILED_COMMITMENTS")));
    Validation repaired = replanner.repair(failed, prior.candidate());
    assertTrue(repaired.valid());
    assertEquals("STRANDED_CARGO", repaired.explanations().get("B"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            replanner.replan(p, checked(p, "A", "A").candidate(), boundary, List.of(), List.of()));
    Problem frozen =
        problem(
            p.jobs(),
            List.of(vehicle("V", 0, Set.of(), 0, List.of("A", "B"), false, NOW.plusSeconds(1000))),
            Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Heuristics()
                .baseline(
                    problem(
                        p.jobs(),
                        List.of(
                            vehicle(
                                "V",
                                0,
                                Set.of(),
                                0,
                                List.of("B", "A"),
                                true,
                                NOW.plusSeconds(1000))),
                        Map.of())));
    assertEquals(
        List.of("A", "B"),
        replanner
            .replan(frozen, checked(frozen, "A", "B").candidate(), NOW, List.of(), List.of())
            .vehicles()
            .getFirst()
            .frozenPrefix());
  }

  @Test
  void crossVehicleMovesAndCancellationPreserveImmutableIncumbent() {
    List<Route> routes = List.of(new Route("V", List.of("A")), new Route("W", List.of("B")));
    assertEquals(List.of("B", "A"), Heuristics.relocate(routes, 0, 0, 1, 1).get(1).jobs());
    assertEquals(List.of("B"), Heuristics.swap(routes, 0, 0, 1, 0).getFirst().jobs());
    assertEquals(List.of("A"), routes.getFirst().jobs());
    Problem p = fixture();
    Validation b = checked(p, "A", "B");
    assertSame(b, new Heuristics().improve(p, b, 0, () -> false));
    assertSame(b, new Heuristics().improve(p, b, 1000, () -> true));
    assertSame(
        b,
        new TimefoldAdapter()
            .solve(p, b, new TimefoldAdapter.Settings(1, 100, 10, "test"), () -> true, v -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> new TimefoldAdapter.Settings(1, 0, 10, "test"));
    assertThrows(
        IllegalArgumentException.class, () -> new TimefoldAdapter.Settings(1, 100, 0, "test"));
  }

  @Test
  void cancellationDuringSolverStartupKeepsTerminationWatcherAlive() throws Exception {
    Problem p = fixture();
    Validation baseline = new Heuristics().baseline(p);
    java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean released =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.CountDownLatch atStart = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1),
        earlyCancellationObserved = new java.util.concurrent.CountDownLatch(1),
        continuedCancellationObserved = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var result =
          worker.submit(
              () ->
                  new TimefoldAdapter()
                      .solveControlled(
                          p,
                          baseline,
                          new TimefoldAdapter.Settings(42, 30000, 100000, "barrier-test"),
                          () -> {
                            boolean value = cancelled.get();
                            if (value) {
                              if (released.get()) continuedCancellationObserved.countDown();
                              else earlyCancellationObserved.countDown();
                            }
                            return value;
                          },
                          v -> {},
                          () -> {
                            atStart.countDown();
                            try {
                              release.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            }
                          }));
      assertTrue(atStart.await(20, java.util.concurrent.TimeUnit.SECONDS));
      cancelled.set(true);
      assertTrue(
          earlyCancellationObserved.await(5, java.util.concurrent.TimeUnit.SECONDS),
          "watcher must request termination before solve starts");
      released.set(true);
      release.countDown();
      assertTrue(
          continuedCancellationObserved.await(5, java.util.concurrent.TimeUnit.SECONDS),
          "watcher must continue after an early termination request");
      Validation returned = result.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(returned.valid());
      assertTrue(returned.score().compareTo(baseline.score()) <= 0);
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
    var original = TimefoldAdapter.WorkingPlan.from(p, baseline.candidate());
    original.assignments.getFirst().position = null;
    original.assignments.getFirst().vehicle = null;
    var clone = new TimefoldAdapter.Cloner().cloneSolution(original);
    assertNull(clone.assignments.getFirst().position);
    assertNull(clone.assignments.getFirst().vehicle);
  }

  @Test
  void returnBoundaryAndDirectedMoveExhaustion() {
    Problem p = fixture();
    Problem boundary =
        problem(
            List.of(job("A", 1)),
            List.of(vehicle("V", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(25))),
            Map.of());
    assertTrue(checked(boundary, "A").valid());
    Validation optimum = checked(p, "A", "B");
    assertSame(optimum, new Heuristics().improve(p, optimum, 1000, () -> false));
    Problem moving =
        problem(
            p.jobs(),
            List.of(vehicle("V", 1, Set.of("A"), 1000, List.of(), false, NOW.plusSeconds(1000))),
            Map.of());
    code(checked(moving, "A", "A"), "NEGATIVE_LOAD");
    assertTrue(checked(moving).explanations().get("A").startsWith("ONBOARD_UNSCHEDULED"));
    assertTrue(checked(moving).explanations().get("B").startsWith("DEPOT_CARGO"));
    Problem failed =
        problem(
            p.jobs(),
            List.of(vehicle("V", 1, Set.of("A"), 1000, List.of(), true, NOW.plusSeconds(1000))),
            Map.of());
    assertTrue(new Heuristics().baseline(failed).valid());
    assertEquals(2, new Heuristics().baseline(failed).candidate().unassigned().size());
    Problem skill =
        problem(
            List.of(new Job("A", 1, 1000, 5, NOW, NOW.plusSeconds(900), Set.of("missing"), 1)),
            p.vehicles(),
            Map.of());
    assertEquals(1, new Heuristics().baseline(skill).candidate().unassigned().size());
    Problem capacity =
        problem(
            List.of(new Job("A", 1, 3000, 5, NOW, NOW.plusSeconds(900), Set.of("normal"), 1)),
            p.vehicles(),
            Map.of());
    assertEquals(1, new Heuristics().baseline(capacity).candidate().unassigned().size());
  }

  @Test
  void validatedWaitingRouteCrossesServiceAreaMidnight() {
    Instant start = Instant.parse("2026-10-18T02:00:00Z"),
        window = Instant.parse("2026-10-18T03:30:00Z"),
        end = Instant.parse("2026-10-18T06:00:00Z");
    Problem p =
        new Problem(
            "tenant",
            "night",
            1,
            ZoneId.of("America/Halifax"),
            0,
            start,
            fixture().matrix(),
            List.of(new Job("A", 1, 1000, 5, window, window.plusSeconds(100), Set.of("normal"), 1)),
            List.of(
                new Vehicle(
                    "V",
                    2000,
                    Set.of("normal"),
                    start,
                    end,
                    0,
                    start,
                    Set.of(),
                    0,
                    List.of(),
                    false)),
            List.of(),
            Map.of(),
            fixture().objective(),
            List.of());
    Validation checked = checked(p, "A");
    assertTrue(checked.valid());
    assertEquals(LocalDate.parse("2026-10-17"), start.atZone(p.serviceZone()).toLocalDate());
    assertEquals(
        LocalDate.parse("2026-10-18"),
        checked.timelines().getFirst().returnedAt().atZone(p.serviceZone()).toLocalDate());
    assertEquals(window.plusSeconds(15), checked.timelines().getFirst().returnedAt());
  }

  @Test
  void allVehiclesLoadAndReturnToDeclaredSharedDepotWhichNeedNotBeZero() {
    Problem source = fixture();
    Problem p =
        new Problem(
            source.tenant(),
            "shared-depot",
            1,
            source.serviceZone(),
            2,
            NOW,
            source.matrix(),
            List.of(job("A", 1)),
            List.of(
                vehicle("V", 2, Set.of(), 0, List.of(), false, NOW.plusSeconds(1000)),
                vehicle("W", 0, Set.of(), 0, List.of(), false, NOW.plusSeconds(1000))),
            List.of(),
            Map.of(),
            source.objective(),
            List.of());
    Validation valid =
        new Validator()
            .validate(
                p,
                Model.candidate(
                    p, List.of(new Route("V", List.of("A")), new Route("W", List.of()))));
    assertTrue(valid.valid());
    assertEquals(2310, valid.score().operatingCost());
    assertEquals(NOW.plusSeconds(115), valid.timelines().getFirst().returnedAt());
    assertEquals(NOW.plusSeconds(100), valid.timelines().getLast().returnedAt());
    Validation remote =
        new Validator()
            .validate(
                p,
                Model.candidate(
                    p, List.of(new Route("V", List.of()), new Route("W", List.of("A")))));
    code(remote, "DEPOT_CARGO");
  }

  @Test
  void forgedSolverScoreCannotMakeInvalidCandidateReplaceIncumbent() {
    Problem p = fixture();
    Validation incumbent = checked(p, "A", "B");
    Candidate duplicate = Model.candidate(p, List.of(new Route("V", List.of("A", "A"))));
    Validation forged =
        new Validation(duplicate, new Score(0, 0, 0, 0), List.of(), List.of(), Map.of());
    assertSame(incumbent, Heuristics.retain(p, incumbent, forged));
  }

  @Test
  void structuralViolationsCannotMaskEachOtherAndReasonsDoNotOverclaim() {
    Problem p = fixture();
    Candidate unknown =
        new Candidate(
            p.canonicalHash(),
            List.of(new Route("unknown", List.of()), new Route("V", List.of())),
            Map.of("A", "UNASSIGNED", "B", "UNASSIGNED"));
    Validation badVehicle = new Validator().validate(p, unknown);
    code(badVehicle, "UNKNOWN_VEHICLE");
    assertEquals(1, badVehicle.violations().size());
    Candidate overlap =
        new Candidate(
            p.canonicalHash(),
            List.of(new Route("V", List.of("A", "B"))),
            Map.of("A", "UNASSIGNED"));
    Validation badPartition = new Validator().validate(p, overlap);
    code(badPartition, "PARTITION");
    assertEquals(1, badPartition.violations().size());
    Vehicle v =
        new Vehicle(
            "V",
            1000,
            Set.of("normal"),
            NOW,
            NOW.plusSeconds(1000),
            0,
            NOW,
            Set.of(),
            0,
            List.of(),
            false);
    Problem exact = problem(List.of(job("A", 1)), List.of(v), Map.of());
    String reason = checked(exact).explanations().get("A");
    assertTrue(reason.startsWith("SEARCH_UNASSIGNED"));
    assertTrue(reason.contains("not proof"));
    assertTrue(checked(exact, "A").valid());
    Problem history =
        problem(
            p.jobs(), p.vehicles(), Map.of("A", new Previous("V", 0), "B", new Previous("V", 1)));
    assertEquals(0, checked(history, "A", "B").score().disruption());
  }
}
