package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.switchback.Heuristics.Estimator;
import io.github.cheshmakzanoon.portfolio.switchback.Heuristics.Moved;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The local search's exact cost pruning and wider neighbourhoods, checked against the independent
 * validator and the exhaustive oracle rather than against themselves.
 */
class LocalSearchTest {
  private static final Validator VALIDATOR = new Validator();

  private static List<Route> routes(String... orders) {
    List<Route> routes = new ArrayList<>();
    for (int v = 0; v < orders.length; v++)
      routes.add(
          new Route("V" + v, orders[v].isEmpty() ? List.of() : List.of(orders[v].split(","))));
    return routes;
  }

  private static List<List<String>> orders(List<Route> routes) {
    return routes.stream().map(Route::jobs).toList();
  }

  /**
   * The same instance with every job previously assigned to the next vehicle, so moves cost
   * disruption.
   */
  private static Problem withPrevious(Problem p) {
    Validation base = new Heuristics().baseline(p);
    Map<String, Previous> previous = new HashMap<>();
    List<Route> routes = base.candidate().routes();
    for (int r = 0; r < routes.size(); r++) {
      String vehicle = routes.get((r + 1) % routes.size()).vehicleId();
      for (int i = 0; i < routes.get(r).jobs().size(); i++)
        previous.put(routes.get(r).jobs().get(i), new Previous(vehicle, i));
    }
    return new Problem(
        p.tenant(),
        p.id(),
        p.version(),
        p.serviceZone(),
        p.depot(),
        p.planningInstant(),
        p.matrix(),
        p.jobs(),
        p.vehicles(),
        p.completed(),
        previous,
        p.objective(),
        p.audit());
  }

  // ---- the move constructors ----------------------------------------------------------

  @Test
  void segmentMovesCoverSameRouteCrossRouteAndReversedPlacements() {
    List<Route> routes = routes("A,B,C,D,E", "X,Y");
    assertEquals(
        List.of(List.of("A", "D", "B", "C", "E"), List.of("X", "Y")),
        orders(Heuristics.segmentMove(routes, 0, 1, 2, 0, 2, false)));
    assertEquals(
        List.of(List.of("A", "D", "C", "B", "E"), List.of("X", "Y")),
        orders(Heuristics.segmentMove(routes, 0, 1, 2, 0, 2, true)));
    assertEquals(
        List.of(List.of("A", "D", "E"), List.of("X", "B", "C", "Y")),
        orders(Heuristics.segmentMove(routes, 0, 1, 2, 1, 1, false)));
    assertEquals(
        List.of(List.of("A", "D", "E"), List.of("X", "Y", "C", "B")),
        orders(Heuristics.segmentMove(routes, 0, 1, 2, 1, 2, true)));
    assertEquals(
        List.of(List.of("C", "D", "E", "A", "B"), List.of("X", "Y")),
        orders(Heuristics.segmentMove(routes, 0, 0, 2, 0, 3, false)));
    assertEquals(orders(routes("A,B,C,D,E", "X,Y")), orders(routes), "inputs are never modified");
  }

  @Test
  void tailExchangeTradesTheRestOfEachRoute() {
    List<Route> routes = routes("A,B,C", "X,Y");
    assertEquals(
        List.of(List.of("A", "Y"), List.of("X", "B", "C")),
        orders(Heuristics.tailExchange(routes, 0, 1, 1, 1)));
    assertEquals(
        List.of(List.of("X", "Y"), List.of("A", "B", "C")),
        orders(Heuristics.tailExchange(routes, 0, 0, 1, 0)));
    assertEquals(
        List.of(List.of("A", "B", "C", "X", "Y"), List.of()),
        orders(Heuristics.tailExchange(routes, 0, 3, 1, 0)));
    assertEquals(
        List.of(List.of("A", "B", "C"), List.of("X", "Y")),
        orders(Heuristics.tailExchange(routes, 0, 3, 1, 2)),
        "exchanging two empty tails changes nothing");
  }

  // ---- the estimator -------------------------------------------------------------------

  @Test
  void theEstimatorMatchesTheValidatorsSoftScoreChangeForEveryMoveTypeWithAndWithoutDisruption() {
    int checked = 0;
    for (boolean disruption : new boolean[] {false, true})
      for (long seed = 1; seed <= 6; seed++) {
        Problem p = Fixtures.problem(14, 3, seed, "easy");
        if (disruption) p = withPrevious(p);
        Validation base = new Heuristics().baseline(p);
        List<Route> routes = base.candidate().routes();
        Estimator estimator = new Estimator(p);
        long[] before = estimator.soft(routes);
        for (int a = 0; a < routes.size(); a++)
          for (int i = 0; i < routes.get(a).jobs().size(); i++) {
            for (int b = 0; b < routes.size(); b++)
              for (int j = 0; j <= routes.get(b).jobs().size(); j++) {
                checked +=
                    compare(
                        p,
                        base,
                        estimator,
                        before,
                        routes,
                        Heuristics.relocateJobs(routes, a, i, b, j),
                        a,
                        b);
              }
            for (int b = a; b < routes.size(); b++)
              for (int j = (b == a ? i + 1 : 0); j < routes.get(b).jobs().size(); j++)
                checked +=
                    compare(
                        p,
                        base,
                        estimator,
                        before,
                        routes,
                        Heuristics.swapJobs(routes, a, i, b, j),
                        a,
                        b);
            for (int j = i + 1; j < routes.get(a).jobs().size(); j++)
              checked +=
                  compare(
                      p,
                      base,
                      estimator,
                      before,
                      routes,
                      Heuristics.reverseJobs(routes, a, i, j),
                      a,
                      a);
            for (int length = 2; length <= 3 && i + length <= routes.get(a).jobs().size(); length++)
              for (int b = 0; b < routes.size(); b++)
                for (int j = 0; j <= routes.get(b).jobs().size() - (a == b ? length : 0); j++)
                  checked +=
                      compare(
                          p,
                          base,
                          estimator,
                          before,
                          routes,
                          Heuristics.segmentJobs(routes, a, i, length, b, j, j % 2 == 0),
                          a,
                          b);
          }
        for (int a = 0; a < routes.size(); a++)
          for (int b = a + 1; b < routes.size(); b++)
            for (int i = 0; i <= routes.get(a).jobs().size(); i++)
              for (int j = 0; j <= routes.get(b).jobs().size(); j++)
                checked +=
                    compare(
                        p,
                        base,
                        estimator,
                        before,
                        routes,
                        Heuristics.tailJobs(routes, a, i, b, j),
                        a,
                        b);
      }
    assertTrue(checked > 5000, "moves compared: " + checked);
  }

  private static int compare(
      Problem p,
      Validation base,
      Estimator estimator,
      long[] before,
      List<Route> routes,
      Moved moved,
      int a,
      int b) {
    long estimated = estimator.soft(routes.get(a).vehicleId(), moved.first()) - before[a];
    List<Route> next = new ArrayList<>(routes);
    next.set(a, new Route(routes.get(a).vehicleId(), moved.first()));
    if (b != a) {
      estimated += estimator.soft(routes.get(b).vehicleId(), moved.second()) - before[b];
      next.set(b, new Route(routes.get(b).vehicleId(), moved.second()));
    }
    // The validator prices a candidate whether or not it is feasible, so the comparison needs no
    // filter.
    long actual =
        VALIDATOR.validate(p, Model.candidate(p, next)).score().soft() - base.score().soft();
    assertEquals(actual, estimated, orders(next).toString());
    return 1;
  }

  // ---- pruning loses nothing -----------------------------------------------------------

  @Test
  void withAnUnlimitedBudgetPruningReturnsExactlyTheOriginalSearchesRoutes() {
    int compared = 0, improved = 0;
    for (String difficulty : List.of("easy", "tight", "overconstrained"))
      for (long seed = 1; seed <= 5; seed++)
        for (boolean disruption : new boolean[] {false, true}) {
          Problem p = Fixtures.problem(16, 3, seed, difficulty);
          if (disruption) p = withPrevious(p);
          Validation base = new Heuristics().baseline(p);
          Validation original =
              new Heuristics(false).improveBasic(p, base, 10_000_000, () -> false);
          Validation pruned = new Heuristics(true).improveBasic(p, base, 10_000_000, () -> false);
          assertEquals(
              original.candidate().routes(),
              pruned.candidate().routes(),
              difficulty + " " + seed + " " + disruption);
          assertEquals(original.score(), pruned.score());
          compared++;
          if (pruned.score().compareTo(base.score()) < 0) improved++;
        }
    assertEquals(30, compared);
    assertTrue(improved >= 20, "the comparison must exercise real improvements: " + improved);
  }

  @Test
  void underTheProductionBudgetPruningSpendsTheLimitOnMovesThatCanImprove() {
    double original = 0, pruned = 0;
    for (long seed = 1; seed <= 6; seed++) {
      Problem p = Fixtures.problem(40, 4, seed, "tight");
      Validation base = new Heuristics().baseline(p);
      long production = Math.min(2000, Math.max(100, p.jobs().size() * 10L));
      Validation a = new Heuristics(false).improveBasic(p, base, production, () -> false);
      Validation b = new Heuristics(true).improveBasic(p, base, production, () -> false);
      assertTrue(b.score().compareTo(a.score()) <= 0, "never worse, seed " + seed);
      original += 1 - (double) a.score().operatingCost() / base.score().operatingCost();
      pruned += 1 - (double) b.score().operatingCost() / base.score().operatingCost();
    }
    // Measured: about 1.6% against about 12.6% of cost removed. Assert a wide margin, not the
    // figure.
    assertTrue(original / 6 < 0.03, "original " + original / 6);
    assertTrue(pruned / 6 > 0.08, "pruned " + pruned / 6);
    assertTrue(pruned > 4 * original);
  }

  // ---- wider neighbourhoods ------------------------------------------------------------

  @Test
  void theWiderSearchIsNeverWorseAndSometimesStrictlyBetterThanTheOriginalMoves() {
    int better = 0;
    for (long seed = 1; seed <= 12; seed++) {
      Problem p = Fixtures.problem(20, 3, seed, "tight");
      Validation base = new Heuristics().baseline(p);
      Validation basic = new Heuristics().improveBasic(p, base, 1_000_000, () -> false);
      Validation wide = new Heuristics().improve(p, base, 1_000_000, () -> false);
      assertTrue(wide.valid(), "seed " + seed);
      assertTrue(VALIDATOR.validate(p, wide.candidate()).valid());
      assertTrue(wide.score().compareTo(basic.score()) <= 0, "seed " + seed);
      if (wide.score().compareTo(basic.score()) < 0) better++;
    }
    assertTrue(better >= 4, "strictly better on " + better + " of 12");
  }

  @Test
  void allStagesKeepFrozenPrefixesCargoAndFailedVehiclesIntact() {
    Problem p = Fixtures.demo();
    Validation base = new Heuristics().baseline(p);
    Validation improved = new Heuristics().improve(p, base, 5_000, () -> false);
    assertTrue(improved.valid());
    for (Route r : improved.candidate().routes()) {
      Vehicle v =
          p.vehicles().stream().filter(x -> x.id().equals(r.vehicleId())).findFirst().orElseThrow();
      assertTrue(
          r.jobs()
              .subList(0, Math.min(v.frozenPrefix().size(), r.jobs().size()))
              .equals(v.frozenPrefix()));
      assertTrue(v.skills().containsAll(skills(p, r.jobs())), "skills respected on " + v.id());
    }
    assertEquals(
        orders(base.candidate().routes()).stream().flatMap(List::stream).sorted().toList(),
        orders(improved.candidate().routes()).stream().flatMap(List::stream).sorted().toList(),
        "improvement reassigns stops but never drops or adds one");
    assertTrue(improved.score().compareTo(base.score()) <= 0);
  }

  private static Set<String> skills(Problem p, List<String> jobs) {
    Set<String> skills = new HashSet<>();
    Map<String, Job> byId = p.jobMap();
    jobs.forEach(j -> skills.addAll(byId.get(j).skills()));
    return skills;
  }

  @Test
  void limitsAndCancellationStillReturnTheSameIncumbent() {
    Problem p = Fixtures.problem(12, 3, 3, "tight");
    Validation base = new Heuristics().baseline(p);
    assertSame(base, new Heuristics().improve(p, base, 0, () -> false));
    assertSame(base, new Heuristics().improve(p, base, 1_000_000, () -> true));
    assertSame(base, new Heuristics(false).improve(p, base, 0, () -> false));
    assertSame(base, new Heuristics().improveBasic(p, base, 0, () -> false));
    Validation small = new Heuristics().improve(p, base, 25, () -> false);
    assertTrue(small.score().compareTo(base.score()) <= 0);
    assertTrue(small.valid());
  }

  @Test
  void aSmallBudgetNeverProducesAWorseResultThanTheOriginalAccountingGivesTheSameBudget() {
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = Fixtures.problem(24, 3, seed, "tight");
      Validation base = new Heuristics().baseline(p);
      for (long limit : new long[] {1, 10, 100, 400}) {
        Validation original = new Heuristics(false).improve(p, base, limit, () -> false);
        Validation pruned = new Heuristics(true).improve(p, base, limit, () -> false);
        assertTrue(
            pruned.score().compareTo(original.score()) <= 0, "seed " + seed + " limit " + limit);
      }
    }
  }

  // ---- against the exhaustive oracle -----------------------------------------------------

  @Test
  void
      theHeuristicNeverBeatsTheExactOptimumAndTheWiderSearchReachesItAtLeastAsOftenAsTheOriginal() {
    int basicOptimal = 0, wideOptimal = 0, instances = 0;
    for (long seed = 1; seed <= 40; seed++) {
      Problem p = Fixtures.problem(7, 2, seed, "tight");
      ExactOracle.Result exact = new ExactOracle().solve(p, 20_000_000);
      if (!exact.complete() || exact.optimum() == null) continue;
      Validation base = new Heuristics().baseline(p);
      Validation basic = new Heuristics().improveBasic(p, base, 100_000, () -> false);
      Validation wide = new Heuristics().improve(p, base, 100_000, () -> false);
      assertTrue(basic.score().compareTo(exact.optimum().score()) >= 0, "seed " + seed);
      assertTrue(wide.score().compareTo(exact.optimum().score()) >= 0, "seed " + seed);
      assertTrue(wide.score().compareTo(basic.score()) <= 0, "seed " + seed);
      instances++;
      if (basic.score().compareTo(exact.optimum().score()) == 0) basicOptimal++;
      if (wide.score().compareTo(exact.optimum().score()) == 0) wideOptimal++;
    }
    assertEquals(40, instances);
    assertTrue(wideOptimal >= basicOptimal, wideOptimal + " vs " + basicOptimal);
    assertTrue(wideOptimal >= 36, "optimal on " + wideOptimal + " of 40");
  }
}
