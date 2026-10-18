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
  public static final List<String> SOURCE_NAMES;
  static {
    var names=new java.util.ArrayList<>(NAMES);
    names.addAll(List.of("memberExecutableCoverage","memberApiProseCoverage",
        "linkedValueCoverage","sameReceiverCoverage","guardBeforeEffectCoverage","terminalAlternativeCoverage"));
    SOURCE_NAMES=List.copyOf(names);
  }
  public static final double LOG_MAX = Math.log1p(1_000_000);

  private SearchFeatureSchema() {}

  public static double[] checked(double[] values) {
    if (values == null || !List.of(10,12,16).contains(values.length))
      throw new IllegalArgumentException("feature dimension");
    double[] copy = values.clone();
    for (int i = 0; i < copy.length; i++)
      if (!Double.isFinite(copy[i]) || copy[i] < 0 || copy[i] > (i < 6 ? LOG_MAX : 1))
        throw new IllegalArgumentException("feature bounds");
    return copy;
  }

  public static String version(int dimensions) {
    return switch(dimensions){case 10->VERSION;case 12->"source-member-v1";case 16->"source-relations-v1";default->throw new IllegalArgumentException("feature schema dimension");};
  }
  public static List<String> names(int dimensions){version(dimensions);return SOURCE_NAMES.subList(0,dimensions);}
}
