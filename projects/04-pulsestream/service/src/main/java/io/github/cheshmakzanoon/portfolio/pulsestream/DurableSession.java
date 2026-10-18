package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * Shared production and recorded-schedule transaction boundary. Call only from its engine owner.
 */
public final class DurableSession implements AutoCloseable {
  private final PostgresStore store;
  private final PostgresStore.Owner owner;
  private final String topicId;
  private Engine.State committed;
  private boolean failed;

  public DurableSession(
      PostgresStore store,
      String run,
      String topicId,
      Engine.Limits limits,
      long now,
      Duration lease)
      throws SQLException {
    this.store = store;
    this.topicId = topicId;
    owner = store.acquire(run, lease);
    try {
      committed = store.restore(run, topicId, limits, now);
    } catch (SQLException | RuntimeException e) {
      store.release(owner);
      throw e;
    }
  }

  public void apply(KafkaPump.Batch batch) throws SQLException {
    if (failed) throw new IllegalStateException("SESSION_RESTART_REQUIRED");
    Engine.State candidate =
        Engine.transition(committed, batch.inputs(), batch.observations(), batch.now());
    try {
      store.commit(owner, topicId, committed, candidate);
    } catch (SQLException | RuntimeException e) {
      failed = true;
      throw e;
    }
    committed = candidate;
  }

  public Engine.State state() {
    return committed;
  }

  public Map<Integer, Long> checkpoint() {
    return Map.copyOf(committed.offsets);
  }

  public PostgresStore.Owner owner() {
    return owner;
  }

  @Override
  public void close() throws SQLException {
    store.release(owner);
  }
}
