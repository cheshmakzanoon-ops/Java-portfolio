package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;

public final class Ranker {
  private final RankingProfile profile;
  private final RankingStrategy strategy;

  public Ranker() {
    this(RankingProfile.current());
  }

  public Ranker(RankingProfile profile) {
    this(profile, RankingStrategy.CLASSIC);
  }

  public Ranker(RankingProfile profile, RankingStrategy strategy) {
    this.profile = Objects.requireNonNull(profile);
    this.strategy = Objects.requireNonNull(strategy);
  }

  private static Set<String> coverageTerms(String query) throws IOException {
    if (!query.contains(" ")
        || !query.matches("[\\p{L}\\p{N}_$ -]+")
        || java.util.regex.Pattern.compile("(?:^| )(?:AND|OR|NOT)(?: |$)").matcher(query).find())
      return Set.of();
    Set<String> terms = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("prose", query)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        terms.add(term.toString());
        if (terms.size() > 64) throw new IllegalArgumentException("coverage query quota");
      }
      stream.end();
    }
    return terms;
  }

  public List<CandidateRetriever.Candidate> rank(
      List<CandidateRetriever.Candidate> candidates,
      GenerationLease lease,
      String query,
      QueryPlanner.Mode mode)
      throws IOException {
    if (candidates.size() > 500) throw new IllegalArgumentException("rerank window");
    Set<String> queryTerms =
        profile.coverage > 0
                && mode != QueryPlanner.Mode.BASELINE
                && mode != QueryPlanner.Mode.NO_COVERAGE
                && mode != QueryPlanner.Mode.NO_PROSE
            ? coverageTerms(query)
            : Set.of();
    List<CandidateRetriever.Candidate> scored = new ArrayList<>();
    boolean tiered =
        mode != QueryPlanner.Mode.BASELINE
            && mode != QueryPlanner.Mode.NO_IDENTIFIERS
            && strategy.declarationTier(query);
    Map<Integer, Float> baseScores = new HashMap<>();
    Set<Integer> exactDeclarations = new HashSet<>();
    String identifier = query.matches("[\\p{L}_$][\\p{L}\\p{N}_$]{0,127}") ? query : null;
    for (var candidate : candidates) {
      float bonus = 0;
      String features = "";
      boolean exactDeclaration = false;
      if (mode != QueryPlanner.Mode.BASELINE && identifier != null) {
        var document =
            lease
                .reader()
                .storedFields()
                .document(candidate.document(), Set.of("declaration", "path"));
        if (mode != QueryPlanner.Mode.NO_IDENTIFIERS) {
          if (Arrays.asList(document.getValues("declaration"))
              .contains(identifier.toLowerCase(Locale.ROOT))) {
            exactDeclaration = true;
            bonus += 4;
            features += " declarationBonus=4";
          }
        }
        if (mode != QueryPlanner.Mode.NO_PATH) {
          String path = document.get("path").toLowerCase(Locale.ROOT);
          String stem = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
          if (stem.equals(identifier.toLowerCase(Locale.ROOT))) {
            bonus += 2;
            features += " pathStemBonus=2";
          }
        }
      }
      if (!queryTerms.isEmpty()) {
        Terms vector = lease.reader().termVectors().get(candidate.document(), "prose");
        if (vector != null) {
          var terms = vector.iterator();
          int matched = 0;
          for (String term : queryTerms) if (terms.seekExact(new BytesRef(term))) matched++;
          float coverage = (float) matched / queryTerms.size();
          float coverageBonus = profile.coverage * coverage * coverage;
          bonus += coverageBonus;
          features += " queryTermCoverage=" + coverage + " coverageBonus=" + coverageBonus;
        }
      }
      float score = candidate.score() + bonus;
      if (tiered) {
        baseScores.put(candidate.document(), score);
        if (exactDeclaration) exactDeclarations.add(candidate.document());
        if (!Float.isFinite(score) || score < 0)
          throw new IllegalArgumentException("base score bounds");
        score = (float) ((exactDeclaration ? 2 : 0) + (double) score / (1.0 + score));
        features += " declarationTier=" + (exactDeclaration ? 1 : 0);
      }
      scored.add(
          new CandidateRetriever.Candidate(candidate.document(), candidate.key(), score, features));
    }
    Comparator<CandidateRetriever.Candidate> order =
        tiered
            ? Comparator.<CandidateRetriever.Candidate>comparingInt(
                    c -> exactDeclarations.contains(c.document()) ? 0 : 1)
                .thenComparingDouble(c -> -baseScores.get(c.document()))
                .thenComparing(CandidateRetriever.Candidate::key)
            : Comparator.comparingDouble((CandidateRetriever.Candidate c) -> -c.score())
                .thenComparing(CandidateRetriever.Candidate::key);
    // Preserve the exact original order within tiers even when float normalization rounds equal.
    return scored.stream().sorted(order).toList();
  }
}
