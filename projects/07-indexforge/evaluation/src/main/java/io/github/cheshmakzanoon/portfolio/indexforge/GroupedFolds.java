package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Deterministic whole-judgment-group allocation; IDs are never a learned dimension. */
public final class GroupedFolds {
  private GroupedFolds() {}

  public static Map<String, Integer> assign(List<PairwiseData.Query> queries) {
    PairwiseData.developmentOnly(queries);
    Map<String, List<String>> groups = new TreeMap<>();
    for (var query : queries)
      groups.computeIfAbsent(query.group(), ignored -> new ArrayList<>()).add(query.id());
    if (groups.size() < 5)
      throw new IllegalArgumentException("five distinct development groups required");
    List<String> order = new ArrayList<>(groups.keySet());
    order.sort(Comparator.comparing(group -> Digests.sha256("7007\n" + group)));
    int[] loads = new int[5];
    Map<String, Integer> result = new TreeMap<>();
    for (String group : order) {
      int fold = 0;
      for (int i = 1; i < loads.length; i++) if (loads[i] < loads[fold]) fold = i;
      loads[fold] += groups.get(group).size();
      for (String id : groups.get(group)) result.put(id, fold);
    }
    return Collections.unmodifiableMap(result);
  }

  public static List<PairwiseData.Query> training(
      List<PairwiseData.Query> queries, Map<String, Integer> folds, int heldFold) {
    validate(queries, folds, heldFold);
    return queries.stream().filter(q -> folds.get(q.id()) != heldFold).toList();
  }

  public static List<PairwiseData.Query> held(
      List<PairwiseData.Query> queries, Map<String, Integer> folds, int heldFold) {
    validate(queries, folds, heldFold);
    return queries.stream().filter(q -> folds.get(q.id()) == heldFold).toList();
  }

  private static void validate(
      List<PairwiseData.Query> queries, Map<String, Integer> folds, int heldFold) {
    PairwiseData.developmentOnly(queries);
    if (heldFold < 0
        || heldFold >= 5
        || !folds
            .keySet()
            .equals(
                queries.stream()
                    .map(PairwiseData.Query::id)
                    .collect(java.util.stream.Collectors.toSet()))
        || folds.values().stream().anyMatch(f -> f == null || f < 0 || f >= 5))
      throw new IllegalArgumentException("fold assignment");
    Map<String, Integer> grouped = new HashMap<>();
    for (var query : queries) {
      Integer previous = grouped.putIfAbsent(query.group(), folds.get(query.id()));
      if (previous != null && !previous.equals(folds.get(query.id())))
        throw new IllegalArgumentException("judgment group leakage");
    }
    if (new HashSet<>(folds.values()).size() != 5) throw new IllegalArgumentException("empty fold");
  }
}
