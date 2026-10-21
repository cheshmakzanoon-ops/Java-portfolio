package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;

/** Pure linear inference over already-authorized fixed vectors, with stable key ties. */
public final class LearnedRanker {
  public List<CandidateRetriever.Candidate> rank(
      List<SearchFeatures.Vector> vectors, LinearRankingModel model) {
    if (vectors.size() > 500) throw new IllegalArgumentException("rerank window");
    Objects.requireNonNull(model);
    List<CandidateRetriever.Candidate> scored = new ArrayList<>();
    for (var vector : vectors) {
      var candidate = vector.candidate();
      double[] features = vector.values();
      scored.add(
          new CandidateRetriever.Candidate(
              candidate.document(),
              candidate.key(),
              (float) model.score(features),
              candidate.features() + " " + model.explanation(features)));
    }
    return scored.stream()
        .sorted(
            Comparator.comparingDouble((CandidateRetriever.Candidate c) -> -c.score())
                .thenComparing(CandidateRetriever.Candidate::key))
        .toList();
  }
}
