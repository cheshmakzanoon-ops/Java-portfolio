package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import net.jqwik.api.*;

/** Generated books checked against the independent {@link ReferenceLedger}. */
class TrialBalanceProperties {
  private static final UUID PERIOD = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

  private record Account(UUID id, String name, String currency, Journal.Side side) {}

  private static List<Account> accounts(Random random) {
    List<Account> accounts = new ArrayList<>();
    for (int i = 0; i < 6; i++)
      accounts.add(
          new Account(
              new UUID(0xacc0L, i + 1),
              "account-" + random.nextInt(4),
              i < 3 ? "CAD" : "USD",
              random.nextBoolean() ? Journal.Side.DEBIT : Journal.Side.CREDIT));
    return accounts;
  }

  private static Journal journal(Random random, List<Account> accounts, LocalDate date) {
    String currency = random.nextBoolean() ? "CAD" : "USD";
    List<Account> usable = accounts.stream().filter(a -> a.currency().equals(currency)).toList();
    int debitLines = 1 + random.nextInt(3), creditLines = 1 + random.nextInt(3);
    List<Journal.Line> lines = new ArrayList<>();
    long total = 0;
    for (int i = 0; i < debitLines; i++) {
      long amount = 3 + random.nextInt(1_000_000);
      total += amount;
      lines.add(
          new Journal.Line(
              usable.get(random.nextInt(usable.size())).id(),
              Journal.Side.DEBIT,
              BigInteger.valueOf(amount)));
    }
    long remaining = total;
    for (int i = 0; i < creditLines; i++) {
      long amount =
          i == creditLines - 1
              ? remaining
              : 1 + random.nextInt((int) Math.max(1, remaining - (creditLines - 1 - i)));
      remaining -= amount;
      lines.add(
          new Journal.Line(
              usable.get(random.nextInt(usable.size())).id(),
              Journal.Side.CREDIT,
              BigInteger.valueOf(amount)));
    }
    return new Journal(PERIOD, date, currency, "generated", lines);
  }

  @Property(tries = 250, seed = "20261029")
  void everyCurrencyBalancesAndEveryNetAgreesWithTheReferenceLedger(@ForAll long seed) {
    Random random = new Random(seed);
    List<Account> accounts = accounts(random);
    LocalDate asOf = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365));
    ReferenceLedger reference = new ReferenceLedger();
    Map<UUID, BigInteger> debits = new HashMap<>(), credits = new HashMap<>();
    int included = 0;
    for (int i = 0; i < 1 + random.nextInt(40); i++) {
      LocalDate date = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365));
      Journal journal = journal(random, accounts, date);
      if (random.nextInt(5) == 0) journal = journal.reversed(date);
      if (date.isAfter(asOf)) continue;
      included++;
      reference.apply(journal);
      for (Journal.Line line : journal.lines())
        (line.side() == Journal.Side.DEBIT ? debits : credits)
            .merge(line.account(), line.minor(), BigInteger::add);
    }
    List<TrialBalance.Row> rows = new ArrayList<>();
    for (Account account : accounts)
      rows.add(
          new TrialBalance.Row(
              account.id(),
              account.name(),
              account.currency(),
              account.side(),
              debits.getOrDefault(account.id(), BigInteger.ZERO),
              credits.getOrDefault(account.id(), BigInteger.ZERO)));
    TrialBalance report = TrialBalance.of(asOf, rows);

    assertTrue(report.balanced(), "seed " + seed + " included " + included);
    int seen = 0;
    for (TrialBalance.CurrencyTotals currency : report.currencies()) {
      assertEquals(currency.debits(), currency.credits());
      BigInteger net = BigInteger.ZERO;
      for (TrialBalance.Row row : currency.accounts()) {
        seen++;
        assertEquals(reference.balances().getOrDefault(row.account(), BigInteger.ZERO), row.net());
        assertEquals(reference.displayed(row.account(), row.normalSide()), row.normalBalance());
        net = net.add(row.net());
      }
      assertEquals(BigInteger.ZERO, net, "debits minus credits over a currency is zero");
    }
    assertEquals(accounts.size(), seen);
  }
}
