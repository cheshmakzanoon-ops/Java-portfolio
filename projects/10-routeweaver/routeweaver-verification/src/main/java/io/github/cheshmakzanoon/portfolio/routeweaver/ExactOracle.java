package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import java.util.*;

/** Exhausts all unassigned/vehicle assignments and route permutations, including empty routes. */
public final class ExactOracle {
  public record Result(boolean complete, long leaves, Validation optimum) {
    public String status() {
      return complete ? (optimum == null ? "INFEASIBLE" : "OPTIMUM") : "INCONCLUSIVE";
    }
  }

  private final Validator validator = new Validator();
  private long limit, leaves;
  private boolean complete;
  private Validation best;

  public Result solve(Problem p, long limit) {
    if (p.jobs().size() > 7 || p.vehicles().size() > 2 || limit < 1)
      throw new IllegalArgumentException(
          "oracle is bounded to 7 jobs, 2 vehicles and a positive leaf limit");
    this.limit = limit;
    leaves = 0;
    complete = true;
    best = null;
    List<Route> routes = p.vehicles().stream().map(v -> new Route(v.id(), List.of())).toList();
    visit(p, 0, routes);
    return new Result(complete, leaves, complete ? best : null);
  }

  private void visit(Problem p, int next, List<Route> routes) {
    if (!complete) return;
    if (next == p.jobs().size()) {
      if (leaves >= limit) {
        complete = false;
        return;
      }
      leaves++;
      Validation value = validator.validate(p, Model.candidate(p, routes));
      if (value.valid() && (best == null || value.score().compareTo(best.score()) < 0))
        best = value;
      return;
    }
    // Excluding this job is an explicit unassigned choice, not a lost delivery.
    visit(p, next + 1, routes);
    String id = p.jobs().get(next).id();
    for (int v = 0; v < routes.size(); v++)
      for (int pos = 0; pos <= routes.get(v).jobs().size(); pos++) {
        List<Route> changed = new ArrayList<>(routes);
        List<String> order = new ArrayList<>(routes.get(v).jobs());
        order.add(pos, id);
        changed.set(v, new Route(routes.get(v).vehicleId(), order));
        visit(p, next + 1, changed);
        if (!complete) return;
      }
  }
}
