package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SqlTransactionsTest {
  private static final class Database {
    final List<String> events = new ArrayList<>();
    SQLException commitFailure, rollbackFailure, closeFailure;
    boolean autoCommit = true;
    int isolation;

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    return null;
                  }
                  case "getAutoCommit" -> {
                    return autoCommit;
                  }
                  case "setTransactionIsolation" -> {
                    isolation = (int) args[0];
                    return null;
                  }
                  case "getTransactionIsolation" -> {
                    return isolation;
                  }
                  case "commit" -> {
                    events.add("commit");
                    if (commitFailure != null) throw commitFailure;
                    return null;
                  }
                  case "rollback" -> {
                    events.add("rollback");
                    if (rollbackFailure != null) throw rollbackFailure;
                    return null;
                  }
                  case "close" -> {
                    events.add("close");
                    if (closeFailure != null) throw closeFailure;
                    return null;
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                }
              });
    }
  }

  @Test
  void successfulWorkCommitsBeforeCloseAndPreservesItsResult() throws Exception {
    var database = new Database();
    Object expected = new Object();
    assertSame(
        expected,
        new SqlTransactions(database::connection)
            .run(
                connection -> {
                  assertFalse(connection.getAutoCommit());
                  assertEquals(
                      Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
                  database.events.add("work");
                  return expected;
                }));
    assertEquals(List.of("work", "commit", "close"), database.events);
  }

  @Test
  void acknowledgedCommitSurvivesCleanupFailureWithoutReplay() throws Exception {
    var database = new Database();
    database.closeFailure = new SQLException("pool", "40001");
    var calls = new AtomicInteger();
    int result =
        new SqlTransactions(database::connection).run(connection -> calls.incrementAndGet());
    assertEquals(1, result);
    assertEquals(1, calls.get());
    assertEquals(List.of("commit", "close"), database.events);
  }

  @Test
  void rejectedOrUnknownCommitRollsBackAndNeverReplaysWork() {
    for (String state : List.of("40001", "08006")) {
      var database = new Database();
      database.commitFailure = new SQLException("commit", state);
      var calls = new AtomicInteger();
      assertSame(
          database.commitFailure,
          assertThrows(
              SQLException.class,
              () -> new SqlTransactions(database::connection).run(c -> calls.incrementAndGet())));
      assertEquals(1, calls.get());
      assertEquals(List.of("commit", "rollback", "close"), database.events);
    }
  }

  @Test
  void cleanupPreservesPrimarySqlRuntimeAndFatalFailures() {
    for (Throwable primary :
        List.of(
            new SQLException("business", "P0422"),
            new IllegalArgumentException("intent"),
            new AssertionError("fatal"))) {
      var database = new Database();
      database.rollbackFailure = new SQLException("rollback", "08006");
      database.closeFailure = new SQLException("close", "08006");
      Throwable observed =
          assertThrows(
              Throwable.class,
              () ->
                  new SqlTransactions(database::connection)
                      .run(
                          c -> {
                            if (primary instanceof SQLException sql) throw sql;
                            if (primary instanceof RuntimeException runtime) throw runtime;
                            throw (Error) primary;
                          }));
      assertSame(primary, observed);
      assertArrayEquals(
          new Throwable[] {database.rollbackFailure, database.closeFailure},
          observed.getSuppressed());
      assertEquals(List.of("rollback", "close"), database.events);
    }
  }

  @Test
  void identicalRollbackFailureDoesNotReplaceTheOriginalOrSuppressItself() {
    var database = new Database();
    var primary = new SQLException("original");
    database.rollbackFailure = primary;
    assertSame(
        primary,
        assertThrows(
            SQLException.class,
            () ->
                new SqlTransactions(database::connection)
                    .run(
                        c -> {
                          throw primary;
                        })));
    assertEquals(0, primary.getSuppressed().length);
    assertEquals(List.of("rollback", "close"), database.events);
  }
}
