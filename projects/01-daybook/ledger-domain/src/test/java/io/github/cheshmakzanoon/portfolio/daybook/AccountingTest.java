package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AccountingTest {
  @Test
  void storageTextPreservesValidUnicodeAndRejectsLossyOrUnrepresentableValues() {
    assertTrue(LedgerText.valid(""));
    assertTrue(LedgerText.valid("café-支払-\uD83D\uDE00"));
    assertFalse(LedgerText.valid(null));
    for (String invalid : List.of("\0first", "middle\0end", "\uD800", "\uDC00"))
      assertFalse(LedgerText.valid(invalid));
  }

  @Test
  void malformedUnicodeCannotCollapseDistinctReferencesIntoOneUtf8Digest() {
    var lines =
        List.of(
            new Journal.Line(A, Journal.Side.DEBIT, BigInteger.ONE),
            new Journal.Line(B, Journal.Side.CREDIT, BigInteger.ONE));
    var date = LocalDate.of(2026, 1, 2);
    for (String invalid : List.of("\uD800", "\uDC00", "before\uD800after", "zero\0byte")) {
      var error =
          assertThrows(
              IllegalArgumentException.class, () -> new Journal(P, date, "CAD", invalid, lines));
      assertEquals("REFERENCE", error.getMessage());
    }
    var accepted = new Journal(P, date, "CAD", "café-支払-\uD83D\uDE00", lines);
    assertEquals(
        accepted.digest(), new Journal(P, date, "CAD", accepted.reference(), lines).digest());
    assertNotEquals(accepted.digest(), new Journal(P, date, "CAD", "?", lines).digest());
  }

  @Test
  void requiredJournalFieldsFailExplicitlyBeforePersistence() {
    var date = LocalDate.of(2026, 1, 2);
    var lines =
        List.of(
            new Journal.Line(A, Journal.Side.DEBIT, BigInteger.ONE),
            new Journal.Line(B, Journal.Side.CREDIT, BigInteger.ONE));
    var missing =
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> new Journal(null, date, "CAD", "ref", lines),
            () -> new Journal(P, null, "CAD", "ref", lines),
            () -> new Journal(P, date, null, "ref", lines),
            () -> new Journal(P, date, "CAD", "ref", null),
            () -> new Journal(P, date, "CAD", "ref", Arrays.asList(lines.getFirst(), null)),
            () -> new Journal.Line(null, Journal.Side.DEBIT, BigInteger.ONE),
            () -> new Journal.Line(A, null, BigInteger.ONE));
    for (var invalid : missing)
      assertEquals(
          "MISSING_FIELD", assertThrows(IllegalArgumentException.class, invalid).getMessage());
  }

  static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000011"),
      B = UUID.fromString("00000000-0000-0000-0000-000000000012"),
      P = UUID.fromString("00000000-0000-0000-0000-000000000013");

  @Test
  void strictCurrencyAndBounds() {
    assertEquals("1.20", Money.parse("CAD", "1.20").decimal());
    assertEquals("12.34", Money.parse("CAD", "12.34").decimal());
    assertEquals("0.00", Money.parse("CAD", "-0.00").decimal());
    assertThrows(IllegalArgumentException.class, () -> Money.parse("CAD", "1".repeat(100000)));
    assertEquals("5", Money.parse("JPY", "5").decimal());
    for (String s : List.of("1.001", "1e2", "01.00", "NaN", " 1.00", "1.", "+1"))
      assertThrows(IllegalArgumentException.class, () -> Money.parse("CAD", s));
    assertThrows(IllegalArgumentException.class, () -> Money.parse("JPY", "1.0"));
    assertThrows(IllegalArgumentException.class, () -> Money.parse("EUR", "1"));
    assertThrows(
        IllegalArgumentException.class, () -> new Money("CAD", Money.MAX.add(BigInteger.ONE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Journal.Line(A, Journal.Side.DEBIT, BigInteger.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Journal(P, LocalDate.now(), "CAD", "x", List.of()));
  }

  @Test
  void canonicalDigestIgnoresLineOrderButPreservesIntent() {
    var lines =
        List.of(
            new Journal.Line(A, Journal.Side.DEBIT, BigInteger.TEN),
            new Journal.Line(B, Journal.Side.CREDIT, BigInteger.TEN));
    var j = new Journal(P, LocalDate.now(), "CAD", "x", lines);
    var rev = new ArrayList<>(lines);
    Collections.reverse(rev);
    assertEquals(j.digest(), new Journal(P, j.date(), "CAD", "x", rev).digest());
    assertNotEquals(j.digest(), new Journal(P, j.date(), "CAD", "y", rev).digest());
    var ref = new ReferenceLedger();
    var max =
        new Journal(
            P,
            j.date(),
            "CAD",
            "max",
            List.of(
                new Journal.Line(A, Journal.Side.DEBIT, Money.MAX),
                new Journal.Line(B, Journal.Side.CREDIT, Money.MAX)));
    ref.apply(max);
    assertThrows(IllegalArgumentException.class, () -> ref.apply(j));
    assertEquals(Money.MAX, ref.balances().get(A));
  }

  @Test
  void limitsPreserveAtomicReferenceAndReversibleLongReferences() {
    assertEquals("-12.34", Money.parse("CAD", "-12.34").decimal());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Journal.Line(A, Journal.Side.DEBIT, Money.MAX.add(BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class, () -> new Money("CAD", null));
    assertThrows(IllegalArgumentException.class, () -> Money.parse(null, "1"));
    var lines =
        List.of(
            new Journal.Line(A, Journal.Side.DEBIT, BigInteger.ONE),
            new Journal.Line(B, Journal.Side.CREDIT, BigInteger.ONE));
    var j = new Journal(P, LocalDate.of(2026, 1, 1), "CAD", "r".repeat(120), lines);
    assertEquals(Journal.Side.CREDIT, j.reversed(j.date()).lines().getFirst().side());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Journal(P, j.date(), "CAD", "r".repeat(121), lines));
    assertThrows(IllegalArgumentException.class, () -> new Journal(P, j.date(), "EUR", "r", lines));
    var hundred = new ArrayList<Journal.Line>();
    for (int i = 0; i < 50; i++) hundred.addAll(lines);
    assertEquals(100, new Journal(P, j.date(), "CAD", "many", hundred).lines().size());
    hundred.add(lines.getFirst());
    assertThrows(
        IllegalArgumentException.class, () -> new Journal(P, j.date(), "CAD", "many", hundred));
    var overflow =
        List.of(
            new Journal.Line(A, Journal.Side.DEBIT, Money.MAX),
            new Journal.Line(A, Journal.Side.DEBIT, BigInteger.ONE),
            new Journal.Line(B, Journal.Side.CREDIT, Money.MAX),
            new Journal.Line(B, Journal.Side.CREDIT, BigInteger.ONE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Journal(P, j.date(), "CAD", "overflow", overflow));
  }

  @Test
  void accumulationAndDigestIncludeEveryEconomicLine() {
    var date = LocalDate.of(2026, 1, 1);
    var j10 =
        new Journal(
            P,
            date,
            "CAD",
            "same",
            List.of(
                new Journal.Line(A, Journal.Side.DEBIT, BigInteger.TEN),
                new Journal.Line(B, Journal.Side.CREDIT, BigInteger.TEN)));
    var j20 =
        new Journal(
            P,
            date,
            "CAD",
            "same",
            List.of(
                new Journal.Line(A, Journal.Side.DEBIT, BigInteger.valueOf(20)),
                new Journal.Line(B, Journal.Side.CREDIT, BigInteger.valueOf(20))));
    assertNotEquals(j10.digest(), j20.digest());
    assertNotEquals(
        j10.digest(),
        new Journal(
                P,
                date,
                "CAD",
                "same",
                List.of(
                    new Journal.Line(A, Journal.Side.CREDIT, BigInteger.TEN),
                    new Journal.Line(B, Journal.Side.DEBIT, BigInteger.TEN)))
            .digest());
    var c = UUID.fromString("00000000-0000-0000-0000-000000000014");
    assertNotEquals(
        j10.digest(),
        new Journal(
                P,
                date,
                "CAD",
                "same",
                List.of(
                    new Journal.Line(c, Journal.Side.DEBIT, BigInteger.TEN),
                    new Journal.Line(B, Journal.Side.CREDIT, BigInteger.TEN)))
            .digest());
    var ref = new ReferenceLedger();
    ref.apply(j10);
    ref.apply(j20);
    assertEquals(BigInteger.valueOf(30), ref.balances().get(A));
    assertEquals(BigInteger.valueOf(-30), ref.balances().get(B));
    ref.apply(j10.reversed(date));
    assertEquals(BigInteger.valueOf(20), ref.balances().get(A));
    assertEquals(BigInteger.valueOf(-20), ref.balances().get(B));
  }
}
