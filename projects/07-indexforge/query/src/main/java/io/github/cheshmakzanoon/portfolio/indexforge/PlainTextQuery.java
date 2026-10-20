package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.Set;

/** Bounded syntax classifier; never reads fixture types or guesses a user's unstated domain. */
public final class PlainTextQuery {
  private PlainTextQuery() {}

  public static boolean multiword(String raw) {
    if (raw == null || raw.isBlank()) return false;
    if (raw.length() > 512) throw new IllegalArgumentException("query length");
    String[] words = raw.strip().split("\\p{javaWhitespace}+");
    if (words.length < 2 || words.length > 64) return false;
    for (String word : words)
      if (Set.of("AND", "OR", "NOT").contains(word)
          || !word.matches("[\\p{L}\\p{N}_$]+(?:-[\\p{L}\\p{N}_$]+)*")) return false;
    return true;
  }

  public static boolean identifier(String raw) {
    // Regex repetition counts code points, while the bounded graph query counts UTF-16
    // units. Longer supplementary names remain searchable through the ordinary lane.
    return raw != null && raw.length() <= 128 && raw.matches("[\\p{L}_$][\\p{L}\\p{N}_$]{0,127}");
  }
}
