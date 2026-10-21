package io.github.cheshmakzanoon.portfolio.backtrace.probe;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class PrimitiveDrainTest {
  @Test
  void sealRejectsLateAdmissionButPreservesAlreadyAcceptedRecords() throws Exception {
    var ring = new TimingBuffer(2);
    assertTrue(ring.offer(1, 1, 1, false));
    assertEquals(1, ring.seal());
    assertEquals(1, ring.seal());
    assertFalse(ring.offer(2, 2, 2, false));
    assertEquals(1, ring.pending());
    assertEquals(1, ring.poll().methodId());
    assertEquals(0, ring.pending());
    assertFalse(ring.pollTo((m, s, d, e) -> fail()));
  }

  @Test
  void disabledQuiescenceAndSealingExposePausedProducerWithoutLatePublication() throws Exception {
    var ring = new TimingBuffer(2);
    TimingProbe.configure(ring);
    CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
    TimingProbe.injectFaultAction(
        () -> {
          admitted.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        });
    Thread worker =
        Thread.ofPlatform().start(() -> TimingProbe.exit(1, TimingProbe.enter(), false));
    try {
      assertTrue(admitted.await(2, TimeUnit.SECONDS));
      assertEquals(1, TimingProbe.inFlightExits());
      assertFalse(TimingProbe.disableAndAwait(0));
      assertEquals(0, ring.seal());
      assertNull(ring.poll());
    } finally {
      release.countDown();
      worker.join(2000);
      TimingProbe.injectFault(false);
    }
    assertFalse(worker.isAlive());
    assertEquals(0, TimingProbe.inFlightExits());
    assertEquals(0, ring.pending());
    assertNull(ring.poll());
    assertTrue(TimingProbe.disableAndAwait(0));
    assertThrows(IllegalArgumentException.class, () -> TimingProbe.disableAndAwait(-1));
    assertThrows(IllegalArgumentException.class, () -> TimingProbe.disableAndAwait(5_000_000_001L));
  }

  @Test
  void concurrentRecoverableFailuresAreCountedExactlyAfterAdmission() throws Exception {
    var ring = new TimingBuffer(8);
    TimingProbe.configure(ring);
    int n = 6;
    CountDownLatch admitted = new CountDownLatch(n), release = new CountDownLatch(1);
    TimingProbe.injectFaultAction(
        () -> {
          admitted.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          throw new LinkageError("observer fault fixture");
        });
    long before = TimingProbe.failures();
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++)
      threads.add(Thread.ofPlatform().start(() -> TimingProbe.exit(1, TimingProbe.enter(), false)));
    try {
      assertTrue(admitted.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    for (Thread t : threads) t.join(2000);
    TimingProbe.injectFault(false);
    assertEquals(before + n, TimingProbe.failures());
    assertEquals(0, TimingProbe.inFlightExits());
    assertNull(ring.poll());
  }

  @Test
  void boundedQuiescenceWaitsForAdmittedExitAndPreservesItsAcceptedRecord() throws Exception {
    var ring = new TimingBuffer(2);
    TimingProbe.configure(ring);
    CountDownLatch admitted = new CountDownLatch(1), release = new CountDownLatch(1);
    TimingProbe.injectFaultAction(
        () -> {
          admitted.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
        });
    Thread worker =
        Thread.ofPlatform().start(() -> TimingProbe.exit(1, TimingProbe.enter(), false));
    assertTrue(admitted.await(2, TimeUnit.SECONDS));
    FutureTask<Boolean> close = new FutureTask<>(() -> TimingProbe.disableAndAwait(2_000_000_000L));
    Thread closer = Thread.ofPlatform().start(close);
    try {
      long deadline = System.nanoTime() + 1_000_000_000L;
      while (TimingProbe.enabled() && System.nanoTime() < deadline) Thread.onSpinWait();
      assertFalse(TimingProbe.enabled());
      assertFalse(close.isDone());
      release.countDown();
      assertTrue(close.get(3, TimeUnit.SECONDS));
      worker.join(2000);
      closer.join(2000);
      assertEquals(1, ring.seal());
      assertEquals(1, ring.poll().methodId());
      assertEquals(0, ring.pending());
    } finally {
      release.countDown();
      worker.join(2000);
      closer.join(2000);
      TimingProbe.injectFault(false);
    }
  }

  @Test
  void primitiveAndImmutableConsumersShareExactCapacityAndPayloadAcrossReuse() throws Exception {
    for (int capacity : List.of(2, 3, 7, 64, 65536)) {
      var ring = new TimingBuffer(capacity);
      assertEquals(capacity, ring.capacity());
      for (int cycle = 0; cycle < 4; cycle++) {
        for (int i = 0; i < capacity; i++) assertTrue(ring.offer(i, i + 1, i + 2, (i & 1) != 0));
        assertFalse(ring.offer(-1, 0, 0, false));
        for (int i = 0; i < capacity; i++) {
          int id = i;
          if ((i & 1) == 0)
            assertTrue(
                ring.pollTo(
                    (m, s, d, e) -> {
                      assertEquals(id, m);
                      assertEquals(id + 1, s);
                      assertEquals(id + 2, d);
                      assertFalse(e);
                    }));
          else assertEquals(new TimingBuffer.Timing(i, i + 1, i + 2, true), ring.poll());
        }
        assertFalse(ring.pollTo((m, s, d, e) -> fail("empty queue callback")));
        assertEquals(0, ring.pending());
      }
      assertEquals(4, ring.dropped());
    }
  }

  @Test
  void nonPowerOfTwoSlotsRemainDistinct() {
    var ring = new TimingBuffer(3);
    assertTrue(ring.offer(1, 1, 1, false));
    assertTrue(ring.offer(2, 2, 2, false));
    assertTrue(ring.offer(3, 3, 3, false));
    assertEquals(1, ring.poll().methodId());
    assertEquals(2, ring.poll().methodId());
    assertEquals(3, ring.poll().methodId());
  }

  @Test
  void inclusiveMaximumCapacityIsAccepted() {
    assertEquals(1_048_576, new TimingBuffer(1_048_576).capacity());
  }

  @Test
  void callbackFailureConsumesExactlyOnceAndReclaimsBeforeInvokingObserver() throws Exception {
    var ring = new TimingBuffer(2);
    assertTrue(ring.offer(1, 2, 3, false));
    assertTrue(ring.offer(2, 3, 4, true));
    var failure = new IOException("observer failure");
    assertSame(
        failure,
        assertThrows(
            IOException.class,
            () ->
                ring.pollTo(
                    (m, s, d, e) -> {
                      assertEquals(1, m);
                      assertTrue(ring.offer(3, 4, 5, false), "observer sees reclaimed slot");
                      throw failure;
                    })));
    assertEquals(2, ring.poll().methodId());
    assertEquals(3, ring.poll().methodId());
    assertNull(ring.poll());
    assertEquals(0, ring.pending());
    assertEquals(0, ring.dropped());
  }

  @Test
  void primitivePublicationConservesConcurrentPlatformAndVirtualProducers() throws Exception {
    var ring = new TimingBuffer(7);
    int producers = 4, each = 10000;
    CountDownLatch done = new CountDownLatch(producers);
    AtomicLong accepted = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int i = 0; i < producers; i++) {
      int producer = i;
      (i % 2 == 0 ? Thread.ofPlatform() : Thread.ofVirtual())
          .start(
              () -> {
                try {
                  for (int n = 0; n < each; n++) {
                    int id = producer * each + n;
                    if (ring.offer(id, id + 1, id * 2L, (id & 1) != 0)) accepted.incrementAndGet();
                  }
                } catch (Throwable t) {
                  failure.set(t);
                } finally {
                  done.countDown();
                }
              });
    }
    BitSet seen = new BitSet(producers * each);
    long[] observed = {0};
    long deadline = System.nanoTime() + 10_000_000_000L;
    while ((done.getCount() > 0 || ring.pending() > 0) && System.nanoTime() < deadline) {
      if (!ring.pollTo(
          (m, s, d, e) -> {
            assertFalse(seen.get(m));
            seen.set(m);
            assertEquals(m + 1, s);
            assertEquals(m * 2L, d);
            assertEquals((m & 1) != 0, e);
            observed[0]++;
          })) Thread.onSpinWait();
    }
    assertTrue(done.await(1, TimeUnit.SECONDS));
    assertNull(failure.get());
    assertEquals(0, ring.pending());
    assertTrue(observed[0] > 0);
    assertEquals(accepted.get(), observed[0]);
    assertEquals(producers * each, observed[0] + ring.dropped());
  }

  @Test
  void guardSuppressesReentryAndFaultCleanupAllowsSubsequentPublication() throws Exception {
    var ring = new TimingBuffer(8);
    TimingProbe.configure(ring);
    var field = TimingProbe.class.getDeclaredField("ACTIVE");
    field.setAccessible(true);
    Object guard = ((ThreadLocal<?>) field.get(null)).get();
    var active = guard.getClass().getDeclaredField("active");
    active.setAccessible(true);
    active.setBoolean(guard, true);
    try {
      TimingProbe.exit(1, TimingProbe.enter(), false);
      assertNull(ring.poll());
    } finally {
      active.setBoolean(guard, false);
    }
    TimingProbe.injectFault(true);
    try {
      TimingProbe.exit(2, TimingProbe.enter(), true);
      assertFalse(TimingProbe.enabled());
      assertFalse(active.getBoolean(guard));
    } finally {
      TimingProbe.injectFault(false);
    }
    TimingProbe.configure(ring);
    TimingProbe.exit(3, TimingProbe.enter(), false);
    assertEquals(3, ring.poll().methodId());
    TimingProbe.disable();
  }
}
