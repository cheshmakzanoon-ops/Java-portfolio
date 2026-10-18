package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
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
      out.add(new Part(original.toLowerCase(Locale.ROOT), words.start(), words.end(), ++position));
      Matcher pieces = IDENTIFIER_PIECES.matcher(original);
      while (pieces.find()) {
        String p = pieces.group().toLowerCase(Locale.ROOT);
        if (!p.equals(original.toLowerCase(Locale.ROOT)))
          out.add(
              new Part(p, words.start() + pieces.start(), words.start() + pieces.end(), position));
      }
    }
    return out;
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
