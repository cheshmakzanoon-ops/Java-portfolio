package io.github.cheshmakzanoon.portfolio.daybook;

import java.nio.charset.StandardCharsets;

/**
 * Text retained in PostgreSQL and canonical intent must survive UTF-8 encoding without replacement.
 */
public final class LedgerText {
  private LedgerText() {}

  public static boolean valid(String value) {
    return value != null
        && value.indexOf('\0') < 0
        && StandardCharsets.UTF_8.newEncoder().canEncode(value);
  }
}
