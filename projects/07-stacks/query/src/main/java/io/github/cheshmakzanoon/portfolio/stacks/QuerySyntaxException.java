package io.github.cheshmakzanoon.portfolio.stacks;

/**
 * An unacceptable query. The message stays the short internal code every caller already handles;
 * {@code position} is the 0-based UTF-16 offset in the query text where parsing stopped (the text
 * length when the query ended too early).
 */
public final class QuerySyntaxException extends IllegalArgumentException {
  private final int position;

  QuerySyntaxException(String code, int position) {
    super(code);
    this.position = position;
  }

  public int position() {
    return position;
  }
}
