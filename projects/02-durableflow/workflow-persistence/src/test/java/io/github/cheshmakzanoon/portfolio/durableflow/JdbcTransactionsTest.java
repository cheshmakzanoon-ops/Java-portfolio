package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcTransactionsTest {
  @Test
  void domainFailureRemainsPrimaryWhenRollbackAndCloseBothFail() {
    RuntimeException primary = new IllegalArgumentException("rejected transition");
    List<String> calls = new ArrayList<>();
    JdbcTransactions transactions = transactions(calls, true, true, null);
    assertSame(
        primary,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                transactions.execute(
                    connection -> {
                      throw primary;
                    })));
    assertEquals(
        List.of("rollback", "close"),
        Arrays.stream(primary.getSuppressed()).map(Throwable::getMessage).toList());
    assertEquals(List.of("autoCommit:false", "isolation:2", "rollback", "close"), calls);
  }

  @Test
  void failedCommitPreservesItsCauseAndNeverReportsSuccess() {
    SQLException commit = new SQLException("commit lost acknowledgement", "08006");
    List<String> calls = new ArrayList<>();
    PersistenceFailure thrown =
        assertThrows(
            PersistenceFailure.class,
            () -> transactions(calls, true, false, commit).execute(connection -> "completed"));
    assertSame(commit, thrown.getCause());
    assertEquals("rollback", commit.getSuppressed()[0].getMessage());
    assertEquals(List.of("autoCommit:false", "isolation:2", "commit", "rollback", "close"), calls);
  }

  @Test
  void fatalFailureRollsBackAndSuccessCommitsExactlyOnce() {
    List<String> success = new ArrayList<>();
    int result = transactions(success, false, false, null).execute(connection -> 42);
    assertEquals(42, result);
    assertEquals(List.of("autoCommit:false", "isolation:2", "commit", "close"), success);
    List<String> failure = new ArrayList<>();
    AssertionError fatal = new AssertionError("invariant");
    assertSame(
        fatal,
        assertThrows(
            AssertionError.class,
            () ->
                transactions(failure, false, false, null)
                    .execute(
                        connection -> {
                          throw fatal;
                        })));
    assertEquals(List.of("autoCommit:false", "isolation:2", "rollback", "close"), failure);
  }

  @Test
  void commitBoundaryHookCannotBeSkippedAndItsFailureRollsBack() {
    List<String> calls = new ArrayList<>();
    RuntimeException boundary = new IllegalStateException("before commit");
    assertSame(
        boundary,
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions(
                        calls,
                        false,
                        false,
                        null,
                        () -> {
                          throw boundary;
                        })
                    .execute(connection -> 42)));
    assertEquals(List.of("autoCommit:false", "isolation:2", "rollback", "close"), calls);
  }

  private JdbcTransactions transactions(
      List<String> calls, boolean rollbackFails, boolean closeFails, SQLException commitFailure) {
    return transactions(calls, rollbackFails, closeFails, commitFailure, () -> {});
  }

  private JdbcTransactions transactions(
      List<String> calls,
      boolean rollbackFails,
      boolean closeFails,
      SQLException commitFailure,
      Runnable hook) {
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                  switch (method.getName()) {
                    case "setAutoCommit" -> calls.add("autoCommit:" + arguments[0]);
                    case "setTransactionIsolation" -> calls.add("isolation:" + arguments[0]);
                    case "commit" -> {
                      calls.add("commit");
                      if (commitFailure != null) throw commitFailure;
                    }
                    case "rollback" -> {
                      calls.add("rollback");
                      if (rollbackFails) throw new SQLException("rollback");
                    }
                    case "close" -> {
                      calls.add("close");
                      if (closeFails) throw new SQLException("close");
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                  }
                  return null;
                });
    DataSource source =
        (DataSource)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getConnection")) return connection;
                  throw new UnsupportedOperationException(method.getName());
                });
    return new JdbcTransactions(source, hook);
  }
}
