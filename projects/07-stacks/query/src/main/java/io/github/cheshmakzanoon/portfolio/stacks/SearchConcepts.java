package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.util.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

/** Hand-authored software-topic associations, not an external thesaurus or exact synonyms. */
public final class SearchConcepts {
  private SearchConcepts() {}

  private static final List<String> CATALOG =
      List.of(
          "checkpoint resume progress completed resumable",
          "digest hash checksum sha256 cryptographic integrity corruption",
          "canonical sorted order deterministic stable ordering",
          "serialize encode bytes binary serialization writing",
          "persist durable save flush force fsync",
          "cancel interrupt abort interrupted cancellation",
          "archive zip expansion compressed compression",
          "quota limit bounded budget oversized unbounded",
          "path root directory checkout filesystem component",
          "symlink link escape traversal outside",
          "authorization permission access permitted forbidden denied revoke revocation",
          "principal subject identity user authenticated",
          "cursor pagination page continuation next",
          "count total hit aggregate facet",
          "boost weight ranking score relevance",
          "generation snapshot revision version index",
          "publish replace pointer atomic rename publication switch replacement",
          "recover restart fallback quarantine crash recovery",
          "lease reader reference retire lifetime close",
          "decode utf8 charset malformed encoding characters unicode",
          "snippet excerpt preview highlight",
          "explain explanation reason why feature",
          "artifact coordinates dependency package");

  private record Group(int priority, List<String> terms) {}

  private static final List<Group> GROUPS = groups();

  private static LinkedHashSet<String> normalized(String value) {
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("prose", value)) {
      var term =
          stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      LinkedHashSet<String> terms = new LinkedHashSet<>();
      stream.reset();
      while (stream.incrementToken()) terms.add(term.toString());
      stream.end();
      return terms;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static List<Group> groups() {
    List<Group> groups = new ArrayList<>();
    for (String row : CATALOG) groups.add(new Group(groups.size(), List.copyOf(normalized(row))));
    return List.copyOf(groups);
  }

  public static Optional<Query> expansion(String raw) {
    if (raw == null) return Optional.empty();
    if (raw.length() > 512) throw new IllegalArgumentException("concept input quota");
    if (!raw.matches("[\\p{L}\\p{N}_$ -]+")
        || raw.strip().split("\\s+").length < 2
        || raw.matches(".*\\b(?:AND|OR|NOT)\\b.*")) return Optional.empty();
    Set<String> query = normalized(raw);
    List<Group> candidates =
        GROUPS.stream()
            .filter(group -> group.terms().stream().anyMatch(query::contains))
            .sorted(
                Comparator.<Group>comparingLong(
                        group -> -group.terms().stream().filter(query::contains).count())
                    .thenComparingInt(Group::priority))
            .toList();
    var expansion = new BooleanQuery.Builder();
    Set<String> claimedTriggers = new HashSet<>();
    int groups = 0;
    for (Group group : candidates) {
      Set<String> triggers = new HashSet<>(group.terms());
      triggers.retainAll(query);
      triggers.removeAll(claimedTriggers);
      if (triggers.isEmpty()) continue;
      List<String> related =
          group.terms().stream().filter(term -> !query.contains(term)).limit(4).toList();
      if (related.isEmpty()) continue;
      var alternatives = new BooleanQuery.Builder();
      for (String word : related)
        alternatives.add(new TermQuery(new Term("prose", word)), BooleanClause.Occur.SHOULD);
      expansion.add(alternatives.build(), BooleanClause.Occur.SHOULD);
      claimedTriggers.addAll(triggers);
      if (++groups == 6) break;
    }
    if (groups == 0) return Optional.empty();
    expansion.setMinimumNumberShouldMatch(Math.min(2, groups));
    return Optional.of(expansion.build());
  }
}
