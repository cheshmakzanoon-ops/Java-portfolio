package io.github.cheshmakzanoon.portfolio.threadlens.probe;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * No argument/object capture, locks, I/O, or per-call allocation. Fatal VM failures are outside
 * scope.
 */
public final class TimingProbe {
  private static volatile boolean enabled;
  private static volatile TimingBuffer buffer = new TimingBuffer(65_536);

  private static final class Guard {
    boolean active;
  }

  // One state per participating thread; no global thread registry or per-invocation state.
  private static final ThreadLocal<Guard> ACTIVE = ThreadLocal.withInitial(Guard::new);
  private static volatile boolean injectFault;
  private static final AtomicLong failures = new AtomicLong();
  private static final AtomicInteger inFlight = new AtomicInteger();
  private static volatile Runnable faultAction;

  private TimingProbe() {}

  public static void configure(TimingBuffer sink) {
    buffer = sink;
    enabled = true;
  }

  public static long enter() {
    return enabled ? System.nanoTime() : 0;
  }

  public static void exit(int method, long started, boolean exceptional) {
    if (!enabled || started == 0) return;
    Guard guard = ACTIVE.get();
    if (guard.active) return;
    inFlight.incrementAndGet();
    try {
      // A late entrant that observed enabled before shutdown cannot publish after disable.
      if (!enabled) return;
      guard.active = true;
      try {
        if (injectFault) {
          Runnable action = faultAction;
          if (action == null) throw new IllegalStateException("injected observer failure");
          action.run();
        }
        buffer.offer(method, started, Math.max(0, System.nanoTime() - started), exceptional);
      } catch (RuntimeException | LinkageError recoverable) {
        failures.incrementAndGet();
        enabled = false;
      } finally {
        guard.active = false;
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  public static void disable() {
    enabled = false;
  }

  public static boolean enabled() {
    return enabled;
  }

  public static long failures() {
    return failures.get();
  }

  public static void injectFault(boolean fault) {
    faultAction = null;
    injectFault = fault;
  }

  /** Bounded explicit fault-fixture seam; the ordinary branch captures no application objects. */
  public static void injectFaultAction(Runnable action) {
    faultAction = java.util.Objects.requireNonNull(action);
    injectFault = true;
  }

  public static int inFlightExits() {
    return inFlight.get();
  }

  /**
   * Late entrants recheck enabled; admitted emitters either finish or are explicitly unfinished.
   */
  public static boolean disableAndAwait(long waitNanos) {
    if (waitNanos < 0 || waitNanos > 5_000_000_000L)
      throw new IllegalArgumentException("quiescence wait bounds");
    enabled = false;
    long start = System.nanoTime();
    while (inFlight.get() != 0) {
      if (System.nanoTime() - start >= waitNanos) return false;
      LockSupport.parkNanos(Math.min(waitNanos, 100_000));
    }
    return true;
  }
}
