package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

public final class SnippetBuilder {
  private SnippetBuilder() {}

  public static String snippet(String content, String query) {
    String lower = content.toLowerCase(Locale.ROOT);
    int at = lower.indexOf(query.toLowerCase(Locale.ROOT));
    if (at < 0) at = 0;
    int start = Math.max(0, at - 60), end = Math.min(content.length(), start + 240);
    return content.substring(start, end);
  }
}
