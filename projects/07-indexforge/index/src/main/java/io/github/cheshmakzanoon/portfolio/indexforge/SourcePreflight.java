package io.github.cheshmakzanoon.portfolio.indexforge;

import java.nio.charset.StandardCharsets;

/** Linear allocation-bounded preflight; unsupported Unicode preprocessing abstains as a unit. */
public final class SourcePreflight {
  private SourcePreflight() {}

  public static SourceFacts.Status check(String source) {
    if (source == null
        || source.length() > SourceFacts.SOURCE_BYTES
        || source.getBytes(StandardCharsets.UTF_8).length > SourceFacts.SOURCE_BYTES)
      return SourceFacts.Status.SOURCE_QUOTA;
    // Conservative superset: even an ineligible escaped-u sequence abstains. Never parse a
    // different program by applying an incomplete Java Unicode-escape eligibility algorithm.
    if (source.contains("\\u")) return SourceFacts.Status.UNSUPPORTED;
    int tokens = 0, depth = 0;
    char[] nesting = new char[32];
    for (int i = 0; i < source.length(); ) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        i += 2;
        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
        int end = source.indexOf("*/", i + 2);
        if (end < 0) return SourceFacts.Status.SYNTAX;
        i = end + 2;
        continue;
      }
      if (++tokens > 8192) return SourceFacts.Status.SOURCE_QUOTA;
      if (c == '"' || c == '\'') {
        boolean block = c == '"' && source.startsWith("\"\"\"", i);
        i += block ? 3 : 1;
        boolean ended = false;
        while (i < source.length()) {
          char q = source.charAt(i++);
          if (q == '\\') {
            if (i < source.length()) i++;
            continue;
          }
          if (block && q == '"' && source.startsWith("\"\"", i)) {
            i += 2;
            ended = true;
            break;
          }
          if (!block && q == c) {
            ended = true;
            break;
          }
          if (!block && (q == '\n' || q == '\r')) return SourceFacts.Status.SYNTAX;
        }
        if (!ended) return SourceFacts.Status.SYNTAX;
        continue;
      }
      if (Character.isJavaIdentifierStart(c)) {
        int start = i++;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
        if (i - start > 256) return SourceFacts.Status.SOURCE_QUOTA;
        continue;
      }
      if (c == '(' || c == '[' || c == '{') {
        if (depth == nesting.length) return SourceFacts.Status.SOURCE_QUOTA;
        nesting[depth++] = c;
      } else if (c == ')' || c == ']' || c == '}') {
        if (depth == 0) return SourceFacts.Status.SYNTAX;
        char opening = nesting[--depth];
        if (opening != (c == ')' ? '(' : c == ']' ? '[' : '{')) return SourceFacts.Status.SYNTAX;
      }
      i++;
    }
    return depth == 0 ? SourceFacts.Status.OBSERVED : SourceFacts.Status.SYNTAX;
  }
}
