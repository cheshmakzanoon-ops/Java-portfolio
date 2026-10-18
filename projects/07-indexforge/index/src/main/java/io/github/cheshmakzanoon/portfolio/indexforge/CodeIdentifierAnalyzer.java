package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.tokenattributes.*;

public final class CodeIdentifierAnalyzer extends Analyzer {
  private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}_$]+");
  private static final Pattern IDENTIFIER_PIECES =
      Pattern.compile(
          "[\\p{Lu}]+(?=[\\p{Lu}][\\p{Ll}]|[^\\p{L}]|$)|[\\p{Lu}]?[\\p{Ll}]+|[\\p{N}]+|[\\p{L}]+");

  public record Part(String term, int start, int end, int position) {}

  public static List<Part> parts(String input) {
    List<Part> out = new ArrayList<>();
    Matcher words = WORDS.matcher(input);
    int position = 0;
    while (words.find()) {
      String original = words.group();
      String normalized = original.toLowerCase(Locale.ROOT);
      ++position;
      if (indexable(normalized))
        out.add(new Part(normalized, words.start(), words.end(), position));
      Matcher pieces = IDENTIFIER_PIECES.matcher(original);
      while (pieces.find()) {
        String p = pieces.group().toLowerCase(Locale.ROOT);
        if (!p.equals(normalized) && indexable(p))
          out.add(
              new Part(p, words.start() + pieces.start(), words.start() + pieces.end(), position));
      }
    }
    return out;
  }

  private static boolean indexable(String normalized) {
    // Lucene's bound applies AFTER case normalization and counts UTF8 bytes, not Java chars.
    // Drop the entire term: a truncated prefix would create false matches. The char precheck
    // also bounds the temporary UTF8 allocation to less than98KiB.
    return normalized.length() <= org.apache.lucene.index.IndexWriter.MAX_TERM_LENGTH
        && normalized.getBytes(StandardCharsets.UTF_8).length
            <= org.apache.lucene.index.IndexWriter.MAX_TERM_LENGTH;
  }

  @Override
  protected TokenStreamComponents createComponents(String field) {
    Tokenizer t =
        new Tokenizer() {
          final CharTermAttribute term = addAttribute(CharTermAttribute.class);
          final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
          final PositionIncrementAttribute increment =
              addAttribute(PositionIncrementAttribute.class);
          List<Part> tokens = List.of();
          int at, previous, length;

          @Override
          public void reset() throws IOException {
            super.reset();
            StringBuilder s = new StringBuilder();
            char[] b = new char[4096];
            int n;
            while ((n = input.read(b)) != -1) s.append(b, 0, n);
            length = s.length();
            tokens = parts(s.toString());
            at = previous = 0;
          }

          @Override
          public boolean incrementToken() {
            if (at >= tokens.size()) return false;
            clearAttributes();
            Part p = tokens.get(at++);
            term.append(p.term());
            offset.setOffset(correctOffset(p.start()), correctOffset(p.end()));
            increment.setPositionIncrement(p.position() - previous);
            previous = p.position();
            return true;
          }

          @Override
          public void end() throws IOException {
            super.end();
            offset.setOffset(correctOffset(length), correctOffset(length));
          }
        };
    return new TokenStreamComponents(t);
  }
}
