package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrialBalanceTest {
  private static final LocalDate DAY = LocalDate.of(2026, 6, 30);
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
      B = UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
      C = UUID.fromString("00000000-0000-0000-0000-0000000000a3"),
      D = UUID.fromString("00000000-0000-0000-0000-0000000000a4");

  private static BigInteger n(long value) {
    return BigInteger.valueOf(value);
  }

  private static TrialBalance.Row row(
      UUID id, String name, String currency, Journal.Side side, long debits, long credits) {
    return new TrialBalance.Row(id, name, currency, side, n(debits), n(credits));
  }

  @Test
  void groupsByCurrencyOrdersAccountsAndTotalsEachSide() {
    var report =
        TrialBalance.of(
            DAY,
            List.of(
                row(C, "sales", "USD", Journal.Side.CREDIT, 0, 500),
                row(B, "sales", "CAD", Journal.Side.CREDIT, 0, 1250),
                row(D, "cash", "USD", Journal.Side.DEBIT, 500, 0),
                row(A, "cash", "CAD", Journal.Side.DEBIT, 1250, 0)));
    assertEquals(DAY, report.asOf());
    assertEquals(
        List.of("CAD", "USD"), report.currencies().stream().map(c -> c.currency()).toList());
    var cad = report.currencies().getFirst();
    assertEquals(n(1250), cad.debits());
    assertEquals(n(1250), cad.credits());
    assertTrue(cad.balanced());
    assertEquals(List.of("cash", "sales"), cad.accounts().stream().map(r -> r.name()).toList());
    assertTrue(report.balanced());
  }

  @Test
  void accountsWithTheSameNameOrderByIdentity() {
    var report =
        TrialBalance.of(
            DAY,
            List.of(
                row(B, "petty", "CAD", Journal.Side.DEBIT, 1, 0),
                row(A, "petty", "CAD", Journal.Side.DEBIT, 2, 0)));
    assertEquals(
        List.of(A, B),
        report.currencies().getFirst().accounts().stream().map(r -> r.account()).toList());
  }

  @Test
  void anImbalanceIsReportedNotHidden() {
    var report =
        TrialBalance.of(
            DAY,
            List.of(
                row(A, "cash", "CAD", Journal.Side.DEBIT, 100, 0),
                row(B, "sales", "CAD", Journal.Side.CREDIT, 0, 99),
                row(C, "ok-debit", "USD", Journal.Side.DEBIT, 5, 0),
                row(D, "ok-credit", "USD", Journal.Side.CREDIT, 0, 5)));
    assertFalse(report.currencies().get(0).balanced());
    assertTrue(report.currencies().get(1).balanced());
    assertFalse(report.balanced());
  }

  @Test
  void normalBalanceFollowsTheAccountsNormalSide() {
    assertEquals(n(70), row(A, "cash", "CAD", Journal.Side.DEBIT, 100, 30).normalBalance());
    assertEquals(n(-70), row(A, "contra", "CAD", Journal.Side.CREDIT, 100, 30).normalBalance());
    assertEquals(n(70), row(A, "sales", "CAD", Journal.Side.CREDIT, 30, 100).normalBalance());
    assertEquals(n(-70), row(A, "overdrawn", "CAD", Journal.Side.DEBIT, 30, 100).normalBalance());
    assertEquals(n(70), row(A, "x", "CAD", Journal.Side.DEBIT, 100, 30).net());
  }

  @Test
  void emptyInputIsAnEmptyBalancedReport() {
    var report = TrialBalance.of(DAY, List.of());
    assertTrue(report.currencies().isEmpty());
    assertTrue(report.balanced());
  }

  @Test
  void invalidRowsFailExplicitly() {
    for (var invalid :
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> new TrialBalance.Row(null, "n", "CAD", Journal.Side.DEBIT, n(0), n(0)),
            () -> new TrialBalance.Row(A, null, "CAD", Journal.Side.DEBIT, n(0), n(0)),
            () -> new TrialBalance.Row(A, "n", null, Journal.Side.DEBIT, n(0), n(0)),
            () -> new TrialBalance.Row(A, "n", "CAD", null, n(0), n(0)),
            () -> new TrialBalance.Row(A, "n", "CAD", Journal.Side.DEBIT, null, n(0)),
            () -> new TrialBalance.Row(A, "n", "CAD", Journal.Side.DEBIT, n(0), null)))
      assertEquals(
          "MISSING_FIELD", assertThrows(IllegalArgumentException.class, invalid).getMessage());
    assertEquals(
        "CURRENCY",
        assertThrows(
                IllegalArgumentException.class,
                () -> new TrialBalance.Row(A, "n", "EUR", Journal.Side.DEBIT, n(0), n(0)))
            .getMessage());
    for (var negative :
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> new TrialBalance.Row(A, "n", "CAD", Journal.Side.DEBIT, n(-1), n(0)),
            () -> new TrialBalance.Row(A, "n", "CAD", Journal.Side.DEBIT, n(0), n(-1))))
      assertEquals(
          "NEGATIVE_TOTAL", assertThrows(IllegalArgumentException.class, negative).getMessage());
    assertThrows(NullPointerException.class, () -> new TrialBalance(null, List.of()));
  }

  @Test
  void decimalTextIsExactAtEveryScaleAndBeyondTheSingleAmountBound() {
    assertEquals("12.30", TrialBalance.decimal("CAD", n(1230)));
    assertEquals("-0.05", TrialBalance.decimal("USD", n(-5)));
    assertEquals("500", TrialBalance.decimal("JPY", n(500)));
    assertEquals("0.00", TrialBalance.decimal("CAD", BigInteger.ZERO));
    // A sum over many accounts may exceed Money's per-amount bound without losing precision.
    var big = Money.MAX.multiply(n(3));
    assertEquals(big.toString(), TrialBalance.decimal("JPY", big));
    assertEquals(
        "CURRENCY",
        assertThrows(IllegalArgumentException.class, () -> TrialBalance.decimal("EUR", n(1)))
            .getMessage());
  }

  @Test
  void resultsAreImmutable() {
    var rows = new ArrayList<>(List.of(row(A, "cash", "CAD", Journal.Side.DEBIT, 1, 0)));
    var report = TrialBalance.of(DAY, rows);
    rows.clear();
    assertEquals(1, report.currencies().getFirst().accounts().size());
    assertThrows(UnsupportedOperationException.class, () -> report.currencies().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> report.currencies().getFirst().accounts().clear());
  }
}
