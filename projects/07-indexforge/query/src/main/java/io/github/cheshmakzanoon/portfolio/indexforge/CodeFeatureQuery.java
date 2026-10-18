package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

/** Soft topic evidence from generic API token patterns; two distinct topic terms are required. */
public final class CodeFeatureQuery {
  private CodeFeatureQuery() {}

  private static final Set<String> VOCABULARY = normalized(CodeFeatures.vocabulary());

  private static Set<String> normalized(String text) {
    Set<String> terms = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("code_features", text)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) terms.add(term.toString());
      stream.end();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return terms;
  }

  public static Optional<Query> plan(String raw) {
    if (raw == null) return Optional.empty();
    if (raw.length() > 512) throw new IllegalArgumentException("code hint query quota");
    if (!raw.contains(" ")
        || !raw.matches("[\\p{L}\\p{N}_$ -]+")
        || raw.matches(".*\\b(?:AND|OR|NOT)\\b.*")) return Optional.empty();
    Set<String> terms = normalized(raw);
    terms.retainAll(VOCABULARY);
    if (terms.size() < 2) return Optional.empty();
    var query = new BooleanQuery.Builder().setMinimumNumberShouldMatch(2);
    for (String term : terms)
      query.add(new TermQuery(new Term("code_features", term)), BooleanClause.Occur.SHOULD);
    return Optional.of(query.build());
  }
}
