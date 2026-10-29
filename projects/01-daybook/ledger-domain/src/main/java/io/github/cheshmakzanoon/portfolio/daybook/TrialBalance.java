package io.github.cheshmakzanoon.portfolio.daybook;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Per-currency trial balance. Every journal balances in one currency, so total debits equal total
 * credits for each currency; a report that says otherwise is evidence of damaged data, not of an
 * accounting position, and is surfaced rather than hidden.
 */
public record TrialBalance(LocalDate asOf, List<CurrencyTotals> currencies) {
  public TrialBalance {
    Objects.requireNonNull(asOf, "asOf");
    currencies = List.copyOf(currencies);
  }

  /** One account's cumulative debits and credits in minor units. */
  public record Row(
      UUID account,
      String name,
      String currency,
      Journal.Side normalSide,
      BigInteger debits,
      BigInteger credits) {
    public Row {
      if (account == null
          || name == null
          || currency == null
          || normalSide == null
          || debits == null
          || credits == null) throw new IllegalArgumentException("MISSING_FIELD");
      if (!Money.SCALES.containsKey(currency)) throw new IllegalArgumentException("CURRENCY");
      if (debits.signum() < 0 || credits.signum() < 0)
        throw new IllegalArgumentException("NEGATIVE_TOTAL");
    }

    /** Debits minus credits. */
    public BigInteger net() {
      return debits.subtract(credits);
    }

    /** The balance as presented on the account's normal side; negative when it runs against it. */
    public BigInteger normalBalance() {
      return normalSide == Journal.Side.DEBIT ? net() : net().negate();
    }
  }

  public record CurrencyTotals(
      String currency, BigInteger debits, BigInteger credits, List<Row> accounts) {
    public CurrencyTotals {
      accounts = List.copyOf(accounts);
    }

    public boolean balanced() {
      return debits.equals(credits);
    }
  }

  /** Groups rows by currency, orders accounts by name then identity, and totals each side. */
  public static TrialBalance of(LocalDate asOf, List<Row> rows) {
    var byCurrency = new TreeMap<String, List<Row>>();
    for (var row : rows)
      byCurrency.computeIfAbsent(row.currency(), c -> new ArrayList<>()).add(row);
    var currencies = new ArrayList<CurrencyTotals>();
    byCurrency.forEach(
        (currency, accounts) -> {
          accounts.sort(Comparator.comparing(Row::name).thenComparing(Row::account));
          var debits = BigInteger.ZERO;
          var credits = BigInteger.ZERO;
          for (var account : accounts) {
            debits = debits.add(account.debits());
            credits = credits.add(account.credits());
          }
          currencies.add(new CurrencyTotals(currency, debits, credits, accounts));
        });
    return new TrialBalance(asOf, currencies);
  }

  /**
   * Exact decimal text for an amount of minor units; unlike {@link Money} it has no upper bound.
   */
  public static String decimal(String currency, BigInteger minor) {
    var scale = Money.SCALES.get(currency);
    if (scale == null) throw new IllegalArgumentException("CURRENCY");
    return new BigDecimal(minor, scale).toPlainString();
  }

  public boolean balanced() {
    return currencies.stream().allMatch(CurrencyTotals::balanced);
  }
}
