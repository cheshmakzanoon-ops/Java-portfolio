package io.github.cheshmakzanoon.portfolio.clearledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

/** Owns connection lifetime, complete-operation retries, and commit-outcome classification. */
final class SerializableTransactions {
  private static final int ATTEMPTS = 12;
  private static final System.Logger LOG =
      System.getLogger(SerializableTransactions.class.getName());
  private final DataSource source;
  private final RetryBackoff backoff;
  private final AtomicLong retries = new AtomicLong();

  SerializableTransactions(DataSource source, RetryBackoff backoff) {
    this.source = Objects.requireNonNull(source);
    this.backoff = Objects.requireNonNull(backoff);
  }

  long retryCount() {
    return retries.get();
  }

  <T> T execute(LedgerStore.SqlWork<T> work) {
    Objects.requireNonNull(work);
    for (int attempt = 0; ; attempt++) {
      if (Thread.currentThread().isInterrupted())
        throw new LedgerStore.LedgerException("INTERRUPTED", true, null);
      boolean committed = false;
      T result = null;
      try (var connection = source.getConnection()) {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try (var statement = connection.createStatement()) {
          statement.execute("SET LOCAL statement_timeout='15s'");
          statement.execute("SET LOCAL lock_timeout='10s'");
        }
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
        if (committed) {
          // A pool-return failure cannot undo an acknowledged commit or justify replaying work.
          LOG.log(
              System.Logger.Level.WARNING,
              "Connection cleanup failed after committed transaction; SQL state {0}",
              failure.getSQLState());
          return result;
        }
        if (retryable(failure) && attempt + 1 < ATTEMPTS) {
          try {
            backoff.pause(attempt);
            retries.incrementAndGet();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LedgerStore.LedgerException("INTERRUPTED", true, interrupted);
          }
          continue;
        }
        throw rejection(failure);
      }
    }
  }

  private static boolean retryable(SQLException failure) {
    return "40001".equals(failure.getSQLState())
        || "40P01".equals(failure.getSQLState())
        || "55P03".equals(failure.getSQLState())
        || "23505".equals(failure.getSQLState());
  }

  private static LedgerStore.LedgerException rejection(SQLException failure) {
    String state = failure.getSQLState();
    String code = "DATABASE_REJECTED";
    if (state != null && state.startsWith("P0")) {
      code =
          failure instanceof org.postgresql.util.PSQLException pg
                  && pg.getServerErrorMessage() != null
              ? pg.getServerErrorMessage().getMessage()
              : "POSTING_REJECTED";
    }
    boolean temporary =
        failure instanceof SQLTransientException
            || "40001".equals(state)
            || "40P01".equals(state)
            || "55P03".equals(state)
            || "57014".equals(state)
            || state != null && state.startsWith("08");
    return new LedgerStore.LedgerException(code, temporary, failure);
  }
}
