package io.github.cheshmakzanoon.portfolio.switchback;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CancellationFailureTest {
  @Test
  void watcherFailureStopsTheSolverAndReachesTheOwningThread() {
    var problem = PlanningTest.fixture();
    var baseline = new Heuristics().baseline(problem);
    Thread caller = Thread.currentThread();
    CountDownLatch watcherChecked = new CountDownLatch(1);
    IllegalStateException failure = new IllegalStateException("cancellation store unavailable");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                new TimefoldAdapter()
                    .solveControlled(
                        problem,
                        baseline,
                        new TimefoldAdapter.Settings(42, 30000, 1000, "test-source"),
                        () -> {
                          if (Thread.currentThread() != caller) {
                            watcherChecked.countDown();
                            throw failure;
                          }
                          return false;
                        },
                        ignored -> {},
                        () -> {
                          try {
                            assertTrue(watcherChecked.await(5, TimeUnit.SECONDS));
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                          }
                        })));
  }
}
