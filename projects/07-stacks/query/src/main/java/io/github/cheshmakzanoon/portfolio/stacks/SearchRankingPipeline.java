package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.search.*;

/** One generation-bound ranking path shared by serving and label-blind evaluation. */
final class SearchRankingPipeline {
  private final RankingProfile profile;
  private final RankingConfiguration configuration;
  private final AuthorizationFilter authorization;

  SearchRankingPipeline(
      RankingProfile profile,
      RankingConfiguration configuration,
      AuthorizationFilter authorization) {
    this.profile = profile;
    this.configuration = configuration;
    this.authorization = authorization;
  }

  List<CandidateRetriever.Candidate> candidates(
      GenerationLease lease, Query query, String raw, QueryPlanner.Mode mode) throws IOException {
    if (configuration.strategy().structural() && mode != QueryPlanner.Mode.BASELINE)
      SourceFeatureView.requireSchema(lease, configuration.catalog());
    if (mode != QueryPlanner.Mode.BASELINE && mode != QueryPlanner.Mode.NO_SIMILARITY)
      lease
          .searcher()
          .setSimilarity(
              new org.apache.lucene.search.similarities.BM25Similarity(profile.k1, profile.b));
    return new Ranker(profile, configuration.strategy())
        .rank(new CandidateRetriever().retrieve(lease, query), lease, raw, mode);
  }

  List<SearchFeatures.Vector> features(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      String raw,
      QueryPlanner.Mode mode,
      AuthorizationFilter.Access access)
      throws IOException {
    var vectors =
        new SearchFeatures().extract(candidates, lease, raw, mode, profile, authorization, access);
    return configuration.strategy().structural()
        ? new SourceSearchFeatures()
            .extract(vectors, lease, raw, mode, configuration, authorization, access)
            .vectors()
        : vectors;
  }

  List<CandidateRetriever.Candidate> rank(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      String raw,
      QueryPlanner.Mode mode,
      AuthorizationFilter.Access access)
      throws IOException {
    var ranked = candidates;
    if (configuration.strategy().learned()
        && mode != QueryPlanner.Mode.BASELINE
        && PlainTextQuery.multiword(raw)) {
      var vectors =
          new SearchFeatures()
              .extract(candidates, lease, raw, mode, profile, authorization, access);
      if (configuration.strategy().structural()) {
        var extraction =
            new SourceSearchFeatures()
                .extract(vectors, lease, raw, mode, configuration, authorization, access);
        if (extraction.usableNewEvidence())
          ranked = new LearnedRanker().rank(extraction.vectors(), configuration.model());
      } else ranked = new LearnedRanker().rank(vectors, configuration.model());
    }
    return configuration.strategy() == RankingStrategy.SOURCE_USAGE
        ? usage(ranked, lease, raw, mode, access).candidates()
        : ranked;
  }

  SourceUsageRanker.Decision usage(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      String raw,
      QueryPlanner.Mode mode,
      AuthorizationFilter.Access access)
      throws IOException {
    if (!PlainTextQuery.identifier(raw)
        || Set.of(
                QueryPlanner.Mode.BASELINE,
                QueryPlanner.Mode.NO_IDENTIFIERS,
                QueryPlanner.Mode.NO_CODE_FEATURES,
                QueryPlanner.Mode.NO_MEMBER_USAGE)
            .contains(mode)) return new SourceUsageRanker.Decision(candidates, "SYNTAX_BYPASS");
    try (var view =
        new SourceFeatureView(candidates, lease, configuration.catalog(), authorization, access)) {
      return new SourceUsageRanker().decide(candidates, raw, view.usage(raw));
    }
  }
}
