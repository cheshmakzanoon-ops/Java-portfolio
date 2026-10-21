package io.github.cheshmakzanoon.portfolio.threadlens.probe;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * No argument/object capture, locks, I/O, or per-call allocation. Fatal VM failures are outside
 * scope. Long-lived threads use per-thread volatile quiescence marks instead of a contended
 * global read-modify-write on every exit. A bounded registry falls back to the original atomic
 * path when many short-lived threads participate.
 */
public final class TimingProbe {
  private static volatile boolean enabled;
  private static volatile TimingBuffer buffer = new TimingBuffer(65_536);

  private static final int MAX_TRACKED_GUARDS = 2_048;
  private static final ConcurrentLinkedQueue<Guard> TRACKED = new ConcurrentLinkedQueue<>();
  private static final AtomicInteger trackedCount = new AtomicInteger();
  private static final AtomicInteger fallbackInFlight = new AtomicInteger();

  private static final class Guard {
    boolean active;
    final boolean tracked;
    volatile boolean publishing;

    Guard(boolean tracked) {
      this.tracked = tracked;
    }
  }

  private static Guard registerGuard() {
    int previous =
        trackedCount.getAndUpdate(n -> n < MAX_TRACKED_GUARDS ? n + 1 : n);
    Guard guard = new Guard(previous < MAX_TRACKED_GUARDS);
    if (guard.tracked) TRACKED.add(guard);
    return guard;
  }

  // One state per participating thread; no per-invocation allocation or unbounded registry.
  private static final ThreadLocal<Guard> ACTIVE = ThreadLocal.withInitial(TimingProbe::registerGuard);
  private static volatile boolean injectFault;
  private static final AtomicLong failures = new AtomicLong();
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
    // The per-thread volatile mark precedes the second enabled read. Disable must either
    // observe the mark or prevent publication; excess threads retain the atomic fallback.
    if (guard.tracked) guard.publishing = true;
    else fallbackInFlight.incrementAndGet();
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
      if (guard.tracked) guard.publishing = false;
      else fallbackInFlight.decrementAndGet();
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
    int count = fallbackInFlight.get();
    for (Guard guard : TRACKED) if (guard.publishing) count++;
    return count;
  }

  /**
   * Late entrants recheck enabled; admitted emitters either finish or are explicitly unfinished.
   */
  public static boolean disableAndAwait(long waitNanos) {
    if (waitNanos < 0 || waitNanos > 5_000_000_000L)
      throw new IllegalArgumentException("quiescence wait bounds");
    enabled = false;
    long start = System.nanoTime();
    while (inFlightExits() != 0) {
      if (System.nanoTime() - start >= waitNanos) return false;
      LockSupport.parkNanos(Math.min(waitNanos, 100_000));
    }
    return true;
  }
}
