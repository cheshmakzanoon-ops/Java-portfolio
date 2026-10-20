package io.github.cheshmakzanoon.portfolio.routeweaver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OracleConcurrencyTest {
  @Test
  void sharedOracleKeepsIndependentLeafBudgetsAndOptimaAcrossConcurrentCalls() throws Exception {
    ExactOracle shared = new ExactOracle();
    try (ExecutorService threads = Executors.newFixedThreadPool(4)) {
      CyclicBarrier start = new CyclicBarrier(4);
      List<Future<ExactOracle.Result>> results = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        int selected = index;
        results.add(
            threads.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  return shared.solve(
                      Fixtures.problem(3, 2, 42 + selected, "easy"), selected == 0 ? 1 : 10000);
                }));
      }
      assertFalse(results.getFirst().get(10, TimeUnit.SECONDS).complete());
      assertNull(results.getFirst().get().optimum());
      for (int index = 1; index < 4; index++) {
        var actual = results.get(index).get(10, TimeUnit.SECONDS);
        var independent =
            new ExactOracle().solve(Fixtures.problem(3, 2, 42 + index, "easy"), 10000);
        assertTrue(actual.complete());
        assertEquals(independent.leaves(), actual.leaves());
        assertEquals(independent.optimum().score(), actual.optimum().score());
      }
    }
  }
}
