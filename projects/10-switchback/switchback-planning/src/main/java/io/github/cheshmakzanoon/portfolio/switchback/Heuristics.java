package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Original deterministic insertion and directed full-route relocate/swap/2-opt search. */
public final class Heuristics {
  private final Validator validator = new Validator();
  private final boolean prune;

  public Heuristics() {
    this(true);
  }

  /**
   * @param exactCostPruning skip candidate moves whose exactly computed soft-score change is not
   *     negative before they are validated or counted against the evaluation limit. A move that
   *     reassigns no job cannot change the hard or unassigned components of the score, and the soft
   *     component is arithmetic over the moved routes, so a skipped move could never have been
   *     accepted: with an unlimited budget the result is identical, and with a limit the budget is
   *     spent only on moves that can improve. {@code false} reproduces the original accounting,
   *     which validates every candidate.
   */
  public Heuristics(boolean exactCostPruning) {
    this.prune = exactCostPruning;
  }

  public Validation baseline(Problem p) {
    return baseline(p, () -> false);
  }

  public Validation baseline(Problem p, BooleanSupplier stop) {
    List<Route> routes =
        p.vehicles().stream()
            .sorted(Comparator.comparing(Vehicle::id))
            .map(v -> new Route(v.id(), v.frozenPrefix()))
            .toList();
    Validation best = validator.validate(p, candidate(p, routes));
    if (!best.valid())
      throw new IllegalArgumentException(
          "BLOCKED_RESIDUAL: frozen/current state has hard conflicts: " + best.violations());
    Map<String, Job> jobMap = p.jobMap();
    List<Job> jobs =
        p.jobs().stream()
            .sorted(Comparator.comparingInt(Job::priority).reversed().thenComparing(Job::id))
            .toList();
    for (Job job : jobs) {
      if (stop.getAsBoolean()) break;
      if (!best.candidate().unassigned().containsKey(job.id())) continue;
      List<Insertion> insertions = new ArrayList<>();
      for (int ri = 0; ri < routes.size(); ri++) {
        Vehicle vehicle = vehicle(p, routes.get(ri).vehicleId());
        if (vehicle.failed()
            || !vehicle.skills().containsAll(job.skills())
            || job.grams() > vehicle.capacityGrams()
            || vehicle.currentLocation() != p.depot() && !vehicle.onboard().contains(job.id()))
          continue;
        List<String> order = routes.get(ri).jobs();
        for (int pos = vehicle.frozenPrefix().size(); pos <= order.size(); pos++) {
          int from =
              pos == 0 ? vehicle.currentLocation() : jobMap.get(order.get(pos - 1)).location();
          int to = pos == order.size() ? p.depot() : jobMap.get(order.get(pos)).location();
          long delta =
              (long) p.matrix().arc(from, job.location()).seconds()
                  + p.matrix().arc(job.location(), to).seconds()
                  - p.matrix().arc(from, to).seconds();
          insertions.add(new Insertion(ri, pos, delta));
        }
      }
      insertions.sort(
          Comparator.comparingLong(Insertion::delta)
              .thenComparingInt(Insertion::route)
              .thenComparingInt(Insertion::position));
      // Nearest feasible insertion: try every position in increasing directed insertion duration.
      for (Insertion insertion : insertions) {
        if (stop.getAsBoolean()) break;
        List<Route> next = copy(routes);
        List<String> order = new ArrayList<>(routes.get(insertion.route).jobs());
        order.add(insertion.position, job.id());
        next.set(insertion.route, new Route(routes.get(insertion.route).vehicleId(), order));
        Validation value = validator.validate(p, candidate(p, next));
        if (value.valid() && value.score().compareTo(best.score()) < 0) {
          best = value;
          routes = next;
          break;
        }
      }
    }
    return best;
  }

  private record Insertion(int route, int position, long delta) {}

  /**
   * Local search in two stages. The original neighbourhoods (relocate, swap, intra-route reversal)
   * run to a local optimum exactly as {@link #improveBasic} does; only when none of them improves
   * and budget remains does it try the wider ones (Or-opt segment moves and cross-route tail
   * exchange), returning to the original stage after any gain. With the same evaluation limit the
   * result is therefore never worse than {@link #improveBasic}'s, and it can leave local optima
   * that the original moves cannot.
   */
  public Validation improve(
      Problem p, Validation initial, long evaluationLimit, BooleanSupplier stop) {
    requireIncumbent(p, initial);
    Search search = new Search(evaluationLimit, stop);
    Validation best = initial;
    while (true) {
      best = descend(p, best, search);
      if (search.exhausted) return best;
      Validation widened = widen(p, best, search);
      if (widened == null) return best;
      best = widened;
    }
  }

  /**
   * Only the original neighbourhoods; kept as the reference the wider search is measured against.
   */
  public Validation improveBasic(
      Problem p, Validation initial, long evaluationLimit, BooleanSupplier stop) {
    requireIncumbent(p, initial);
    return descend(p, initial, new Search(evaluationLimit, stop));
  }

  /** One shared evaluation budget; the first refusal marks it exhausted for every stage. */
  private static final class Search {
    private final long limit;
    private final BooleanSupplier stop;
    private long evaluated;
    private boolean exhausted;

    Search(long limit, BooleanSupplier stop) {
      this.limit = limit;
      this.stop = stop;
    }

    boolean next() {
      if (evaluated++ >= limit || stop.getAsBoolean()) {
        exhausted = true;
        return false;
      }
      return true;
    }
  }

  private Validation descend(Problem p, Validation initial, Search search) {
    Validation best = initial;
    Estimator estimator = new Estimator(p);
    boolean changed = true;
    search:
    while (changed) {
      changed = false;
      List<Route> routes = best.candidate().routes();
      long[] base = estimator.soft(routes);
      for (int a = 0; a < routes.size(); a++)
        for (int i = 0; i < routes.get(a).jobs().size(); i++) {
          for (int b = 0; b < routes.size(); b++)
            for (int j = 0; j <= routes.get(b).jobs().size(); j++) {
              Moved moved = relocateJobs(routes, a, i, b, j);
              if (hopeless(estimator, routes, base, moved, a, b)) continue;
              if (!search.next()) break search;
              Validation value = validator.validate(p, candidate(p, assemble(routes, moved, a, b)));
              if (value.valid() && value.score().compareTo(best.score()) < 0) {
                best = value;
                changed = true;
                continue search;
              }
            }
          for (int b = a; b < routes.size(); b++)
            for (int j = (b == a ? i + 1 : 0); j < routes.get(b).jobs().size(); j++) {
              Moved moved = swapJobs(routes, a, i, b, j);
              if (hopeless(estimator, routes, base, moved, a, b)) continue;
              if (!search.next()) break search;
              Validation value = validator.validate(p, candidate(p, assemble(routes, moved, a, b)));
              if (value.valid() && value.score().compareTo(best.score()) < 0) {
                best = value;
                changed = true;
                continue search;
              }
            }
          for (int j = i + 1; j < routes.get(a).jobs().size(); j++) {
            Moved moved = reverseJobs(routes, a, i, j);
            if (hopeless(estimator, routes, base, moved, a, a)) continue;
            if (!search.next()) break search;
            Validation value = validator.validate(p, candidate(p, assemble(routes, moved, a, a)));
            if (value.valid() && value.score().compareTo(best.score()) < 0) {
              best = value;
              changed = true;
              continue search;
            }
          }
        }
    }
    return best;
  }

  /**
   * First improving Or-opt or tail-exchange move, or null when none improves or the budget ended.
   * Moves that the validator would reject for structural reasons (a failed vehicle, missing skills,
   * cargo aboard another vehicle, depot cargo for a moving vehicle, the frozen prefix) are skipped
   * without spending budget; capacity, windows and shifts are left to the validator.
   */
  private Validation widen(Problem p, Validation best, Search search) {
    List<Route> routes = best.candidate().routes();
    Map<String, Job> jobs = p.jobMap();
    Map<String, Vehicle> vehicles = new HashMap<>();
    p.vehicles().forEach(v -> vehicles.put(v.id(), v));
    Map<String, String> cargo = new HashMap<>();
    p.vehicles().forEach(v -> v.onboard().forEach(j -> cargo.put(j, v.id())));
    int[] frozen = new int[routes.size()];
    for (int r = 0; r < routes.size(); r++)
      frozen[r] = vehicles.get(routes.get(r).vehicleId()).frozenPrefix().size();
    Estimator estimator = new Estimator(p);
    long[] base = estimator.soft(routes);

    for (int a = 0; a < routes.size(); a++)
      for (int length = 2; length <= 3; length++)
        for (int i = frozen[a]; i + length <= routes.get(a).jobs().size(); i++) {
          List<String> segment = routes.get(a).jobs().subList(i, i + length);
          for (int b = 0; b < routes.size(); b++) {
            if (a != b && !serves(p, vehicles.get(routes.get(b).vehicleId()), segment, jobs, cargo))
              continue;
            int remaining = routes.get(b).jobs().size() - (a == b ? length : 0);
            for (boolean reversed : new boolean[] {false, true})
              for (int j = frozen[b]; j <= remaining; j++) {
                if (a == b && j == i && !reversed) continue;
                Moved moved = segmentJobs(routes, a, i, length, b, j, reversed);
                if (hopeless(estimator, routes, base, moved, a, b)) continue;
                if (!search.next()) return null;
                Validation value =
                    validator.validate(p, candidate(p, assemble(routes, moved, a, b)));
                if (value.valid() && value.score().compareTo(best.score()) < 0) return value;
              }
          }
        }

    for (int a = 0; a < routes.size(); a++)
      for (int b = a + 1; b < routes.size(); b++) {
        List<String> left = routes.get(a).jobs(), right = routes.get(b).jobs();
        for (int i = frozen[a]; i <= left.size(); i++)
          for (int j = frozen[b]; j <= right.size(); j++) {
            if (i == left.size() && j == right.size()) continue;
            if (!serves(
                    p,
                    vehicles.get(routes.get(a).vehicleId()),
                    right.subList(j, right.size()),
                    jobs,
                    cargo)
                || !serves(
                    p,
                    vehicles.get(routes.get(b).vehicleId()),
                    left.subList(i, left.size()),
                    jobs,
                    cargo)) continue;
            Moved moved = tailJobs(routes, a, i, b, j);
            if (hopeless(estimator, routes, base, moved, a, b)) continue;
            if (!search.next()) return null;
            Validation value = validator.validate(p, candidate(p, assemble(routes, moved, a, b)));
            if (value.valid() && value.score().compareTo(best.score()) < 0) return value;
          }
      }
    return null;
  }

  /** True when pruning is on and the moved routes' exact soft score does not go down. */
  private boolean hopeless(
      Estimator estimator, List<Route> routes, long[] base, Moved moved, int a, int b) {
    if (!prune) return false;
    long delta = estimator.soft(routes.get(a).vehicleId(), moved.first()) - base[a];
    if (b != a) delta += estimator.soft(routes.get(b).vehicleId(), moved.second()) - base[b];
    return delta >= 0;
  }

  /**
   * The soft score (operating cost plus disruption) of a route, computed with the same arithmetic
   * as {@link Validator} but without timelines, windows or violations: every directed arc from the
   * vehicle's position through its stops and back to the depot (not for a failed vehicle), weighted
   * by the objective, plus the disruption weight for each stop whose previous vehicle or index
   * differs. Exact for any route of known jobs; whether the route is feasible is the validator's.
   * It works on plain job-id lists so that rejecting a move allocates no {@link Route}.
   */
  static final class Estimator {
    private final Problem p;
    private final Map<String, Integer> location = new HashMap<>();
    private final Map<String, Vehicle> vehicles = new HashMap<>();
    private final long[][] arc;

    Estimator(Problem p) {
      this.p = p;
      p.jobs().forEach(j -> location.put(j.id(), j.location()));
      p.vehicles().forEach(v -> vehicles.put(v.id(), v));
      int n = p.matrix().entries().size();
      arc = new long[n][n];
      for (int from = 0; from < n; from++)
        for (int to = 0; to < n; to++) {
          Arc a = p.matrix().arc(from, to);
          arc[from][to] =
              Math.addExact(
                  Math.multiplyExact((long) a.meters(), p.objective().metersWeight()),
                  Math.multiplyExact((long) a.seconds(), p.objective().secondsWeight()));
        }
    }

    long[] soft(List<Route> routes) {
      long[] result = new long[routes.size()];
      for (int r = 0; r < result.length; r++)
        result[r] = soft(routes.get(r).vehicleId(), routes.get(r).jobs());
      return result;
    }

    long soft(String vehicleId, List<String> jobs) {
      Vehicle v = vehicles.get(vehicleId);
      int from = v.currentLocation();
      long total = 0;
      int index = 0;
      for (String id : jobs) {
        int to = location.get(id);
        total = Math.addExact(total, arc[from][to]);
        from = to;
        Previous old = p.previous().get(id);
        if (old != null && (!old.vehicleId().equals(v.id()) || old.index() != index))
          total = Math.addExact(total, p.objective().disruptionWeight());
        index++;
      }
      if (!v.failed()) total = Math.addExact(total, arc[from][p.depot()]);
      return total;
    }
  }

  /** Whether {@code vehicle} could structurally take these jobs; never a proof of feasibility. */
  private static boolean serves(
      Problem p,
      Vehicle vehicle,
      List<String> ids,
      Map<String, Job> jobs,
      Map<String, String> cargo) {
    if (vehicle.failed()) return false;
    for (String id : ids) {
      Job job = jobs.get(id);
      if (job == null || !vehicle.skills().containsAll(job.skills())) return false;
      String holder = cargo.get(id);
      if (holder != null ? !holder.equals(vehicle.id()) : vehicle.currentLocation() != p.depot())
        return false;
    }
    return true;
  }

  /** The new stop order of route a, and of route b when it is a different route. */
  record Moved(List<String> first, List<String> second) {}

  private static List<Route> assemble(List<Route> routes, Moved moved, int a, int b) {
    List<Route> next = copy(routes);
    next.set(a, new Route(routes.get(a).vehicleId(), moved.first()));
    if (b != a) next.set(b, new Route(routes.get(b).vehicleId(), moved.second()));
    return next;
  }

  static Moved relocateJobs(List<Route> routes, int a, int i, int b, int j) {
    List<String> from = new ArrayList<>(routes.get(a).jobs());
    String job = from.remove(i);
    if (a == b) {
      from.add(j > i ? j - 1 : j, job);
      return new Moved(from, null);
    }
    List<String> to = new ArrayList<>(routes.get(b).jobs());
    to.add(j, job);
    return new Moved(from, to);
  }

  static Moved swapJobs(List<Route> routes, int a, int i, int b, int j) {
    List<String> from = new ArrayList<>(routes.get(a).jobs());
    List<String> to = a == b ? from : new ArrayList<>(routes.get(b).jobs());
    String old = from.get(i);
    from.set(i, to.get(j));
    to.set(j, old);
    return new Moved(from, a == b ? null : to);
  }

  static Moved reverseJobs(List<Route> routes, int a, int i, int j) {
    List<String> order = new ArrayList<>(routes.get(a).jobs());
    Collections.reverse(order.subList(i, j + 1));
    return new Moved(order, null);
  }

  static Moved segmentJobs(
      List<Route> routes, int a, int i, int length, int b, int j, boolean reversed) {
    List<String> from = new ArrayList<>(routes.get(a).jobs());
    List<String> segment = new ArrayList<>(from.subList(i, i + length));
    from.subList(i, i + length).clear();
    if (reversed) Collections.reverse(segment);
    if (a == b) {
      from.addAll(j, segment);
      return new Moved(from, null);
    }
    List<String> to = new ArrayList<>(routes.get(b).jobs());
    to.addAll(j, segment);
    return new Moved(from, to);
  }

  static Moved tailJobs(List<Route> routes, int a, int i, int b, int j) {
    List<String> left = routes.get(a).jobs(), right = routes.get(b).jobs();
    List<String> newLeft = new ArrayList<>(left.subList(0, i));
    newLeft.addAll(right.subList(j, right.size()));
    List<String> newRight = new ArrayList<>(right.subList(0, j));
    newRight.addAll(left.subList(i, left.size()));
    return new Moved(newLeft, newRight);
  }

  public static List<Route> relocate(List<Route> routes, int a, int i, int b, int j) {
    return assemble(routes, relocateJobs(routes, a, i, b, j), a, b);
  }

  public static List<Route> swap(List<Route> routes, int a, int i, int b, int j) {
    return assemble(routes, swapJobs(routes, a, i, b, j), a, b);
  }

  public static List<Route> reverse(List<Route> routes, int a, int i, int j) {
    return assemble(routes, reverseJobs(routes, a, i, j), a, a);
  }

  /** Move {@code length} consecutive stops of route a (starting at i) into route b at j. */
  public static List<Route> segmentMove(
      List<Route> routes, int a, int i, int length, int b, int j, boolean reversed) {
    return assemble(routes, segmentJobs(routes, a, i, length, b, j, reversed), a, b);
  }

  /**
   * Swap the tails of two routes: a keeps its first i stops, b its first j, and they trade the
   * rest.
   */
  public static List<Route> tailExchange(List<Route> routes, int a, int i, int b, int j) {
    return assemble(routes, tailJobs(routes, a, i, b, j), a, b);
  }

  public static Validation retain(Problem p, Validation incumbent, Validation challenger) {
    requireIncumbent(p, incumbent);
    if (!p.canonicalHash().equals(challenger.candidate().inputHash()))
      throw new IllegalArgumentException("cross-version comparison forbidden");
    Validation checked = new Validator().validate(p, challenger.candidate());
    return checked.valid() && checked.score().compareTo(incumbent.score()) < 0
        ? checked
        : incumbent;
  }

  private static void requireIncumbent(Problem p, Validation value) {
    Validation checked = new Validator().validate(p, value.candidate());
    if (!checked.valid()
        || !p.canonicalHash().equals(value.candidate().inputHash())
        || !checked.score().equals(value.score()))
      throw new IllegalArgumentException("incumbent must be independently valid for same input");
  }

  private static Vehicle vehicle(Problem p, String id) {
    return p.vehicles().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
  }

  private static List<Route> copy(List<Route> routes) {
    return new ArrayList<>(routes);
  }
}
