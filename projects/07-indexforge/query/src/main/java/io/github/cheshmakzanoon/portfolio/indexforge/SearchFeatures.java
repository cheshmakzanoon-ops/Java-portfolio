package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;

/**
 * Shared authorized indexed interactions, bounded to500 candidates and64 normalized query terms.
 */
public final class SearchFeatures {
  public record Vector(CandidateRetriever.Candidate candidate, double[] values) {
    public Vector {
      Objects.requireNonNull(candidate);
      values = SearchFeatureSchema.checked(values);
    }

    @Override
    public double[] values() {
      return values.clone();
    }
  }

  public List<Vector> extract(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      String raw,
      QueryPlanner.Mode mode,
      RankingProfile profile,
      AuthorizationFilter authorization,
      AuthorizationFilter.Access access)
      throws IOException {
    if (candidates.size() > 500) throw new IllegalArgumentException("feature candidate quota");
    if (!authorization.current(access)) throw new SecurityException("access denied");
    QueryAst.parse(raw);
    Set<String> queryTerms = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("prose", raw)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        queryTerms.add(term.toString());
        if (queryTerms.size() > 64) throw new IllegalArgumentException("feature term quota");
      }
      stream.end();
    }
    Set<Integer> seen = new HashSet<>();
    for (var candidate : candidates)
      if (candidate.document() < 0
          || candidate.document() >= lease.reader().maxDoc()
          || !seen.add(candidate.document()))
        throw new IllegalArgumentException("feature candidate identity");
    double[] allowed =
        scores(candidates, lease, authorization.apply(new MatchAllDocsQuery(), access));
    for (double match : allowed) if (match <= 0) throw new SecurityException("access denied");
    var channels = new QueryPlanner(profile).featureChannels(raw);
    Map<String, double[]> scores = new LinkedHashMap<>();
    for (var channel : channels.entrySet())
      scores.put(
          channel.getKey(),
          scores(candidates, lease, authorization.apply(channel.getValue(), access)));
    List<Vector> result = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      var candidate = candidates.get(i);
      double[] f = new double[SearchFeatureSchema.SIZE];
      f[0] = logScore(candidate.score());
      f[1] = logScore(scores.get("content")[i]);
      f[2] = logScore(scores.get("prose")[i]);
      f[3] = logScore(scores.get("identifiers")[i]);
      f[4] = logScore(scores.get("path")[i]);
      f[5] = logScore(scores.get("code")[i]);
      f[8] = scores.get("phrase")[i] > 0 ? 1 : 0;
      f[9] = scores.get("declaration")[i] > 0 ? 1 : 0;
      var vector = lease.reader().termVectors().get(candidate.document(), "prose");
      if (vector != null && !queryTerms.isEmpty()) {
        var terms = vector.iterator();
        long frequency = 0;
        int matches = 0;
        for (String term : queryTerms)
          if (terms.seekExact(new BytesRef(term))) {
            matches++;
            frequency += Math.max(0, terms.totalTermFreq());
          }
        f[6] = (double) matches / queryTerms.size();
        long total = vector.getSumTotalTermFreq();
        f[7] = total > 0 ? Math.min(1, (double) frequency / total) : 0;
      }
      if (mode == QueryPlanner.Mode.NO_IDENTIFIERS) {
        f[3] = 0;
        f[9] = 0;
      }
      if (mode == QueryPlanner.Mode.NO_PATH) f[4] = 0;
      if (mode == QueryPlanner.Mode.NO_PROSE) {
        f[2] = 0;
        f[6] = 0;
        f[7] = 0;
        f[8] = 0;
      }
      if (mode == QueryPlanner.Mode.NO_CODE_FEATURES) f[5] = 0;
      if (mode == QueryPlanner.Mode.NO_COVERAGE) {
        f[6] = 0;
        f[7] = 0;
      }
      result.add(new Vector(candidate, f));
    }
    if (!authorization.current(access)) throw new SecurityException("access denied");
    return List.copyOf(result);
  }

  private static double logScore(double score) {
    if (!Double.isFinite(score) || score < 0)
      throw new IllegalArgumentException("feature score bounds");
    return Math.log1p(Math.min(1_000_000, score));
  }

  private static double[] scores(
      List<CandidateRetriever.Candidate> candidates, GenerationLease lease, Query authorized)
      throws IOException {
    var searcher = lease.searcher();
    var weight = searcher.createWeight(searcher.rewrite(authorized), ScoreMode.COMPLETE, 1);
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) order.add(i);
    order.sort(Comparator.comparingInt(i -> candidates.get(i).document()));
    double[] result = new double[candidates.size()];
    int at = 0;
    for (LeafReaderContext leaf : lease.reader().leaves()) {
      Scorer scorer = weight.scorer(leaf);
      var iterator =
          scorer == null
              ? null
              : scorer.twoPhaseIterator() == null
                  ? scorer.iterator()
                  : TwoPhaseIterator.asDocIdSetIterator(scorer.twoPhaseIterator());
      while (at < order.size()) {
        int index = order.get(at), document = candidates.get(index).document();
        if (document >= leaf.docBase + leaf.reader().maxDoc()) break;
        int local = document - leaf.docBase;
        if (iterator != null) {
          int actual = iterator.docID();
          if (actual < local) actual = iterator.advance(local);
          if (actual == local) result[index] = scorer.score();
        }
        at++;
      }
    }
    return result;
  }
}
