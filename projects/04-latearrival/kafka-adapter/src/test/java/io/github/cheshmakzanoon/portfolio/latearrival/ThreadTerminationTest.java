package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ThreadTerminationTest {
  @Test
  void interruptedCallerStillWaitsForOwnerAndRetainsItsInterrupt() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var owner =
        Thread.ofPlatform()
            .start(
                () -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    try (var closer = Executors.newSingleThreadExecutor()) {
      var closing = new CountDownLatch(1);
      var cleanupThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var result =
          closer.submit(
              () -> {
                cleanupThread.set(Thread.currentThread());
                Thread.currentThread().interrupt();
                closing.countDown();
                ThreadTermination.await(owner, Duration.ofSeconds(3));
                return Thread.currentThread().isInterrupted();
              });
      assertTrue(closing.await(2, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (!waiting(cleanupThread.get()) && !result.isDone() && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertTrue(
          waiting(cleanupThread.get()), () -> "cleanup state: " + cleanupThread.get().getState());
      assertFalse(result.isDone());
      release.countDown();
      assertTrue(result.get(3, TimeUnit.SECONDS));
      assertFalse(owner.isAlive());
    } finally {
      release.countDown();
      owner.join(3000);
    }
  }

  @Test
  void stalledOwnerIsReportedWithoutPretendingItStopped() throws Exception {
    var release = new CountDownLatch(1);
    var owner =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () ->
              assertTrue(
                  assertThrows(
                          IllegalStateException.class,
                          () -> ThreadTermination.await(owner, Duration.ofMillis(20)))
                      .getMessage()
                      .startsWith("OWNER_DID_NOT_STOP")));
      assertTrue(owner.isAlive());
    } finally {
      release.countDown();
      owner.join(3000);
    }
    assertDoesNotThrow(() -> ThreadTermination.await(owner, Duration.ZERO));
  }

  @Test
  void ownerCannotJoinItself() {
    assertEquals(
        "CANNOT_JOIN_OWNER",
        assertThrows(
                IllegalStateException.class,
                () -> ThreadTermination.await(Thread.currentThread(), Duration.ofSeconds(1)))
            .getMessage());
  }

  private static boolean waiting(Thread thread) {
    return thread.getState() == Thread.State.WAITING
        || thread.getState() == Thread.State.TIMED_WAITING;
  }
}
