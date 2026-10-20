package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.regex.Pattern;

public final class SnippetBuilder {
  private SnippetBuilder() {}

  public static String snippet(String content, String query) {
    // Lowercase normalization can expand text (for example U+0130). Its offsets cannot index the
    // original source. Quoting preserves literal query semantics and matching returns source
    // offsets.
    var match =
        Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(content);
    int at = match.find() ? match.start() : 0;
    int start = Math.max(0, at - 60);
    if (start > 0
        && Character.isLowSurrogate(content.charAt(start))
        && Character.isHighSurrogate(content.charAt(start - 1))) start--;
    int end = Math.min(content.length(), start + 240);
    if (end < content.length()
        && end > start
        && Character.isHighSurrogate(content.charAt(end - 1))
        && Character.isLowSurrogate(content.charAt(end))) end--;
    return content.substring(start, end);
  }
}
