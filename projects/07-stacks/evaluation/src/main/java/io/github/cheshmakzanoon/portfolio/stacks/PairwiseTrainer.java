package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Fixed bounded full-batch logistic pairwise learner. No execution occurs during preregistration.
 */
public final class PairwiseTrainer {
  public static final long SEED = 7007;
  public static final int PAIRS_PER_QUERY = 512;
  public static final List<Double> L2_GRID = List.of(.01, .1, 1.0);
  private final LongSupplier clock;
  private final int iterations;
  private final long budget;

  public PairwiseTrainer() {
    this(System::nanoTime, 100, 30_000_000_000L);
  }

  PairwiseTrainer(LongSupplier clock, int iterations, long budget) {
    if (iterations < 1 || iterations > 100 || budget < 1 || budget > 30_000_000_000L)
      throw new IllegalArgumentException("training limits");
    this.clock = Objects.requireNonNull(clock);
    this.iterations = iterations;
    this.budget = budget;
  }

  public static final class TrainingFailure extends IllegalStateException {
    TrainingFailure(String reason) {
      super(reason);
    }
  }

  private record Pair(double[] difference, double weight) {}

  private record Choice(int high, int low, double weight) {}

  private record Objective(double loss, double[] gradient) {}

  public record Result(
      LinearRankingModel model,
      int iterations,
      double initialLoss,
      double finalLoss,
      int pairs,
      int eligibleQueries,
      long seed) {}

  public Result fit(
      List<PairwiseData.Query> queries, double l2, String exportHash, String foldsHash) {
    PairwiseData.developmentOnly(queries);
    if (!L2_GRID.contains(l2)) throw new IllegalArgumentException("unregistered regularization");
    if (exportHash == null
        || !exportHash.matches("[a-f0-9]{64}")
        || foldsHash == null
        || !foldsHash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("training provenance");
    long started = clock.getAsLong();
    DevelopmentScaler scaler;
    try {
      scaler = DevelopmentScaler.fit(queries);
    } catch (IllegalArgumentException invalid) {
      throw new TrainingFailure("no eligible development scaler observations");
    }
    List<List<Pair>> perQuery = new ArrayList<>();
    for (var query :
        queries.stream().sorted(Comparator.comparing(PairwiseData.Query::id)).toList()) {
      checkBudget(started);
      if (!PlainTextQuery.multiword(query.text())) continue;
      List<double[]> values = query.rows().stream().map(r -> scaler.apply(r.features())).toList();
      List<Choice> reservoir = new ArrayList<>();
      SplittableRandom random = new SplittableRandom(SEED ^ query.id().hashCode());
      long seen = 0;
      for (int i = 0; i < query.rows().size(); i++) {
        checkBudget(started);
        for (int j = i + 1; j < query.rows().size(); j++) {
          int a = query.rows().get(i).grade(), b = query.rows().get(j).grade();
          if (a == b) continue;
          Choice pair = new Choice(a > b ? i : j, a > b ? j : i, Math.abs((1 << a) - (1 << b)));
          seen++;
          if (reservoir.size() < PAIRS_PER_QUERY) reservoir.add(pair);
          else {
            long replace = random.nextLong(seen);
            if (replace < PAIRS_PER_QUERY) reservoir.set((int) replace, pair);
          }
        }
      }
      if (reservoir.isEmpty()) continue;
      double sum = reservoir.stream().mapToDouble(Choice::weight).sum();
      List<Pair> pairs = new ArrayList<>();
      for (var choice : reservoir) {
        double[] difference = new double[scaler.means().length];
        for (int k = 0; k < difference.length; k++)
          difference[k] = values.get(choice.high())[k] - values.get(choice.low())[k];
        pairs.add(new Pair(difference, choice.weight() / sum));
      }
      perQuery.add(pairs);
    }
    if (perQuery.size() < 2)
      throw new TrainingFailure("fewer than two eligible development queries with pairs");
    List<Pair> pairs = new ArrayList<>();
    for (var query : perQuery)
      for (var pair : query)
        pairs.add(new Pair(pair.difference(), pair.weight() / perQuery.size()));
    double[] weights = new double[scaler.means().length];
    Objective current = objective(pairs, weights, l2);
    double initial = current.loss();
    for (int iteration = 0; iteration <= iterations; iteration++) {
      checkBudget(started);
      double infinity = 0, squared = 0;
      for (double gradient : current.gradient()) {
        infinity = Math.max(infinity, Math.abs(gradient));
        squared += gradient * gradient;
      }
      if (infinity <= 1e-6)
        return result(
            scaler,
            weights,
            l2,
            exportHash,
            foldsHash,
            iteration,
            initial,
            current.loss(),
            pairs.size(),
            perQuery.size());
      if (iteration == iterations) break;
      Objective next = null;
      double[] candidate = null;
      double step = 1 / l2;
      for (int halve = 0; halve <= 20; halve++, step *= .5) {
        checkBudget(started);
        candidate = weights.clone();
        for (int i = 0; i < candidate.length; i++) candidate[i] -= step * current.gradient()[i];
        next = objective(pairs, candidate, l2);
        if (next.loss() <= current.loss() - 1e-4 * step * squared) break;
        next = null;
      }
      if (next == null) throw new TrainingFailure("bounded line search did not converge");
      double relative = (current.loss() - next.loss()) / Math.max(1, Math.abs(current.loss()));
      weights = candidate;
      if (relative <= 1e-9)
        return result(
            scaler,
            weights,
            l2,
            exportHash,
            foldsHash,
            iteration + 1,
            initial,
            next.loss(),
            pairs.size(),
            perQuery.size());
      current = next;
    }
    throw new TrainingFailure(
        "bounded optimizer did not converge within" + iterations + " iterations");
  }

  private Result result(
      DevelopmentScaler scaler,
      double[] weights,
      double l2,
      String exportHash,
      String foldsHash,
      int iterations,
      double initial,
      double loss,
      int pairs,
      int queries) {
    try {
      return new Result(
          new LinearRankingModel(
              scaler.means(), scaler.scales(), weights, exportHash, foldsHash, l2),
          iterations,
          initial,
          loss,
          pairs,
          queries,
          SEED);
    } catch (IllegalArgumentException invalid) {
      throw new TrainingFailure("trained model violates finite artifact bounds");
    }
  }

  private void checkBudget(long started) {
    if (clock.getAsLong() - started > budget)
      throw new TrainingFailure("training wall budget exceeded");
  }

  private static Objective objective(List<Pair> pairs, double[] weights, double l2) {
    double loss = 0;
    double[] gradient = new double[weights.length];
    for (var pair : pairs) {
      double margin = 0;
      for (int i = 0; i < weights.length; i++) margin += weights[i] * pair.difference()[i];
      loss += pair.weight() * (Math.max(0, -margin) + Math.log1p(Math.exp(-Math.abs(margin))));
      double exp = Math.exp(-Math.abs(margin));
      double sigmoid = margin >= 0 ? exp / (1 + exp) : 1 / (1 + exp);
      for (int i = 0; i < gradient.length; i++)
        gradient[i] -= pair.weight() * sigmoid * pair.difference()[i];
    }
    for (int i = 0; i < weights.length; i++) {
      loss += l2 * weights[i] * weights[i] / 2;
      gradient[i] += l2 * weights[i];
      if (!Double.isFinite(gradient[i])) throw new TrainingFailure("nonfinite training gradient");
    }
    if (!Double.isFinite(loss)) throw new TrainingFailure("nonfinite training loss");
    return new Objective(loss, gradient);
  }
}
