package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkloadClientRetryTest {
  @Test
  void cleanupWaitsForSamplerExitAndPreservesCancellation() throws Exception {
    var writers = Executors.newSingleThreadExecutor();
    var sampler = Executors.newSingleThreadScheduledExecutor();
    var closer = Executors.newSingleThreadExecutor();
    var started = new CountDownLatch(1);
    var canceled = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    sampler.execute(
        () -> {
          started.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException expected) {
            canceled.countDown();
            try {
              release.await();
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
            }
          }
        });
    try {
      assertTrue(started.await(5, TimeUnit.SECONDS));
      var finished =
          closer.submit(
              () -> {
                Thread.currentThread().interrupt();
                Workload.stopExecutors(writers, sampler);
                return Thread.currentThread().isInterrupted();
              });
      assertTrue(canceled.await(5, TimeUnit.SECONDS));
      assertFalse(finished.isDone(), "cleanup must wait for the sampler's finally path");
      release.countDown();
      assertTrue(finished.get(5, TimeUnit.SECONDS));
      assertTrue(writers.isTerminated());
      assertTrue(sampler.isTerminated());
    } finally {
      release.countDown();
      writers.shutdownNow();
      sampler.shutdownNow();
      closer.shutdownNow();
      assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void sameCapturedRequestRecoversWithBoundedBackoff() {
    var outcomes = new Workload.ClientOutcomes();
    var calls = new AtomicInteger();
    var pauses = new ArrayList<Long>();
    var receipt = new Object();
    assertSame(
        receipt,
        Workload.retryClient(
            () -> {
              if (calls.incrementAndGet() < 3)
                throw new LedgerStore.LedgerException("SERIALIZATION", true, null);
              return receipt;
            },
            outcomes,
            pauses::add));
    assertEquals(3, calls.get());
    assertEquals(2, outcomes.temporaryResults.get());
    assertEquals(2, outcomes.retries.get());
    assertEquals(0, outcomes.exhaustions.get());
    assertEquals(2, pauses.size());
    assertTrue(pauses.get(0) >= 100 && pauses.get(0) < 200);
    assertTrue(pauses.get(1) >= 200 && pauses.get(1) < 300);
  }

  @Test
  void exhaustedTemporaryResponsesRemainFailuresAfterSixCalls() {
    var outcomes = new Workload.ClientOutcomes();
    var calls = new AtomicInteger();
    var pauses = new ArrayList<Long>();
    var failure = new LedgerStore.LedgerException("SERIALIZATION", true, null);
    assertSame(
        failure,
        assertThrows(
            LedgerStore.LedgerException.class,
            () ->
                Workload.retryClient(
                    () -> {
                      calls.incrementAndGet();
                      throw failure;
                    },
                    outcomes,
                    pauses::add)));
    assertEquals(6, calls.get());
    assertEquals(6, outcomes.temporaryResults.get());
    assertEquals(5, outcomes.retries.get());
    assertEquals(1, outcomes.exhaustions.get());
    assertEquals(5, pauses.size());
    assertTrue(pauses.getLast() >= 1600 && pauses.getLast() < 1700);
  }

  @Test
  void permanentRejectionIsNeverRetried() {
    var outcomes = new Workload.ClientOutcomes();
    var failure = new LedgerStore.LedgerException("CLOSED_PERIOD", false, null);
    assertSame(
        failure,
        assertThrows(
            LedgerStore.LedgerException.class,
            () ->
                Workload.retryClient(
                    () -> {
                      throw failure;
                    },
                    outcomes,
                    millis -> fail("Permanent rejection must not back off"))));
    assertEquals(0, outcomes.temporaryResults.get());
    assertEquals(0, outcomes.retries.get());
    assertEquals(0, outcomes.exhaustions.get());
  }

  @Test
  void cancellationInterruptsBackoffAndPreservesThreadInterrupt() {
    var outcomes = new Workload.ClientOutcomes();
    try {
      var failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  Workload.retryClient(
                      () -> {
                        throw new LedgerStore.LedgerException("SERIALIZATION", true, null);
                      },
                      outcomes,
                      millis -> {
                        throw new InterruptedException("cancelled");
                      }));
      assertEquals("CLIENT_RETRY_INTERRUPTED", failure.getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, outcomes.temporaryResults.get());
      assertEquals(0, outcomes.retries.get());
      assertEquals(0, outcomes.exhaustions.get());
    } finally {
      Thread.interrupted();
    }
  }
}
