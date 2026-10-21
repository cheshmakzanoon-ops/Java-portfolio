package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;
import java.util.function.LongSupplier;

/** Fixed wave3 DEV-only fitting; no validation inputs, family rescue, or extra regularization. */
public final class SourcePairwiseSelection {
  public static final List<Double> L2_GRID = List.of(.1, 1.0);
  private final LongSupplier clock;
  private final long started;

  public SourcePairwiseSelection() {
    this(System::nanoTime);
  }

  SourcePairwiseSelection(LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock);
    started = clock.getAsLong();
  }

  public record Trial(
      double l2,
      PairwiseSelection.Metrics metrics,
      String failure,
      Map<String, List<String>> outOfFoldPaths) {
    public Trial {
      outOfFoldPaths = Map.copyOf(outOfFoldPaths);
    }
  }

  public record Fold(
      double l2,
      int fold,
      PairwiseTrainer.Result fit,
      String failure,
      Map<String, List<String>> paths) {
    public Fold {
      paths = Map.copyOf(paths);
    }
  }

  public record Result(
      String status,
      List<Fold> folds,
      List<Trial> trials,
      PairwiseTrainer.Result selected,
      Map<String, List<String>> selectedOutOfFoldPaths) {
    public Result {
      folds = List.copyOf(folds);
      trials = List.copyOf(trials);
      selectedOutOfFoldPaths = Map.copyOf(selectedOutOfFoldPaths);
    }
  }

  public Result select(
      List<PairwiseData.Query> development,
      Map<String, Map<String, Integer>> judgments,
      Set<String> identifiers,
      Map<String, Integer> assignments,
      String exportHash,
      String foldsHash,
      PairwiseSelection.Metrics r0,
      PairwiseSelection.Metrics originalBaseline) {
    PairwiseData.developmentOnly(development);
    if (!GroupedFolds.assign(development).equals(assignments))
      throw new IllegalArgumentException("source grouped DEV identity differs");
    if (!judgments
        .keySet()
        .equals(
            development.stream()
                .map(PairwiseData.Query::id)
                .collect(java.util.stream.Collectors.toSet())))
      throw new IllegalArgumentException("source DEV label identity differs");
    var first = development.stream().flatMap(q -> q.rows().stream()).findFirst();
    if (first.isEmpty())
      return new Result(
          "NOT_ATTEMPTED_SOURCE_AUDIT_CONDITION", List.of(), List.of(), null, Map.of());
    int dimensions = first.get().features().length;
    if (dimensions != 12 && dimensions != 16)
      throw new IllegalArgumentException("source family schema");
    for (var query : development) {
      if (!query.group().equals(Wave2Inputs.judgmentGroup(judgments.get(query.id()))))
        throw new IllegalArgumentException("source DEV group differs");
      Set<Integer> ranks = new HashSet<>();
      for (var row : query.rows())
        if (row.features().length != dimensions
            || !ranks.add(row.baseRank())
            || row.grade() != judgments.get(query.id()).getOrDefault(row.key(), 0))
          throw new IllegalArgumentException("source DEV row integrity");
    }
    long eligible =
        development.stream()
            .filter(SourcePairwiseSelection::reranks)
            .filter(
                q ->
                    q.rows().stream().anyMatch(r -> r.grade() > 0)
                        && q.rows().stream().anyMatch(r -> r.grade() == 0))
            .count();
    boolean lowerGrade3 =
        development.stream()
            .filter(SourcePairwiseSelection::reranks)
            .anyMatch(q -> q.rows().stream().anyMatch(r -> r.grade() == 3 && r.baseRank() > 0));
    if (eligible < 10 || !lowerGrade3)
      return new Result(
          "NOT_ATTEMPTED_SOURCE_AUDIT_CONDITION", List.of(), List.of(), null, Map.of());
    var folds = new ArrayList<Fold>();
    var trials = new ArrayList<Trial>();
    Double chosen = null;
    PairwiseSelection.Metrics best = null;
    Map<String, List<String>> selectedPaths = Map.of();
    for (double l2 : L2_GRID) {
      var paths = new TreeMap<String, List<String>>();
      String failure = null;
      for (int fold = 0; fold < 5; fold++) {
        try {
          var training =
              GroupedFolds.training(development, assignments, fold).stream()
                  .filter(SourcePairwiseSelection::reranks)
                  .toList();
          var fit = trainer().fit(training, l2, exportHash, foldsHash);
          var held = rankedPaths(GroupedFolds.held(development, assignments, fold), fit.model());
          folds.add(new Fold(l2, fold, fit, null, held));
          paths.putAll(held);
        } catch (PairwiseTrainer.TrainingFailure failed) {
          failure = failed.getMessage();
          folds.add(new Fold(l2, fold, null, failure, Map.of()));
          break;
        }
      }
      if (failure != null) {
        trials.add(new Trial(l2, null, failure, Map.of()));
        continue;
      }
      var metric = PairwiseSelection.metrics(paths, judgments, identifiers);
      trials.add(new Trial(l2, metric, null, paths));
      if (metric.dominates(r0)
          && metric.dominates(originalBaseline)
          && (best == null
              || metric.objective() > best.objective()
              || metric.objective() == best.objective() && l2 > chosen)) {
        chosen = l2;
        best = metric;
        selectedPaths = Map.copyOf(paths);
      }
    }
    if (chosen == null)
      return new Result("FAILED_NO_JOINT_DEVELOPMENT_DOMINANCE", folds, trials, null, Map.of());
    try {
      var refit =
          trainer()
              .fit(
                  development.stream().filter(SourcePairwiseSelection::reranks).toList(),
                  chosen,
                  exportHash,
                  foldsHash);
      budget();
      return new Result("FITTED_BEFORE_VALIDATION", folds, trials, refit, selectedPaths);
    } catch (PairwiseTrainer.TrainingFailure failed) {
      return new Result(
          "FAILED_ALL_DEVELOPMENT_REFIT: " + failed.getMessage(), folds, trials, null, Map.of());
    }
  }

  public static boolean reranks(PairwiseData.Query query) {
    if (!PlainTextQuery.multiword(query.text())) return false;
    for (var row : query.rows())
      for (int i = 10; i < row.features().length; i++) if (row.features()[i] > 0) return true;
    return false;
  }

  public static Map<String, List<String>> rankedPaths(
      List<PairwiseData.Query> queries, LinearRankingModel model) {
    var result = new TreeMap<String, List<String>>();
    for (var query : queries) {
      Comparator<PairwiseData.Row> order =
          reranks(query)
              ? Comparator.<PairwiseData.Row>comparingDouble(
                      row -> (float) model.score(row.features()))
                  .reversed()
                  .thenComparing(PairwiseData.Row::key)
              : Comparator.comparingInt(PairwiseData.Row::baseRank);
      result.put(
          query.id(),
          query.rows().stream().sorted(order).map(PairwiseData.Row::key).limit(20).toList());
    }
    return Map.copyOf(result);
  }

  private PairwiseTrainer trainer() {
    return new PairwiseTrainer(clock, 100, Math.min(30_000_000_000L, budget()));
  }

  private long budget() {
    long remaining = 900_000_000_000L - (clock.getAsLong() - started);
    if (remaining <= 0)
      throw new PairwiseTrainer.TrainingFailure("whole source DEV fitting budget exceeded");
    return remaining;
  }
}
