package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import net.jqwik.api.*;
import org.junit.jupiter.api.*;

class AccountingProperties {
  static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000011"),
      B = UUID.fromString("00000000-0000-0000-0000-000000000012"),
      P = UUID.fromString("00000000-0000-0000-0000-000000000013");

  @Property(tries = 300, seed = "20261017")
  void reversalIsAnInvolution(
      @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 1000000) int amount,
      @ForAll boolean creditNormal) {
    var n = BigInteger.valueOf(amount);
    var j =
        new Journal(
            P,
            LocalDate.of(2026, 1, 1),
            "CAD",
            "r",
            List.of(
                new Journal.Line(A, Journal.Side.DEBIT, n),
                new Journal.Line(B, Journal.Side.CREDIT, n)));
    var ref = new ReferenceLedger();
    ref.apply(j);
    assertEquals(n, ref.balances().get(A));
    assertEquals(
        creditNormal ? n.negate() : n,
        ref.displayed(A, creditNormal ? Journal.Side.CREDIT : Journal.Side.DEBIT));
    ref.apply(j.reversed(j.date()));
    assertTrue(ref.balances().values().stream().allMatch(BigInteger.ZERO::equals));
    assertEquals(j.lines(), j.reversed(j.date()).reversed(j.date()).lines());
  }

  @Property(tries = 200, seed = "20261018")
  void imbalanceAlwaysRejected(
      @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 1000000) int amount) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Journal(
                P,
                LocalDate.now(),
                "USD",
                "r",
                List.of(
                    new Journal.Line(A, Journal.Side.DEBIT, BigInteger.valueOf(amount)),
                    new Journal.Line(B, Journal.Side.CREDIT, BigInteger.valueOf(amount + 1)))));
  }
}
