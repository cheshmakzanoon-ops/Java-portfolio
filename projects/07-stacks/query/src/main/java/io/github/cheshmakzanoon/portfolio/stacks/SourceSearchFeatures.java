package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Serving and audit share the same structural vector/mask path. No labels enter this class. */
public final class SourceSearchFeatures {
  public record Extraction(List<SearchFeatures.Vector> vectors, boolean usableNewEvidence) {
    public Extraction {
      vectors = List.copyOf(vectors);
    }
  }

  public Extraction extract(
      List<SearchFeatures.Vector> base,
      GenerationLease lease,
      String raw,
      QueryPlanner.Mode mode,
      RankingConfiguration ranking,
      AuthorizationFilter authorization,
      AuthorizationFilter.Access access)
      throws IOException {
    if (!ranking.strategy().structural())
      throw new IllegalArgumentException("structural strategy required");
    boolean enabled =
        PlainTextQuery.multiword(raw)
            && !Set.of(
                    QueryPlanner.Mode.BASELINE,
                    QueryPlanner.Mode.NO_MEMBER_SCOPE,
                    QueryPlanner.Mode.NO_CODE_FEATURES,
                    QueryPlanner.Mode.NO_PROSE,
                    QueryPlanner.Mode.NO_COVERAGE)
                .contains(mode);
    Map<Integer, double[]> source = Map.of();
    if (enabled)
      try (var view =
          new SourceFeatureView(
              base.stream().map(SearchFeatures.Vector::candidate).toList(),
              lease,
              ranking.catalog(),
              authorization,
              access)) {
        source =
            view.features(
                queryTerms(raw),
                new SourceRelations.Options(
                    mode != QueryPlanner.Mode.NO_IDENTIFIERS,
                    mode != QueryPlanner.Mode.NO_API_DOCS,
                    ranking.strategy().dimensions() == 16
                        && mode != QueryPlanner.Mode.NO_RELATIONS));
      }
    List<SearchFeatures.Vector> result = new ArrayList<>();
    boolean evidence = false;
    for (var vector : base) {
      double[] values = Arrays.copyOf(vector.values(), ranking.strategy().dimensions());
      var extra = source.get(vector.candidate().document());
      if (extra != null)
        for (int i = 10; i < values.length; i++) {
          values[i] = extra[i - 10];
          evidence |= values[i] > 0;
        }
      result.add(new SearchFeatures.Vector(vector.candidate(), values));
    }
    if (!authorization.current(access)) throw new SecurityException("access denied");
    return new Extraction(result, evidence);
  }

  private static Set<String> queryTerms(String raw) throws IOException {
    Set<String> query = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("prose", raw)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        query.add(term.toString());
        if (query.size() > 64) throw new IllegalArgumentException("source query term quota");
      }
      stream.end();
    }
    return Collections.unmodifiableSet(query);
  }
}
