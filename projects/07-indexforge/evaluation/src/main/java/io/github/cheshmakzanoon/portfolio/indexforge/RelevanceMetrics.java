package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

public final class RelevanceMetrics {
  private RelevanceMetrics() {}

  public record Values(double ndcg10, double mrr10, double recall20) {}

  public static Values evaluate(List<String> retrieved, Map<String, Integer> grades) {
    if (grades.values().stream().anyMatch(g -> g < 0 || g > 3))
      throw new IllegalArgumentException("grade");
    Set<String> unique = new HashSet<>();
    double dcg = 0, mrr = 0;
    int found = 0;
    for (int i = 0; i < Math.min(20, retrieved.size()); i++) {
      String id = retrieved.get(i);
      if (!unique.add(id)) throw new IllegalArgumentException("duplicate retrieval");
      int grade = grades.getOrDefault(id, 0);
      if (grade > 0) {
        found++;
        if (i < 10 && mrr == 0) mrr = 1.0 / (i + 1);
      }
      if (i < 10) dcg += (Math.pow(2, grade) - 1) / (Math.log(i + 2) / Math.log(2));
    }
    List<Integer> ideal = grades.values().stream().sorted(Comparator.reverseOrder()).toList();
    double idcg = 0;
    for (int i = 0; i < Math.min(10, ideal.size()); i++)
      idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
    long relevant = grades.values().stream().filter(g -> g > 0).count();
    return new Values(
        idcg == 0 ? 0 : dcg / idcg, mrr, relevant == 0 ? 0 : (double) found / relevant);
  }
}
