package io.github.cheshmakzanoon.portfolio.threadlens.probe;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The lock-free shutdown protocol must not lose any accepted ring reservation. */
class TimingProbeQuiescenceTest {
  @Test
  void concurrentQuiescenceConservesEveryReservedEvent() throws Exception {
    for (int iteration = 0; iteration < 24; iteration++) {
      TimingProbe.injectFault(false);
      TimingBuffer buffer = new TimingBuffer(8192);
      TimingProbe.configure(buffer);
      CountDownLatch begin = new CountDownLatch(1), firstPublished = new CountDownLatch(8);
      List<Thread> workers = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        int method = i;
        workers.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        begin.await();
                        for (int n = 0; n < 1500; n++) {
                          long started = TimingProbe.enter();
                          TimingProbe.exit(method, started, (n & 1) != 0);
                          if (n == 0) firstPublished.countDown();
                        }
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
      }
      begin.countDown();
      assertTrue(firstPublished.await(5, TimeUnit.SECONDS), "all workers must publish before disable");
      assertTrue(TimingProbe.disableAndAwait(2_000_000_000L));
      for (Thread thread : workers) {
        thread.join(2000);
        assertFalse(thread.isAlive(), "late entrants must terminate");
      }
      assertEquals(0, TimingProbe.inFlightExits());
      long reserved = buffer.seal();
      long[] drained = {0};
      while (buffer.pollTo(
          (method, started, elapsed, failed) -> {
            assertTrue(method >= 0 && method < 8);
            assertTrue(elapsed >= 0);
            drained[0]++;
          })) {}
      assertEquals(reserved, drained[0], "every reservation must be published before seal");
      assertTrue(drained[0] >= 8, "conservation must not pass without admitted events");
      assertEquals(0, buffer.pending());
      assertTrue(drained[0] <= 8 * 1500);
    }
  }

  @Test
  void blockedTrackedPublisherPreventsFalseSuccessfulShutdown() throws Exception {
    TimingProbe.injectFault(false);
    TimingBuffer buffer = new TimingBuffer(16);
    TimingProbe.configure(buffer);
    CountDownLatch admitted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TimingProbe.injectFaultAction(
        () -> {
          admitted.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
    Thread worker =
        Thread.ofPlatform().start(() -> TimingProbe.exit(7, TimingProbe.enter(), false));
    try {
      assertTrue(admitted.await(2, TimeUnit.SECONDS));
      assertTrue(TimingProbe.inFlightExits() >= 1);
      assertFalse(TimingProbe.disableAndAwait(0));
      release.countDown();
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertEquals(0, TimingProbe.inFlightExits());
      assertTrue(TimingProbe.disableAndAwait(2_000_000_000L));
      assertEquals(1, buffer.seal());
      assertEquals(7, buffer.poll().methodId());
      assertEquals(0, buffer.pending());
    } finally {
      release.countDown();
      worker.join(2000);
      TimingProbe.injectFault(false);
      TimingProbe.disable();
    }
  }

  @Test
  void boundedThreadRegistryFallsBackWithoutLosingCompletedEntries() throws Exception {
    TimingProbe.injectFault(false);
    int count = 2100;
    TimingBuffer buffer = new TimingBuffer(4096);
    TimingProbe.configure(buffer);
    for (int i = 0; i < count; i++) {
      int method = i;
      Thread worker =
          Thread.ofPlatform().start(() -> TimingProbe.exit(method, TimingProbe.enter(), false));
      worker.join(2000);
      assertFalse(worker.isAlive());
    }
    assertTrue(TimingProbe.disableAndAwait(2_000_000_000L));
    assertEquals(count, buffer.seal());
    int drained = 0;
    TimingBuffer.Timing record;
    while ((record = buffer.poll()) != null) {
      assertEquals(drained++, record.methodId());
    }
    assertEquals(count, drained);
    assertEquals(0, TimingProbe.inFlightExits());
    assertEquals(0, buffer.pending());
    Field tracked = TimingProbe.class.getDeclaredField("trackedCount");
    tracked.setAccessible(true);
    assertTrue(((AtomicInteger) tracked.get(null)).get() <= 2048);
  }

  @Test
  void faultedPublisherClearsGuardAndHostRemainsAlive() throws Exception {
    TimingProbe.injectFault(false);
    TimingBuffer buffer = new TimingBuffer(16);
    TimingProbe.configure(buffer);
    long initial = TimingProbe.failures();
    TimingProbe.injectFaultAction(
        () -> {
          throw new IllegalStateException("injected");
        });
    TimingProbe.exit(8, TimingProbe.enter(), true);
    assertEquals(initial + 1, TimingProbe.failures());
    assertFalse(TimingProbe.enabled());
    assertEquals(0, TimingProbe.inFlightExits());
    assertEquals(0, buffer.seal());
    TimingProbe.injectFault(false);
    TimingProbe.configure(new TimingBuffer(16));
    assertTrue(TimingProbe.disableAndAwait(2_000_000_000L));
  }
}
