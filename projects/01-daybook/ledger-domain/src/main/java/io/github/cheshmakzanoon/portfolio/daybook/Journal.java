package io.github.cheshmakzanoon.portfolio.daybook;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Journal(
    UUID period, LocalDate date, String currency, String reference, List<Line> lines) {
  public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
      return this == DEBIT ? CREDIT : DEBIT;
    }
  }

  public record Line(UUID account, Side side, BigInteger minor) {
    public Line {
      if (account == null || side == null) throw new IllegalArgumentException("MISSING_FIELD");
      if (minor == null || minor.signum() <= 0 || minor.compareTo(Money.MAX) > 0)
        throw new IllegalArgumentException("POSITIVE_BOUNDED_AMOUNT");
    }
  }

  public Journal {
    if (period == null || date == null || currency == null || lines == null)
      throw new IllegalArgumentException("MISSING_FIELD");
    if (!Money.SCALES.containsKey(currency)) throw new IllegalArgumentException("CURRENCY");
    if (!LedgerText.valid(reference) || reference.length() > 120)
      throw new IllegalArgumentException("REFERENCE");
    if (lines.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("MISSING_FIELD");
    lines = List.copyOf(lines);
    if (lines.size() < 2 || lines.size() > 100) throw new IllegalArgumentException("BATCH_BOUND");
    var debit = BigInteger.ZERO;
    var credit = BigInteger.ZERO;
    for (var l : lines)
      if (l.side() == Side.DEBIT) debit = debit.add(l.minor());
      else credit = credit.add(l.minor());
    if (!debit.equals(credit) || debit.compareTo(Money.MAX) > 0)
      throw new IllegalArgumentException("UNBALANCED_OR_OVERFLOW");
  }

  public Journal reversed(LocalDate when) {
    return new Journal(
        period,
        when,
        currency,
        "reversal:" + digest(),
        lines.stream().map(l -> new Line(l.account(), l.side().opposite(), l.minor())).toList());
  }

  public String digest() {
    try {
      var s =
          period
              + "|"
              + date
              + "|"
              + currency
              + "|"
              + reference
              + "|"
              + lines.stream()
                  .map(l -> l.account() + ":" + l.side() + ":" + l.minor())
                  .sorted()
                  .toList();
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
