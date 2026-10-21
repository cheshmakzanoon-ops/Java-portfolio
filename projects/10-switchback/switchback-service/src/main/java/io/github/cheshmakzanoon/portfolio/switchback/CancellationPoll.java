package io.github.cheshmakzanoon.portfolio.switchback;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Cache only cooperative cancellation reads; authoritative writes always recheck the DB fence. */
final class CancellationPoll implements BooleanSupplier {
  static final long INTERVAL_NANOS = 100_000_000L;
  private final BooleanSupplier database;
  private final LongSupplier clock;
  private boolean polled;
  private boolean cancelled;
  private long lastPoll;

  CancellationPoll(BooleanSupplier database, LongSupplier clock) {
    this.database = database;
    this.clock = clock;
  }

  @Override
  public synchronized boolean getAsBoolean() {
    if (cancelled) return true;
    long now = clock.getAsLong();
    if (!polled || now - lastPoll >= INTERVAL_NANOS) {
      cancelled = database.getAsBoolean();
      lastPoll = now;
      polled = true;
    }
    return cancelled;
  }
}
