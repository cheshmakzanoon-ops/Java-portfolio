package io.github.cheshmakzanoon.portfolio.durableflow;

import java.sql.Connection;
import javax.sql.DataSource;

/** Transaction ownership and failure preservation, independent of workflow scheduling policy. */
final class JdbcTransactions {
  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws Exception;
  }

  private final DataSource source;
  private final Runnable beforeCommit;

  JdbcTransactions(DataSource source, Runnable beforeCommit) {
    this.source = source;
    this.beforeCommit = beforeCommit;
  }

  <T> T execute(Work<T> work) {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        T result = work.run(connection);
        beforeCommit.run();
        connection.commit();
        return result;
      } catch (Exception | Error primary) {
        try {
          connection.rollback();
        } catch (Exception | Error rollback) {
          if (rollback != primary) primary.addSuppressed(rollback);
        }
        throw primary;
      }
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new PersistenceFailure(failure);
    }
  }
}
