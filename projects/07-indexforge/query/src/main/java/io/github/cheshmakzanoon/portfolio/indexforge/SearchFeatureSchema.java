package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.List;

/** Fixed shared interaction dimensions; no learned token, query, document or origin identities. */
public final class SearchFeatureSchema {
  public static final String VERSION = "shared-v1";
  public static final List<String> NAMES =
      List.of(
          "candidateLogScore",
          "contentLogScore",
          "proseLogScore",
          "identifierSymbolLogScore",
          "pathLogScore",
          "codeTopicLogScore",
          "proseTermCoverage",
          "proseMatchedDensity",
          "proseOrderedPhrase",
          "declarationMatch");
  public static final int SIZE = NAMES.size();
  public static final double LOG_MAX = Math.log1p(1_000_000);

  private SearchFeatureSchema() {}

  public static double[] checked(double[] values) {
    if (values == null || values.length != SIZE)
      throw new IllegalArgumentException("feature dimension");
    double[] copy = values.clone();
    for (int i = 0; i < SIZE; i++)
      if (!Double.isFinite(copy[i]) || copy[i] < 0 || copy[i] > (i < 6 ? LOG_MAX : 1))
        throw new IllegalArgumentException("feature bounds");
    return copy;
  }
}
