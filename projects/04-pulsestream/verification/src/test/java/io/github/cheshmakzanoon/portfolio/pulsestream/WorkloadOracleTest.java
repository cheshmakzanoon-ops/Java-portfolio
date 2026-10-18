package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkloadOracleTest {
  @Test
  void directEnumerationHasHalfOpenEdgesAndSlidingOverlap() {
    var oracle = new WorkloadOracle(2);
    oracle.measurement(0, 0, 10);
    oracle.measurement(0, 59_999, 20);
    oracle.measurement(0, 60_000, 5);
    oracle.measurement(1, 0, -4);
    assertEquals(new WorkloadOracle.Total(2, 30, 10, 20), oracle.expected(0, "TUMBLING", 0));
    assertEquals(new WorkloadOracle.Total(1, 5, 5, 5), oracle.expected(0, "TUMBLING", 60_000));
    assertEquals(new WorkloadOracle.Total(2, 30, 10, 20), oracle.expected(0, "SLIDING", -240_000));
    assertEquals(new WorkloadOracle.Total(3, 35, 5, 20), oracle.expected(0, "SLIDING", -180_000));
    assertEquals(new WorkloadOracle.Total(1, -4, -4, -4), oracle.expected(1, "SLIDING", 0));
    assertThrows(IllegalArgumentException.class, () -> oracle.measurement(0, 120_000, 1));
    assertThrows(IllegalArgumentException.class, () -> oracle.expected(0, "SLIDING", 1));
  }
}
