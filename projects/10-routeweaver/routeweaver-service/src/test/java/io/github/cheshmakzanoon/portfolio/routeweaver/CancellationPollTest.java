package io.github.cheshmakzanoon.portfolio.routeweaver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CancellationPollTest {
  @Test
  void hotLoopBoundsDatabaseReadsAndCancellationLatchesAtNextPoll() {
    AtomicLong now = new AtomicLong();
    AtomicInteger reads = new AtomicInteger();
    AtomicBoolean cancelled = new AtomicBoolean();
    CancellationPoll poll =
        new CancellationPoll(
            () -> {
              reads.incrementAndGet();
              return cancelled.get();
            },
            now::get);
    assertFalse(poll.getAsBoolean());
    cancelled.set(true);
    for (int i = 0; i < 10000; i++) {
      now.set(i * 9999L);
      assertFalse(poll.getAsBoolean());
    }
    assertEquals(1, reads.get(), "CPU candidate count must not become DB transaction count");
    now.set(CancellationPoll.INTERVAL_NANOS);
    assertTrue(poll.getAsBoolean());
    assertEquals(2, reads.get());
    cancelled.set(false);
    now.addAndGet(CancellationPoll.INTERVAL_NANOS);
    assertTrue(poll.getAsBoolean(), "a cancelled generation must never resume");
    assertEquals(2, reads.get());
  }

  @Test
  void monotonicClockMayHaveNegativeOriginAndWrapAround() {
    AtomicLong now = new AtomicLong(Long.MAX_VALUE - 50_000_000L);
    AtomicInteger reads = new AtomicInteger();
    CancellationPoll poll =
        new CancellationPoll(
            () -> {
              reads.incrementAndGet();
              return false;
            },
            now::get);
    assertFalse(poll.getAsBoolean());
    now.addAndGet(CancellationPoll.INTERVAL_NANOS - 1);
    assertFalse(poll.getAsBoolean());
    assertEquals(1, reads.get());
    now.incrementAndGet();
    assertFalse(poll.getAsBoolean());
    assertEquals(2, reads.get());
    CancellationPoll negative = new CancellationPoll(() -> true, () -> -1234);
    assertTrue(
        negative.getAsBoolean(), "first read is immediate even at a negative nanoTime origin");
  }
}
