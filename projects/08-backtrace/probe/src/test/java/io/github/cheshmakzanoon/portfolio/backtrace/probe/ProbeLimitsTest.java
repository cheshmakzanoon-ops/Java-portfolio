package io.github.cheshmakzanoon.portfolio.backtrace.probe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ProbeLimitsTest {
  @Test
  void concurrentPrimitivePublicationConservesAcceptedRecordsWithoutTornValues() throws Exception {
    int producers = 4, perProducer = 20000;
    TimingBuffer ring = new TimingBuffer(64);
    CountDownLatch done = new CountDownLatch(producers);
    AtomicLong accepted = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> threads = new ArrayList<>();
    for (int p = 0; p < producers; p++) {
      int producer = p;
      Thread t =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      for (int i = 0; i < perProducer; i++) {
                        int id = producer * perProducer + i;
                        if (ring.offer(id, id, id * 2L, (id & 1) == 1)) accepted.incrementAndGet();
                      }
                    } catch (Throwable problem) {
                      failure.set(problem);
                    } finally {
                      done.countDown();
                    }
                  });
      threads.add(t);
    }
    BitSet seen = new BitSet(producers * perProducer);
    long observed = 0, deadline = System.nanoTime() + 10_000_000_000L;
    while ((done.getCount() > 0 || ring.pending() > 0) && System.nanoTime() < deadline) {
      var e = ring.poll();
      if (e == null) {
        Thread.onSpinWait();
        continue;
      }
      assertFalse(seen.get(e.methodId()), "duplicate publication");
      seen.set(e.methodId());
      assertEquals(e.methodId(), e.startedNanos());
      assertEquals(e.methodId() * 2L, e.durationNanos());
      assertEquals((e.methodId() & 1) == 1, e.exceptional());
      observed++;
    }
    assertTrue(done.await(1, TimeUnit.SECONDS));
    for (Thread thread : threads) thread.join(1000);
    assertNull(failure.get());
    assertEquals(0, ring.pending());
    assertEquals(accepted.get(), observed);
    assertEquals(producers * perProducer, observed + ring.dropped());
    assertTrue(observed > 0);
  }

  @Test
  void configuredLimitsAndFullRegistryAreRealBounds() {
    assertThrows(IllegalArgumentException.class, () -> new TimingBuffer(1));
    assertThrows(IllegalArgumentException.class, () -> new TimingBuffer(1_048_577));
    assertThrows(IllegalArgumentException.class, () -> new MethodRegistry(0));
    assertThrows(IllegalArgumentException.class, () -> new MethodRegistry(10_001));
    var registry = new MethodRegistry(10_000);
    for (int i = 0; i < 10_000; i++)
      assertEquals(i, registry.register("application.Class.method" + i + "()V"));
    assertEquals(-1, registry.register("application.Overflow()V"));
    assertEquals(10_000, registry.snapshot().size());
    assertEquals(1, registry.overflow());
    assertThrows(UnsupportedOperationException.class, () -> registry.snapshot().clear());
  }

  @Test
  void oversizedIdentitiesRemainDistinctAndDisabledProbePublishesNothing() {
    var registry = new MethodRegistry(2);
    assertEquals(0, registry.register("x".repeat(300) + "A"));
    assertEquals(1, registry.register("x".repeat(300) + "B"));
    assertEquals(0, registry.register("x".repeat(300) + "A"));
    assertEquals(256, registry.snapshot().get(0).length());
    assertNotEquals(registry.snapshot().get(0), registry.snapshot().get(1));
    TimingBuffer buffer = new TimingBuffer(2);
    TimingProbe.configure(buffer);
    TimingProbe.exit(1, 0, false);
    assertNull(buffer.poll());
    TimingProbe.disable();
    assertEquals(0, TimingProbe.enter());
    TimingProbe.exit(1, 1, false);
    assertNull(buffer.poll());
  }
}
