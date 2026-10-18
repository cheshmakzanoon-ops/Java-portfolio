package io.github.cheshmakzanoon.portfolio.indexforge;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.en.*;

/** Original source tokenization with Lucene's standard English stopword and Porter filters. */
public final class SourceProseAnalyzer extends AnalyzerWrapper {
  private final CodeIdentifierAnalyzer identifiers = new CodeIdentifierAnalyzer();

  public SourceProseAnalyzer() {
    super(PER_FIELD_REUSE_STRATEGY);
  }

  @Override
  protected Analyzer getWrappedAnalyzer(String field) {
    return identifiers;
  }

  @Override
  protected TokenStreamComponents wrapComponents(String field, TokenStreamComponents components) {
    return new TokenStreamComponents(
        components.getSource(),
        new PorterStemFilter(
            new StopFilter(components.getTokenStream(), EnglishAnalyzer.ENGLISH_STOP_WORDS_SET)));
  }

  @Override
  public void close() {
    super.close();
    identifiers.close();
  }
}
