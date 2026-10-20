package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.time.Duration;

/** Bounded owner-thread cleanup that does not abandon cleanup when its caller is interrupted. */
final class ThreadTermination {
  private ThreadTermination() {}

  static void await(Thread thread, Duration budget) {
    if (thread == Thread.currentThread()) throw new IllegalStateException("CANNOT_JOIN_OWNER");
    long deadline = System.nanoTime() + budget.toNanos();
    boolean interrupted = false;
    try {
      while (thread.isAlive()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
          throw new IllegalStateException("OWNER_DID_NOT_STOP: " + thread.getName());
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
        } catch (InterruptedException cancellation) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
