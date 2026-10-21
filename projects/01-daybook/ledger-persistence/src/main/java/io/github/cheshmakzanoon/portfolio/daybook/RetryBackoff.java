package io.github.cheshmakzanoon.portfolio.daybook;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Independent bounded jitter for database retries; attempts and transaction semantics are
 * unchanged.
 */
final class RetryBackoff {
  @FunctionalInterface
  interface Pause {
    void sleep(long millis) throws InterruptedException;
  }

  private final LongUnaryOperator sample;
  private final Pause pause;

  RetryBackoff() {
    this(Thread::sleep);
  }

  RetryBackoff(Pause pause) {
    this(bound -> ThreadLocalRandom.current().nextLong(bound), pause);
  }

  RetryBackoff(LongUnaryOperator sample, Pause pause) {
    this.sample = Objects.requireNonNull(sample);
    this.pause = Objects.requireNonNull(pause);
  }

  void pause(int failedAttempt) throws InterruptedException {
    if (failedAttempt < 0 || failedAttempt > 10)
      throw new IllegalArgumentException("RETRY_ATTEMPT_BOUND");
    long window = Math.min(256, 4L << failedAttempt);
    long draw = sample.applyAsLong(window);
    if (draw < 0 || draw >= window) throw new IllegalArgumentException("RETRY_JITTER_BOUND");
    // Uniform [1,window] avoids deterministic collision phases and a hot zero-delay retry loop.
    pause.sleep(1 + draw);
  }
}
