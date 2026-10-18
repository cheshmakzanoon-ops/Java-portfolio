package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Labels attach only after the serving candidate/feature export; identities are metadata only. */
public final class PairwiseData {
  private PairwiseData() {}

  public record Row(String key, double[] features, int grade, int baseRank) {
    public Row {
      if (key == null
          || key.isBlank()
          || key.length() > 4096
          || grade < 0
          || grade > 3
          || baseRank < 0
          || baseRank >= 500) throw new IllegalArgumentException("training row");
      features = SearchFeatureSchema.checked(features);
    }

    public Row(String key, double[] features, int grade) {
      this(key, features, grade, 0);
    }

    @Override
    public double[] features() {
      return features.clone();
    }
  }

  public record Query(String id, String group, String text, String split, List<Row> rows) {
    public Query {
      if (id == null
          || id.isBlank()
          || id.length() > 128
          || group == null
          || !group.matches("[a-f0-9]{64}")
          || !Set.of("development", "validation").contains(split)
          || text == null
          || text.isBlank()
          || text.length() > 512
          || rows.size() > 500) throw new IllegalArgumentException("training query");
      QueryAst.parse(text);
      if (rows.stream().map(Row::key).distinct().count() != rows.size())
        throw new IllegalArgumentException("duplicate training candidate");
      rows = rows.stream().sorted(Comparator.comparing(Row::key)).toList();
    }
  }

  public static void developmentOnly(List<Query> queries) {
    if (queries.isEmpty()
        || queries.size() > 40
        || queries.stream().anyMatch(q -> !q.split().equals("development"))
        || queries.stream().map(Query::id).distinct().count() != queries.size())
      throw new IllegalArgumentException("development-only training data required");
  }
}
