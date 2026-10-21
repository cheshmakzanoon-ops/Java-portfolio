package io.github.cheshmakzanoon.portfolio.backtrace.agent;

import io.github.cheshmakzanoon.portfolio.backtrace.probe.TimingProbe;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fail-open startup entry point; TimingSession owns every installed resource. */
public final class BacktracePremain {
  private static final AtomicBoolean DIAGNOSTIC = new AtomicBoolean();
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  private BacktracePremain() {}

  public static void premain(String argument, Instrumentation instrumentation) {
    if (!STARTED.compareAndSet(false, true)) {
      diagnostic("duplicate startup ignored; the first configuration owns this JVM");
      return;
    }
    try {
      TimingSession.start(AgentConfig.read(argument), instrumentation);
    } catch (Exception | LinkageError recoverable) {
      TimingProbe.disable();
      diagnostic("initialization disabled; host may continue");
    }
  }

  static void diagnostic(String text) {
    if (DIAGNOSTIC.compareAndSet(false, true)) System.err.println("Backtrace: " + text);
  }
}
