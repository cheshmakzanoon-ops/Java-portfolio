package io.github.cheshmakzanoon.portfolio.threadlens.probe;

/**
 * No argument/object capture, locks, I/O, or per-call allocation. Fatal VM failures are outside
 * scope.
 */
public final class TimingProbe {
  private static volatile boolean enabled;
  private static volatile TimingBuffer buffer = new TimingBuffer(65_536);
  private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
  private static volatile boolean injectFault;
  private static volatile long failures;

  private TimingProbe() {}

  public static void configure(TimingBuffer sink) {
    buffer = sink;
    enabled = true;
  }

  public static long enter() {
    return enabled ? System.nanoTime() : 0;
  }

  public static void exit(int method, long started, boolean exceptional) {
    if (!enabled || started == 0 || ACTIVE.get()) return;
    ACTIVE.set(Boolean.TRUE);
    try {
      if (injectFault) throw new IllegalStateException("injected observer failure");
      buffer.offer(method, started, Math.max(0, System.nanoTime() - started), exceptional);
    } catch (RuntimeException | LinkageError recoverable) {
      failures++;
      enabled = false;
    } finally {
      ACTIVE.set(Boolean.FALSE);
    }
  }

  public static void disable() {
    enabled = false;
  }

  public static boolean enabled() {
    return enabled;
  }

  public static long failures() {
    return failures;
  }

  public static void injectFault(boolean fault) {
    injectFault = fault;
  }
}
