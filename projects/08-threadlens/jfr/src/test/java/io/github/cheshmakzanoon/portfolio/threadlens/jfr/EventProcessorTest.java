package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.analysis.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class EventProcessorTest {
  @Test
  void payloadAccountingRejectsOneByteBelowTheDeclaredConservativeWeight() {
    NormalizedEvent event =
        new NormalizedEvent(
            NormalizedEvent.Kind.SAMPLE, 1, 1, "s".repeat(100), List.of("f".repeat(100)));
    assertFalse(new EventQueue(2, 623).offer(event));
    EventQueue exact = new EventQueue(2, 624);
    assertTrue(exact.offer(event));
    assertEquals(624, exact.retainedBytes());
    assertEquals(624, exact.peakBytes());
    assertFalse(exact.offer(event));
    assertEquals(event, exact.poll());
    assertEquals(0, exact.retainedBytes());
  }

  @Test
  void pendingPayloadAndDropCountersRemainVisibleUntilDrainAndTaintCoverage() {
    EventProcessor processor = new EventProcessor(2);
    assertTrue(processor.publish(SAMPLE));
    assertTrue(processor.publish(SAMPLE));
    assertFalse(processor.publish(SAMPLE));
    assertEquals(2, processor.pending());
    assertTrue(processor.retainedBytes() > 0);
    assertEquals(1, processor.drops());
    processor.close();
    assertEquals(2, processor.profile().sampleCount());
    assertTrue(
        processor.profile().windows().values().stream()
            .allMatch(WindowAccumulator.Window::incomplete));
  }

  @Test
  void healthyCoverageCanBeExplicitlyMarkedIncompleteAndConfigurationLimitsAreInclusive() {
    EventProcessor processor = new EventProcessor(2, 5_000_000_000L, 5000);
    assertTrue(processor.publish(SAMPLE));
    processor.close();
    assertTrue(
        processor.profile(false).windows().values().stream()
            .noneMatch(WindowAccumulator.Window::incomplete));
    assertTrue(
        processor.profile(true).windows().values().stream()
            .allMatch(WindowAccumulator.Window::incomplete));
    new EventProcessor(2, 0, 1).close();
    for (long[] limits : new long[][] {{-1, 1}, {5_000_000_001L, 1}, {0, 0}, {0, 5001}})
      assertThrows(
          IllegalArgumentException.class, () -> new EventProcessor(2, limits[0], limits[1]));
  }

  @Test
  void diskBudgetChecksAreScheduledByMonotonicTimeInsteadOfEventVolume() throws Exception {
    EventProcessor processor = new EventProcessor(1024, 2_000_000_000L, 3000, () -> 10);
    AtomicInteger checked = new AtomicInteger();
    CountDownLatch observed = new CountDownLatch(1000);
    processor.observer(event -> observed.countDown());
    processor.start(checked::incrementAndGet);
    try {
      for (int i = 0; i < 1000; i++) assertTrue(processor.publish(SAMPLE));
      assertTrue(observed.await(2, TimeUnit.SECONDS));
    } finally {
      processor.close();
    }
    assertEquals(1, checked.get());
    assertEquals(1000, processor.profile().sampleCount());
  }

  private static final NormalizedEvent SAMPLE =
      new NormalizedEvent(NormalizedEvent.Kind.SAMPLE, 1, 1, "sample", List.of("frame"));

  @Test
  void sealingConservesAcceptedSlotsAndPayloadAndRejectsNewReservations() {
    EventQueue queue = new EventQueue(3, 1024);
    assertTrue(queue.offer(SAMPLE));
    assertTrue(queue.offer(SAMPLE));
    assertEquals(2, queue.pending());
    assertTrue(queue.retainedBytes() > 0);
    assertEquals(2, queue.seal());
    assertEquals(2, queue.seal());
    assertFalse(queue.offer(SAMPLE));
    assertEquals(0, queue.drops());
    assertEquals(SAMPLE, queue.poll());
    assertEquals(SAMPLE, queue.poll());
    assertNull(queue.poll());
    assertEquals(0, queue.pending());
    assertEquals(0, queue.retainedBytes());
    assertFalse(queue.offer(SAMPLE));
  }

  @Test
  void closeDrainsBeforeStartAndAdmissionCannotBeReopened() {
    EventProcessor processor = new EventProcessor(2);
    assertTrue(processor.publish(SAMPLE));
    assertThrows(IllegalStateException.class, processor::profile);
    assertThrows(NullPointerException.class, () -> processor.observer(null));
    processor.close();
    processor.close();
    assertEquals(1, processor.profile().sampleCount());
    assertEquals(0, processor.retainedBytes());
    assertFalse(processor.publish(SAMPLE));
    assertThrows(IllegalStateException.class, () -> processor.start(() -> {}));
  }

  @Test
  void closeSealsAdmissionAndDrainsAlreadyAcceptedEventsAfterTheObserverReturns() throws Exception {
    EventProcessor processor = new EventProcessor(8);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger observed = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread closer = null;
    try {
      processor.observer(
          event -> {
            observed.incrementAndGet();
            entered.countDown();
            await(release);
          });
      processor.start(() -> {});
      assertThrows(IllegalStateException.class, () -> processor.start(() -> {}));
      assertTrue(processor.publish(SAMPLE));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 5; i++) assertTrue(processor.publish(SAMPLE));
      closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      processor.close();
                    } catch (Throwable error) {
                      failure.set(error);
                    }
                  });
      awaitCloseBarrier(closer);
      assertFalse(processor.publish(SAMPLE));
      release.countDown();
      closer.join(2000);
      assertFalse(closer.isAlive());
      assertNull(failure.get());
      assertEquals(6, processor.profile().sampleCount());
      assertEquals(1, observed.get());
      assertEquals(0, processor.discarded());
      assertEquals(0, processor.pending());
      assertEquals(0, processor.retainedBytes());
    } finally {
      release.countDown();
      if (closer != null) closer.join(2000);
      processor.close();
    }
  }

  @Test
  void expiredDrainCountsTheAcceptedTailAndMarksRetainedWindowsIncomplete() throws Exception {
    EventProcessor processor = new EventProcessor(8, 0, 1000, () -> 0);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Thread closer = null;
    try {
      processor.observer(
          event -> {
            entered.countDown();
            await(release);
          });
      processor.start(() -> {});
      assertTrue(processor.publish(SAMPLE));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 5; i++) assertTrue(processor.publish(SAMPLE));
      closer = Thread.ofPlatform().start(processor::close);
      awaitCloseBarrier(closer);
      assertFalse(processor.publish(SAMPLE));
      release.countDown();
      closer.join(2000);
      assertFalse(closer.isAlive());
      assertEquals(5, processor.discarded());
      assertEquals(1, processor.profile().sampleCount());
      assertTrue(
          processor.profile().windows().values().stream()
              .allMatch(WindowAccumulator.Window::incomplete));
    } finally {
      release.countDown();
      if (closer != null) closer.join(2000);
      processor.close();
    }
  }

  @Test
  void stalledObserverCannotProduceASnapshotOrClaimSuccessfulShutdown() throws Exception {
    EventProcessor processor = new EventProcessor(2, 0, 50);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      processor.observer(
          event -> {
            entered.countDown();
            await(release);
          });
      processor.start(() -> {});
      assertTrue(processor.publish(SAMPLE));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(
          "analysis consumer did not terminate",
          assertThrows(IllegalStateException.class, processor::close).getMessage());
      assertThrows(IllegalStateException.class, processor::profile);
      assertFalse(processor.publish(SAMPLE));
      release.countDown();
    } finally {
      release.countDown();
      processor.close();
    }
    assertEquals(1, processor.failures());
    assertTrue(
        processor.profile().windows().values().stream()
            .allMatch(WindowAccumulator.Window::incomplete));
  }

  @Test
  void interruptedShutdownRestoresInterruptAndCanBeRetried() throws Exception {
    EventProcessor processor = new EventProcessor(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try {
      processor.observer(
          event -> {
            entered.countDown();
            await(release);
          });
      processor.start(() -> {});
      assertTrue(processor.publish(SAMPLE));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Thread.currentThread().interrupt();
      assertThrows(IllegalStateException.class, processor::close);
      assertTrue(Thread.interrupted());
    } finally {
      Thread.interrupted();
      release.countDown();
      processor.close();
    }
  }

  @Test
  void recoverableLinkageFailureDoesNotEscapeTheConsumerAndBudgetFailureSealsIt() throws Exception {
    EventProcessor processor = new EventProcessor(2);
    CountDownLatch checked = new CountDownLatch(1);
    processor.observer(
        event -> {
          throw new NoClassDefFoundError("fixture");
        });
    assertTrue(processor.publish(SAMPLE));
    assertTrue(processor.publish(SAMPLE));
    processor.start(
        () -> {
          checked.countDown();
          throw new IllegalStateException("budget unavailable");
        });
    assertTrue(checked.await(2, TimeUnit.SECONDS));
    processor.close();
    assertEquals(2, processor.failures());
    assertEquals(1, processor.discarded());
    assertEquals(1, processor.profile().sampleCount());
    assertFalse(processor.publish(SAMPLE));
    assertTrue(processor.peakBytes() > 0);
    assertTrue(
        processor.profile(true).windows().values().stream()
            .allMatch(WindowAccumulator.Window::incomplete));
  }

  private static void awaitCloseBarrier(Thread closer) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (closer.isAlive()
        && closer.getState() != Thread.State.TIMED_WAITING
        && System.nanoTime() < deadline) Thread.onSpinWait();
    assertEquals(Thread.State.TIMED_WAITING, closer.getState());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
