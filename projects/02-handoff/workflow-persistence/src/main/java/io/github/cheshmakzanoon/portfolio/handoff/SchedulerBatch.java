package io.github.cheshmakzanoon.portfolio.handoff;

import static io.github.cheshmakzanoon.portfolio.handoff.WorkflowSql.statement;

import java.sql.*;
import java.util.*;

/** Isolates each instance transaction so one rejected history cannot roll back unrelated work. */
final class SchedulerBatch {
  private static final String DUE =
      "(EXISTS(SELECT 1 FROM timer t WHERE t.instance_id=i.id AND t.status='WAITING' AND t.due_at<=clock_timestamp()) OR EXISTS(SELECT 1 FROM activity_task t WHERE t.instance_id=i.id AND t.status='LEASED' AND t.lease_until<=clock_timestamp()))";

  @FunctionalInterface
  interface Expire {
    void apply(Connection connection, UUID instance) throws Exception;
  }

  private final JdbcTransactions transactions;
  private final Expire expire;

  SchedulerBatch(JdbcTransactions transactions, Expire expire) {
    this.transactions = transactions;
    this.expire = expire;
  }

  WorkflowStore.SchedulerReport tick(int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("tick bounds");
    List<UUID> candidates =
        transactions.execute(
            connection -> {
              List<UUID> ids = new ArrayList<>();
              try (PreparedStatement statement =
                  statement(
                      connection,
                      "SELECT i.id FROM workflow_instance i WHERE i.scheduler_retry_at<=clock_timestamp() AND "
                          + DUE
                          + " ORDER BY i.id LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                  while (rows.next()) ids.add(rows.getObject(1, UUID.class));
                }
              }
              return ids;
            });
    int processed = 0;
    List<WorkflowStore.SchedulerFailure> failures = new ArrayList<>();
    for (UUID instance : candidates) {
      long[] attemptedVersion = {-1};
      try {
        boolean claimed =
            transactions.execute(
                connection -> {
                  try (PreparedStatement statement =
                      statement(
                          connection,
                          "SELECT i.version FROM workflow_instance i WHERE i.id=? AND i.scheduler_retry_at<=clock_timestamp() AND "
                              + DUE
                              + " FOR UPDATE SKIP LOCKED")) {
                    statement.setObject(1, instance);
                    try (ResultSet rows = statement.executeQuery()) {
                      if (!rows.next()) return false;
                      attemptedVersion[0] = rows.getLong(1);
                    }
                  }
                  expire.apply(connection, instance);
                  try (PreparedStatement clear =
                      statement(
                          connection,
                          "UPDATE workflow_instance SET scheduler_retry_at='-infinity',last_scheduler_error=NULL WHERE id=?")) {
                    clear.setObject(1, instance);
                    clear.executeUpdate();
                  }
                  return true;
                });
        if (claimed) processed++;
      } catch (RuntimeException failure) {
        // SQL failures describe storage/transaction availability or configuration,
        // not a rejected instance history. The runtime decides retry versus exit.
        if (failure instanceof PersistenceFailure storage
            && (storage.databaseFailure() || storage.retryable())) throw failure;
        // No locked instance snapshot means acquisition failed, rather than this
        // execution rejecting a transition. Let the runtime retry storage access.
        if (attemptedVersion[0] < 0) throw failure;
        String category =
            "history cap".equals(failure.getMessage())
                ? "HISTORY_CAPACITY"
                : "INSTANCE_TRANSITION_FAILED";
        // Persist a short retry delay after rollback; poisoned first-page rows cannot starve
        // later instances, and scheduler restarts retain the delay and failure counter.
        // A command may advance history after rollback; its progress must not receive
        // the stale transition's error or admission delay.
        try {
          transactions.execute(
              connection -> {
                try (PreparedStatement defer =
                    statement(
                        connection,
                        "UPDATE workflow_instance SET scheduler_retry_at=clock_timestamp()+interval '5 seconds',scheduler_failures=scheduler_failures+1,last_scheduler_error=? WHERE id=? AND version=?")) {
                  defer.setString(1, category);
                  defer.setObject(2, instance);
                  defer.setLong(3, attemptedVersion[0]);
                  defer.executeUpdate();
                }
                return null;
              });
        } catch (RuntimeException deferred) {
          failure.addSuppressed(deferred);
          throw failure;
        }
        failures.add(new WorkflowStore.SchedulerFailure(instance, category));
      }
    }
    return new WorkflowStore.SchedulerReport(processed, failures);
  }
}
