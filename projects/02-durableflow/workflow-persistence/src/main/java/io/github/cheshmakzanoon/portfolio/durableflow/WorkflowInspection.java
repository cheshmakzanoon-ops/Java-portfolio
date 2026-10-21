package io.github.cheshmakzanoon.portfolio.durableflow;

import static io.github.cheshmakzanoon.portfolio.durableflow.WorkflowSql.statement;

import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.Event;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.State;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Viewer operations never schedule work or acquire a row lock held by workflow writers. */
final class WorkflowInspection {
  private record Snapshot(
      String definitionHash, String semantics, State state, long version, String input) {}

  private final JdbcTransactions transactions;
  private final WorkflowHistory history;
  private final WorkflowReplay replay;

  WorkflowInspection(
      JdbcTransactions transactions, WorkflowHistory history, WorkflowReplay replay) {
    this.transactions = transactions;
    this.history = history;
    this.replay = replay;
  }

  WorkflowStore.View get(String tenant, UUID instance) {
    validateIdentity(tenant, instance);
    return transactions.inspect(
        connection -> {
          Snapshot snapshot = snapshot(connection, tenant, instance);
          String definition;
          try (PreparedStatement query =
                  statement(
                      connection,
                      "SELECT body FROM workflow_definition WHERE tenant=? AND hash=?",
                      tenant,
                      snapshot.definitionHash());
              ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("pinned definition missing");
            definition = rows.getString(1);
          }
          var result =
              replay.replay(
                  snapshot.semantics(),
                  definition,
                  history.read(connection, instance, snapshot.version()),
                  snapshot.input());
          // Runtime state includes outstanding lease barriers that pure replay alone cannot
          // observe. Return the state already committed with this exact history version.
          return new WorkflowStore.View(
              instance,
              tenant,
              snapshot.definitionHash(),
              snapshot.semantics(),
              snapshot.state(),
              snapshot.version(),
              result.values());
        });
  }

  List<Event> history(String tenant, UUID instance) {
    validateIdentity(tenant, instance);
    return transactions.inspect(
        connection -> {
          Snapshot snapshot = snapshot(connection, tenant, instance);
          return history.read(connection, instance, snapshot.version());
        });
  }

  HistoryPage page(String tenant, UUID instance, long after, Long requestedSnapshot, int limit) {
    validateIdentity(tenant, instance);
    HistoryPage.validateRequest(after, requestedSnapshot, limit);
    return transactions.inspect(
        connection -> {
          Snapshot current = snapshot(connection, tenant, instance);
          long boundary = requestedSnapshot == null ? current.version() : requestedSnapshot;
          if (boundary > current.version() || after > boundary)
            throw new IllegalArgumentException("history snapshot is not available");
          return HistoryPage.from(
              instance,
              boundary,
              after,
              limit,
              history.readPage(connection, instance, after, boundary, limit));
        });
  }

  private Snapshot snapshot(Connection connection, String tenant, UUID instance) throws Exception {
    try (PreparedStatement query =
            statement(
                connection,
                "SELECT definition_hash,semantics,state,version,input FROM workflow_instance WHERE id=? AND tenant=?",
                instance,
                tenant);
        ResultSet rows = query.executeQuery()) {
      if (!rows.next()) throw new NoSuchElementException("execution missing");
      long version = rows.getLong(4);
      if (version < 1 || version > WorkflowLimits.HISTORY_EVENTS)
        throw new IllegalStateException("persisted history version out of bounds");
      return new Snapshot(
          rows.getString(1),
          rows.getString(2),
          State.valueOf(rows.getString(3)),
          version,
          rows.getString(5));
    }
  }

  private static void validateIdentity(String tenant, UUID instance) {
    if (tenant == null || !tenant.matches("[a-zA-Z0-9-]{1,64}") || instance == null)
      throw new IllegalArgumentException("execution identity required");
  }
}
