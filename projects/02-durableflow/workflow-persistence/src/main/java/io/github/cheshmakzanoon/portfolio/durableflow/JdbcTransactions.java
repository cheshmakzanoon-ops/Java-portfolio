package io.github.cheshmakzanoon.portfolio.durableflow;

import java.sql.Connection;
import javax.sql.DataSource;

/** Transaction ownership and failure preservation, independent of workflow scheduling policy. */
final class JdbcTransactions {
  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws Exception;
  }

  private static final System.Logger LOG = System.getLogger(JdbcTransactions.class.getName());
  private final DataSource source;
  private final Runnable beforeCommit;

  JdbcTransactions(DataSource source, Runnable beforeCommit) {
    this.source = source;
    this.beforeCommit = beforeCommit;
  }

  <T> T execute(Work<T> work) {
    boolean committed = false;
    T result = null;
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        result = work.run(connection);
        beforeCommit.run();
        connection.commit();
        committed = true;
        return result;
      } catch (Exception | Error primary) {
        try {
          connection.rollback();
        } catch (Exception | Error rollback) {
          if (rollback != primary) primary.addSuppressed(rollback);
        }
        throw primary;
      }
    } catch (Exception failure) {
      if (committed) {
        // A failed pool return cannot undo an acknowledged durable transition.
        // Reporting failure here would misclassify a durable claim or dispatch outcome.
        LOG.log(
            System.Logger.Level.WARNING, "Connection cleanup failed after committed transaction");
        return result;
      }
      if (failure instanceof RuntimeException runtime) throw runtime;
      throw new PersistenceFailure(failure);
    }
  }
}
