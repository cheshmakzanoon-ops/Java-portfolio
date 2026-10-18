package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;

public final class Ranker {
  private final RankingProfile profile;

  public Ranker() {
    this(RankingProfile.current());
  }

  public Ranker(RankingProfile profile) {
    this.profile = Objects.requireNonNull(profile);
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
    String identifier = query.matches("[\\p{L}_$][\\p{L}\\p{N}_$]{0,127}") ? query : null;
    for (var candidate : candidates) {
      float bonus = 0;
      String features = "";
      if (mode != QueryPlanner.Mode.BASELINE && identifier != null) {
        var document =
            lease
                .reader()
                .storedFields()
                .document(candidate.document(), Set.of("declaration", "path"));
        if (mode != QueryPlanner.Mode.NO_IDENTIFIERS) {
          if (Arrays.asList(document.getValues("declaration"))
              .contains(identifier.toLowerCase(Locale.ROOT))) {
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
      scored.add(
          new CandidateRetriever.Candidate(
              candidate.document(), candidate.key(), candidate.score() + bonus, features));
    }
    return scored.stream()
        .sorted(
            Comparator.comparingDouble((CandidateRetriever.Candidate c) -> -c.score())
                .thenComparing(CandidateRetriever.Candidate::key))
        .toList();
  }
}
