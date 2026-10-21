package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import java.util.*;
import net.jqwik.api.*;

class LatticePropertiesTest {
  @Provide
  Arbitrary<Taint> taints() {
    return Arbitraries.integers()
        .between(0, 15)
        .map(n -> new Taint(new TreeMap<>(Map.of("a", n & 7, "P:0", (n + 3) & 7)), (n & 8) != 0));
  }

  @Property(tries = 300, seed = "20261017")
  void semilattice(
      @ForAll("taints") Taint a, @ForAll("taints") Taint b, @ForAll("taints") Taint c) {
    assertEquals(a, a.join(a));
    assertEquals(a.join(b), b.join(a));
    assertEquals(a.join(b).join(c), a.join(b.join(c)));
    assertEquals(a, a.join(Taint.BOTTOM));
    assertTrue(a.lessOrEqual(a.join(b)));
  }

  @Property(tries = 300, seed = "20261018")
  void sanitizationMonotone(@ForAll("taints") Taint a, @ForAll("taints") Taint b) {
    Taint upper = a.join(b);
    for (int mask : new int[] {1, 2, 4})
      assertTrue(a.sanitize(mask).lessOrEqual(upper.sanitize(mask)));
  }

  @Property(tries = 300, seed = "20261019")
  void substitutionMonotone(@ForAll("taints") Taint a, @ForAll("taints") Taint b) {
    Taint symbolic = Taint.source("P:0");
    assertTrue(
        symbolic.substitute(List.of(a)).lessOrEqual(symbolic.substitute(List.of(a.join(b)))));
    assertTrue(
        a.substitute(List.of(Taint.source("x")))
            .lessOrEqual(a.join(b).substitute(List.of(Taint.source("x")))));
  }

  @Example
  void unknownDistinctFromClean() {
    assertNotEquals(Taint.BOTTOM, Taint.unknownValue());
    assertTrue(Taint.source("P:2").substitute(List.of()).unknown());
    assertFalse(Taint.source("P:0").hasConcrete(1));
    assertTrue(Taint.source("source").hasConcrete(1));
  }
}
