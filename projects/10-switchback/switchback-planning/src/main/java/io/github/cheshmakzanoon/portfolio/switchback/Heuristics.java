package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Original deterministic insertion and directed full-route relocate/swap/2-opt search. */
public final class Heuristics {
  private final Validator validator = new Validator();

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

  public Validation improve(
      Problem p, Validation initial, long evaluationLimit, BooleanSupplier stop) {
    requireIncumbent(p, initial);
    Validation best = initial;
    long evaluated = 0;
    boolean changed = true;
    search:
    while (changed) {
      changed = false;
      List<Route> routes = best.candidate().routes();
      for (int a = 0; a < routes.size(); a++)
        for (int i = 0; i < routes.get(a).jobs().size(); i++) {
          for (int b = 0; b < routes.size(); b++)
            for (int j = 0; j <= routes.get(b).jobs().size(); j++) {
              if (evaluated++ >= evaluationLimit || stop.getAsBoolean()) break search;
              List<Route> next = relocate(routes, a, i, b, j);
              Validation value = validator.validate(p, candidate(p, next));
              if (value.valid() && value.score().compareTo(best.score()) < 0) {
                best = value;
                changed = true;
                continue search;
              }
            }
          for (int b = a; b < routes.size(); b++)
            for (int j = (b == a ? i + 1 : 0); j < routes.get(b).jobs().size(); j++) {
              if (evaluated++ >= evaluationLimit || stop.getAsBoolean()) break search;
              List<Route> next = swap(routes, a, i, b, j);
              Validation value = validator.validate(p, candidate(p, next));
              if (value.valid() && value.score().compareTo(best.score()) < 0) {
                best = value;
                changed = true;
                continue search;
              }
            }
          for (int j = i + 1; j < routes.get(a).jobs().size(); j++) {
            if (evaluated++ >= evaluationLimit || stop.getAsBoolean()) break search;
            List<Route> next = reverse(routes, a, i, j);
            Validation value = validator.validate(p, candidate(p, next));
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

  public static List<Route> relocate(List<Route> routes, int a, int i, int b, int j) {
    List<Route> next = copy(routes);
    List<String> from = new ArrayList<>(routes.get(a).jobs());
    String job = from.remove(i);
    next.set(a, new Route(routes.get(a).vehicleId(), from));
    List<String> to = new ArrayList<>(next.get(b).jobs());
    to.add(a == b && j > i ? j - 1 : j, job);
    next.set(b, new Route(routes.get(b).vehicleId(), to));
    return next;
  }

  public static List<Route> swap(List<Route> routes, int a, int i, int b, int j) {
    List<Route> next = copy(routes);
    List<String> from = new ArrayList<>(routes.get(a).jobs());
    List<String> to = a == b ? from : new ArrayList<>(routes.get(b).jobs());
    String old = from.get(i);
    from.set(i, to.get(j));
    to.set(j, old);
    next.set(a, new Route(routes.get(a).vehicleId(), from));
    next.set(b, new Route(routes.get(b).vehicleId(), to));
    return next;
  }

  public static List<Route> reverse(List<Route> routes, int a, int i, int j) {
    List<Route> next = copy(routes);
    List<String> order = new ArrayList<>(routes.get(a).jobs());
    Collections.reverse(order.subList(i, j + 1));
    next.set(a, new Route(routes.get(a).vehicleId(), order));
    return next;
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
