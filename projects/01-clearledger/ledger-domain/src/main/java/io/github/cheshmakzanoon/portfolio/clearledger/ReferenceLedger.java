package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Independent debit/credit summation oracle; credit-normal presentation reverses the signed sum.
 */
public final class ReferenceLedger {
  private final Map<UUID, BigInteger> debitTotals = new HashMap<>();
  private final Map<UUID, BigInteger> creditTotals = new HashMap<>();

  public void apply(Journal journal) {
    var debitDelta = new HashMap<UUID, BigInteger>();
    var creditDelta = new HashMap<UUID, BigInteger>();
    for (var line : journal.lines()) {
      (line.side() == Journal.Side.DEBIT ? debitDelta : creditDelta)
          .merge(line.account(), line.minor(), BigInteger::add);
    }
    debitDelta.replaceAll((id, delta) -> debitTotals.getOrDefault(id, BigInteger.ZERO).add(delta));
    creditDelta.replaceAll(
        (id, delta) -> creditTotals.getOrDefault(id, BigInteger.ZERO).add(delta));
    if (java.util.stream.Stream.concat(debitDelta.values().stream(), creditDelta.values().stream())
        .anyMatch(n -> n.compareTo(Money.MAX) > 0))
      throw new IllegalArgumentException("BALANCE_BOUND");
    debitTotals.putAll(debitDelta);
    creditTotals.putAll(creditDelta);
  }

  public Map<UUID, BigInteger> balances() {
    var result = new HashMap<>(debitTotals);
    creditTotals.forEach((id, value) -> result.merge(id, value.negate(), BigInteger::add));
    return Map.copyOf(result);
  }

  public BigInteger displayed(UUID id, Journal.Side normal) {
    return balances()
        .getOrDefault(id, BigInteger.ZERO)
        .multiply(normal == Journal.Side.DEBIT ? BigInteger.ONE : BigInteger.ONE.negate());
  }
}
