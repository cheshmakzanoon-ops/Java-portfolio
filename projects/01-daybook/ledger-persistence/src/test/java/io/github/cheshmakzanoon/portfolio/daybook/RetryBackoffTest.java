package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {
  @Test
  void everyAllowedRetryHasPositiveBoundedDelayAndTotalBudgetBelowTwoSeconds() throws Exception {
    var minimum = new ArrayList<Long>();
    var maximum = new ArrayList<Long>();
    var smallest = new RetryBackoff(bound -> 0, minimum::add);
    var largest = new RetryBackoff(bound -> bound - 1, maximum::add);
    for (int attempt = 0; attempt <= 10; attempt++) {
      smallest.pause(attempt);
      largest.pause(attempt);
    }
    assertEquals(java.util.Collections.nCopies(11, 1L), minimum);
    assertEquals(
        java.util.List.of(4L, 8L, 16L, 32L, 64L, 128L, 256L, 256L, 256L, 256L, 256L), maximum);
    assertTrue(maximum.stream().mapToLong(Long::longValue).sum() < 2000);
  }

  @Test
  void independentInjectedDrawsDesynchronizeThirtyTwoContenders() throws Exception {
    var random = new Random(20261018);
    var cumulative = new HashSet<Long>();
    for (int writer = 0; writer < 32; writer++) {
      long[] delay = {0};
      var backoff = new RetryBackoff(random::nextLong, millis -> delay[0] += millis);
      for (int attempt = 0; attempt < 7; attempt++) backoff.pause(attempt);
      cumulative.add(delay[0]);
    }
    assertTrue(
        cumulative.size() >= 24,
        "A synchronized deterministic schedule must not replace independent draws");
  }

  @Test
  void invalidAttemptsOrSamplerResultsNeverReachTheSleeper() {
    var backoff =
        new RetryBackoff(
            bound -> fail("invalid attempt must not draw"), millis -> fail("must not sleep"));
    assertThrows(IllegalArgumentException.class, () -> backoff.pause(-1));
    assertThrows(IllegalArgumentException.class, () -> backoff.pause(11));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryBackoff(bound -> -1, millis -> fail()).pause(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryBackoff(bound -> bound, millis -> fail()).pause(10));
    assertThrows(NullPointerException.class, () -> new RetryBackoff(null, millis -> {}));
    assertThrows(NullPointerException.class, () -> new RetryBackoff(bound -> 0, null));
  }

  @Test
  void productionSamplerDrawsFreshBoundedDelaysWithoutReplacingThemWithAConstant()
      throws Exception {
    var delays = new ArrayList<Long>();
    var backoff = new RetryBackoff(delays::add);
    for (int i = 0; i < 64; i++) backoff.pause(6);
    assertEquals(64, delays.size());
    assertTrue(delays.stream().allMatch(delay -> delay >= 1 && delay <= 256));
    // An entropy wiring check complements the independently injected deterministic cases.
    assertTrue(new HashSet<>(delays).size() >= 8);
  }

  @Test
  void productionSleeperObservesInterruptionWithoutAnElapsedTimeAssertion() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> new RetryBackoff().pause(0));
    } finally {
      Thread.interrupted();
    }
  }
}
