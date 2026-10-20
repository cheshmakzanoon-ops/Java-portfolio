package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** One acknowledged SQL commit; failures never cause implicit replay of a processing decision. */
final class SqlTransactions {
  @FunctionalInterface
  interface Connections {
    Connection open() throws SQLException;
  }

  @FunctionalInterface
  interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  private static final System.Logger LOG = System.getLogger(SqlTransactions.class.getName());
  private final Connections connections;

  SqlTransactions(Connections connections) {
    this.connections = Objects.requireNonNull(connections);
  }

  <T> T run(Work<T> work) throws SQLException {
    boolean committed = false;
    T result = null;
    try (var connection = connections.open()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        result = work.run(connection);
        connection.commit();
        committed = true;
        return result;
      } catch (SQLException | RuntimeException | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          if (failure != rollbackFailure) failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      if (!committed) throw failure;
      LOG.log(
          System.Logger.Level.WARNING,
          "Connection cleanup failed after committed transaction; SQL state {0}",
          failure.getSQLState());
      return result;
    }
  }
}
