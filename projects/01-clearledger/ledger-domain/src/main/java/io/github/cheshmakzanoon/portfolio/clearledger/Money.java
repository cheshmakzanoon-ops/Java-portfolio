package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

public record Money(String currency, BigInteger minor) {
  public static final Map<String, Integer> SCALES = Map.of("CAD", 2, "USD", 2, "JPY", 0);
  public static final BigInteger MAX = new BigInteger("999999999999999999999999999999");

  public Money {
    if (currency == null || !SCALES.containsKey(currency))
      throw new IllegalArgumentException("CURRENCY");
    if (minor == null || minor.abs().compareTo(MAX) > 0)
      throw new IllegalArgumentException("AMOUNT_BOUND");
  }

  public static Money parse(String currency, String decimal) {
    if (currency == null
        || decimal == null
        || decimal.length() > 64
        || !decimal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        || !SCALES.containsKey(currency)) throw new IllegalArgumentException("DECIMAL");
    var d = new BigDecimal(decimal);
    if (d.scale() > SCALES.get(currency)) throw new IllegalArgumentException("SCALE");
    return new Money(currency, d.movePointRight(SCALES.get(currency)).toBigIntegerExact());
  }

  public String decimal() {
    return new BigDecimal(minor, SCALES.get(currency)).toPlainString();
  }
}
