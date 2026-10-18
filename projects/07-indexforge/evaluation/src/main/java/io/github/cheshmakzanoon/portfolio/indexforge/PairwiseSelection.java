package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/**
 * Finite development-only grouped selection. Full judgments preserve missing-positive penalties.
 */
public final class PairwiseSelection {
  private PairwiseSelection() {}

  public record Metrics(double ndcg10, double identifierMrr10, double recall20) {
    public double objective() {
      return Math.min(ndcg10 / .75, identifierMrr10 / .90);
    }

    public boolean dominates(Metrics other) {
      return ndcg10 >= other.ndcg10 && identifierMrr10 >= other.identifierMrr10;
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

  public record Trial(double l2, Metrics metrics, String failure) {}

  public record Result(
      String status,
      Metrics comparator,
      List<Fold> folds,
      List<Trial> trials,
      PairwiseTrainer.Result selected) {
    public Result {
      folds = List.copyOf(folds);
      trials = List.copyOf(trials);
    }
  }

  public static Result select(
      List<PairwiseData.Query> development,
      Map<String, Map<String, Integer>> judgments,
      Set<String> identifierIds,
      Map<String, Integer> assignments,
      String exportHash,
      String foldsHash) {
    PairwiseData.developmentOnly(development);
    validateLabels(development, judgments, identifierIds);
    if (!GroupedFolds.assign(development).equals(assignments))
      throw new IllegalArgumentException("preregistered grouped fold assignment differs");
    Metrics comparator = metrics(basePaths(development), judgments, identifierIds);
    long eligible =
        development.stream()
            .filter(q -> PlainTextQuery.multiword(q.text()))
            .filter(
                q ->
                    q.rows().stream().anyMatch(r -> r.grade() > 0)
                        && q.rows().stream().anyMatch(r -> r.grade() == 0))
            .count();
    boolean lowerGrade3 =
        development.stream()
            .filter(q -> PlainTextQuery.multiword(q.text()))
            .anyMatch(q -> q.rows().stream().anyMatch(r -> r.grade() == 3 && r.baseRank() > 0));
    if (eligible < 10 || !lowerGrade3)
      return new Result("NOT_ATTEMPTED_AUDIT_CONDITION", comparator, List.of(), List.of(), null);
    long started = System.nanoTime();
    List<Fold> folds = new ArrayList<>();
    List<Trial> trials = new ArrayList<>();
    Double chosen = null;
    Metrics chosenMetrics = null;
    for (double l2 : PairwiseTrainer.L2_GRID) {
      Map<String, List<String>> paths = new TreeMap<>();
      String failure = null;
      for (int fold = 0; fold < 5; fold++) {
        try {
          checkBudget(started);
          var fit =
              trainer(started)
                  .fit(
                      GroupedFolds.training(development, assignments, fold),
                      l2,
                      exportHash,
                      foldsHash);
          Map<String, List<String>> held =
              rankedPaths(GroupedFolds.held(development, assignments, fold), fit.model());
          folds.add(new Fold(l2, fold, fit, null, held));
          paths.putAll(held);
        } catch (PairwiseTrainer.TrainingFailure failed) {
          failure = failed.getMessage();
          folds.add(new Fold(l2, fold, null, failure, Map.of()));
          break;
        }
      }
      if (failure != null) {
        trials.add(new Trial(l2, null, failure));
        continue;
      }
      Metrics score = metrics(paths, judgments, identifierIds);
      trials.add(new Trial(l2, score, null));
      if (score.dominates(comparator)
          && (chosenMetrics == null
              || score.objective() > chosenMetrics.objective()
              || (score.objective() == chosenMetrics.objective() && l2 > chosen))) {
        chosen = l2;
        chosenMetrics = score;
      }
    }
    if (chosen == null)
      return new Result("FAILED_NO_DEVELOPMENT_DOMINANCE", comparator, folds, trials, null);
    try {
      checkBudget(started);
      var selected = trainer(started).fit(development, chosen, exportHash, foldsHash);
      checkBudget(started);
      return new Result("FITTED_BEFORE_VALIDATION", comparator, folds, trials, selected);
    } catch (PairwiseTrainer.TrainingFailure failed) {
      return new Result(
          "FAILED_ALL_DEVELOPMENT_REFIT: " + failed.getMessage(), comparator, folds, trials, null);
    }
  }

  private static void checkBudget(long started) {
    if (System.nanoTime() - started > 600_000_000_000L)
      throw new PairwiseTrainer.TrainingFailure("whole development training budget exceeded");
  }

  private static PairwiseTrainer trainer(long started) {
    long remaining = 600_000_000_000L - (System.nanoTime() - started);
    if (remaining <= 0)
      throw new PairwiseTrainer.TrainingFailure("whole development training budget exceeded");
    return new PairwiseTrainer(System::nanoTime, 100, Math.min(30_000_000_000L, remaining));
  }

  private static void validateLabels(
      List<PairwiseData.Query> queries,
      Map<String, Map<String, Integer>> labels,
      Set<String> identifierIds) {
    Set<String> ids =
        queries.stream().map(PairwiseData.Query::id).collect(java.util.stream.Collectors.toSet());
    if (!labels.keySet().equals(ids) || identifierIds.isEmpty() || !ids.containsAll(identifierIds))
      throw new IllegalArgumentException("development judgment/header identities");
    for (var query : queries) {
      var full = labels.get(query.id());
      if (!query.group().equals(Wave2Inputs.judgmentGroup(full))
          || query.rows().stream().map(PairwiseData.Row::baseRank).distinct().count()
              != query.rows().size())
        throw new IllegalArgumentException("development group/base rank integrity");
      for (var row : query.rows())
        if (row.grade() != full.getOrDefault(row.key(), 0))
          throw new IllegalArgumentException("candidate grade differs from frozen judgment");
    }
  }

  static Map<String, List<String>> basePaths(List<PairwiseData.Query> queries) {
    Map<String, List<String>> paths = new TreeMap<>();
    for (var query : queries)
      paths.put(
          query.id(),
          query.rows().stream()
              .sorted(Comparator.comparingInt(PairwiseData.Row::baseRank))
              .map(PairwiseData.Row::key)
              .limit(20)
              .toList());
    return paths;
  }

  static Map<String, List<String>> rankedPaths(
      List<PairwiseData.Query> queries, LinearRankingModel model) {
    Map<String, List<String>> paths = new TreeMap<>();
    for (var query : queries) {
      Comparator<PairwiseData.Row> order =
          PlainTextQuery.multiword(query.text())
              ? Comparator.<PairwiseData.Row>comparingDouble(r -> (float) model.score(r.features()))
                  .reversed()
                  .thenComparing(PairwiseData.Row::key)
              : Comparator.comparingInt(PairwiseData.Row::baseRank);
      paths.put(
          query.id(),
          query.rows().stream().sorted(order).map(PairwiseData.Row::key).limit(20).toList());
    }
    return paths;
  }

  public static Metrics metrics(
      Map<String, List<String>> paths,
      Map<String, Map<String, Integer>> judgments,
      Set<String> identifiers) {
    if (paths.isEmpty()
        || !paths.keySet().equals(judgments.keySet())
        || identifiers.isEmpty()
        || !paths.keySet().containsAll(identifiers))
      throw new IllegalArgumentException("metric query identities");
    double ndcg = 0, mrr = 0, recall = 0;
    for (var entry : new TreeMap<>(paths).entrySet()) {
      var value = RelevanceMetrics.evaluate(entry.getValue(), judgments.get(entry.getKey()));
      ndcg += value.ndcg10();
      recall += value.recall20();
      if (identifiers.contains(entry.getKey())) mrr += value.mrr10();
    }
    return new Metrics(ndcg / paths.size(), mrr / identifiers.size(), recall / paths.size());
  }
}
