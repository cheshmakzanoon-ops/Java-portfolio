package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransactionRetryTest {
  @Test
  void accountNamesRejectLossyUnicodeWithoutOpeningATransaction() {
    var connections = new Connections();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      for (String invalid : List.of("name\0suffix", "\uD800", "\uDC00")) {
        assertEquals(
            "ACCOUNT_INPUT",
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                        store.account(
                            java.util.UUID.randomUUID(), "CAD", Journal.Side.DEBIT, invalid))
                .getMessage());
      }
      assertTrue(connections.events.isEmpty());
    }
  }

  static final class Connections extends HikariDataSource {
    final List<String> events = new ArrayList<>();
    final List<String> statements = new ArrayList<>();
    SQLException rollbackFailure;
    SQLException commitFailure;
    SQLException closeFailure;

    @Override
    public Connection getConnection() {
      events.add("open");
      var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setAutoCommit" -> {
                    assertEquals(false, args[0]);
                    autoCommit.set((Boolean) args[0]);
                  }
                  case "getAutoCommit" -> {
                    return autoCommit.get();
                  }
                  case "setTransactionIsolation" -> {
                    assertEquals(Connection.TRANSACTION_SERIALIZABLE, args[0]);
                    events.add("serializable");
                  }
                  case "createStatement" -> {
                    return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (statement, call, parameters) -> {
                          if (call.getName().equals("execute")) {
                            statements.add((String) parameters[0]);
                            return true;
                          }
                          return null;
                        });
                  }
                  case "rollback" -> {
                    events.add("rollback");
                    if (rollbackFailure != null) throw rollbackFailure;
                  }
                  case "commit" -> {
                    events.add("commit");
                    if (commitFailure != null) {
                      var failure = commitFailure;
                      commitFailure = null;
                      throw failure;
                    }
                  }
                  case "close" -> {
                    events.add("close");
                    if (closeFailure != null) throw closeFailure;
                  }
                  default -> throw new AssertionError("Unexpected connection method " + method);
                }
                return null;
              });
    }
  }

  @Test
  void workRunsWithManualCommitAndTransactionLocalTimeouts() {
    var connections = new Connections();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      store.tx(
          connection -> {
            assertFalse(connection.getAutoCommit());
            return null;
          });
      assertEquals(
          List.of("SET LOCAL statement_timeout='15s'", "SET LOCAL lock_timeout='10s'"),
          connections.statements);
    }
  }

  @Test
  void retryableSqlStatesRepeatButConnectionAndQueryTimeoutsNeedCallerRecovery() {
    for (String state : List.of("40001", "40P01", "55P03", "23505")) {
      var calls = new AtomicInteger();
      try (var store =
          new LedgerStore(new Connections(), new RetryBackoff(bound -> 0, millis -> {}))) {
        int result =
            store.tx(
                c -> {
                  if (calls.incrementAndGet() == 1) throw new SQLException("retry fixture", state);
                  return calls.get();
                });
        assertEquals(2, result, state);
        assertEquals(1, store.retryCount(), state);
      }
    }
    for (String state : List.of("08006", "57014")) {
      try (var store =
          new LedgerStore(new Connections(), new RetryBackoff(bound -> fail(), millis -> fail()))) {
        var error =
            assertThrows(
                LedgerStore.LedgerException.class,
                () ->
                    store.tx(
                        c -> {
                          throw new SQLException("ambiguous/timeout", state);
                        }));
        assertTrue(error.temporary, state);
        assertEquals(0, store.retryCount(), state);
      }
    }
  }

  @Test
  void databaseFailuresRetainStructuredBusinessCodesWithoutAssumingEveryStateExists() {
    var pg =
        new org.postgresql.util.PSQLException(
            new org.postgresql.util.ServerErrorMessage("SERROR\0CP0422\0MINPUT_BOUND\0\0"));
    var failures =
        List.of(
            new SQLException("unknown state"),
            new java.sql.SQLTransientException("temporary without state"),
            new SQLException("plain business state", "P0422"),
            pg,
            new org.postgresql.util.PSQLException(
                "no server fields", org.postgresql.util.PSQLState.CONNECTION_FAILURE) {
              @Override
              public String getSQLState() {
                return "P0422";
              }
            });
    var codes =
        List.of(
            "DATABASE_REJECTED",
            "DATABASE_REJECTED",
            "POSTING_REJECTED",
            "INPUT_BOUND",
            "POSTING_REJECTED");
    for (int index = 0; index < failures.size(); index++) {
      var original = failures.get(index);
      try (var store =
          new LedgerStore(new Connections(), new RetryBackoff(bound -> fail(), millis -> fail()))) {
        var error =
            assertThrows(
                LedgerStore.LedgerException.class,
                () ->
                    store.tx(
                        c -> {
                          throw original;
                        }));
        assertSame(original, error.getCause());
        assertEquals(codes.get(index), error.code);
        assertEquals(index == 1, error.temporary);
      }
    }
  }

  @Test
  void acknowledgedCommitIsReturnedEvenWhenPoolCleanupFailsWithRetryableState() {
    var connections = new Connections();
    connections.closeFailure = new SQLException("pool return failed", "40001");
    var calls = new AtomicInteger();
    var result = new Object();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      assertSame(
          result,
          store.tx(
              c -> {
                calls.incrementAndGet();
                return result;
              }));
      assertEquals(1, calls.get());
      assertEquals(0, store.retryCount());
      assertEquals(List.of("open", "serializable", "commit", "close"), connections.events);
    }
  }

  @Test
  void uncertainCommitIsNotAutomaticallyReplayed() {
    var connections = new Connections();
    var unknown = new SQLException("acknowledgement lost", "08006");
    connections.commitFailure = unknown;
    var calls = new AtomicInteger();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class, () -> store.tx(c -> calls.incrementAndGet()));
      assertTrue(failure.temporary);
      assertSame(unknown, failure.getCause());
      assertEquals(1, calls.get());
      assertEquals(0, store.retryCount());
      assertEquals(
          List.of("open", "serializable", "commit", "rollback", "close"), connections.events);
    }
  }

  @Test
  void explicitSerializationAbortAtCommitRepeatsCompleteWorkOnNewConnection() {
    var connections = new Connections();
    connections.commitFailure = new SQLException("serialization abort", "40001");
    var calls = new AtomicInteger();
    try (var store = new LedgerStore(connections, new RetryBackoff(bound -> 0, millis -> {}))) {
      int result = store.tx(c -> calls.incrementAndGet());
      assertEquals(2, result);
      assertEquals(2, calls.get());
      assertEquals(1, store.retryCount());
      assertEquals(2, connections.events.stream().filter("open"::equals).count());
    }
  }

  @Test
  void fatalApplicationFailureRollsBackAndRemainsThePrimaryCause() {
    var connections = new Connections();
    connections.rollbackFailure = new SQLException("rollback failed", "08006");
    var original = new AssertionError("invariant failure");
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      assertSame(
          original,
          assertThrows(
              AssertionError.class,
              () ->
                  store.tx(
                      c -> {
                        throw original;
                      })));
      assertArrayEquals(new Throwable[] {connections.rollbackFailure}, original.getSuppressed());
      assertEquals(List.of("open", "serializable", "rollback", "close"), connections.events);
    }
  }

  @Test
  void preexistingCancellationNeverAcquiresAConnection() {
    var connections = new Connections();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      Thread.currentThread().interrupt();
      var failure = assertThrows(LedgerStore.LedgerException.class, () -> store.tx(c -> fail()));
      assertEquals("INTERRUPTED", failure.code);
      assertTrue(failure.temporary);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(connections.events.isEmpty());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rollbackFailureRetainsOriginalDatabaseErrorAndRetryClassification() {
    var connections = new Connections();
    connections.rollbackFailure = new SQLException("connection lost during rollback", "08006");
    var original = new SQLException("permanent validation failure", "23514");
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  store.tx(
                      c -> {
                        throw original;
                      }));
      assertSame(original, failure.getCause());
      assertFalse(failure.temporary);
      assertArrayEquals(new Throwable[] {connections.rollbackFailure}, original.getSuppressed());
      assertEquals(List.of("open", "serializable", "rollback", "close"), connections.events);
    }
  }

  @Test
  void rollbackFailureDoesNotTurnApplicationRejectionIntoDatabaseFailure() {
    var connections = new Connections();
    connections.rollbackFailure = new SQLException("connection lost during rollback", "08006");
    var original = new IllegalArgumentException("INVALID_MANUAL_CANDIDATE");
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  store.tx(
                      c -> {
                        throw original;
                      }));
      assertSame(original, failure);
      assertArrayEquals(new Throwable[] {connections.rollbackFailure}, original.getSuppressed());
      assertEquals(0, store.retryCount());
    }
  }

  @Test
  void finalTwelfthSerializableAttemptCanSucceedAndReleasesConnectionsBeforeEveryPause() {
    var connections = new Connections();
    var pauses = new ArrayList<Long>();
    var workCalls = new AtomicInteger();
    var result = new Object();
    try (var store =
        new LedgerStore(
            connections,
            new RetryBackoff(
                bound -> bound - 1,
                millis -> {
                  assertEquals("close", connections.events.getLast());
                  pauses.add(millis);
                }))) {
      assertSame(
          result,
          store.tx(
              c -> {
                if (workCalls.incrementAndGet() < 12)
                  throw new SQLException("synthetic serialization", "40001");
                return result;
              }));
      assertEquals(12, workCalls.get());
      assertEquals(11, store.retryCount());
      assertEquals(11, pauses.size());
      assertEquals(12, connections.events.stream().filter("serializable"::equals).count());
      assertEquals(11, connections.events.stream().filter("rollback"::equals).count());
      assertEquals(1, connections.events.stream().filter("commit"::equals).count());
      assertEquals(12, connections.events.stream().filter("close"::equals).count());
    }
  }

  @Test
  void exhaustionStopsAtTwelveWithElevenPausesAndRetainsTypedLastFailure() {
    var connections = new Connections();
    var calls = new AtomicInteger();
    var pauses = new ArrayList<Long>();
    var last = new SQLException("retained pivot fixture", "40001");
    try (var store = new LedgerStore(connections, new RetryBackoff(bound -> 0, pauses::add))) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  store.tx(
                      c -> {
                        calls.incrementAndGet();
                        throw last;
                      }));
      assertTrue(failure.temporary);
      assertSame(last, failure.getCause());
      assertEquals(12, calls.get());
      assertEquals(11, pauses.size());
      assertEquals(11, store.retryCount());
      assertFalse(connections.events.contains("commit"));
    }
  }

  @Test
  void permanentPostingFailureDoesNotDrawOrSleepOrStartAnotherTransaction() {
    var connections = new Connections();
    try (var store =
        new LedgerStore(connections, new RetryBackoff(bound -> fail(), millis -> fail()))) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  store.tx(
                      c -> {
                        throw new SQLException("closed period", "P0422");
                      }));
      assertFalse(failure.temporary);
      assertEquals(1, connections.events.stream().filter("open"::equals).count());
      assertEquals(0, store.retryCount());
    }
  }

  @Test
  void interruptedBackoffRetainsInterruptAndCannotStartAnotherTransaction() {
    var connections = new Connections();
    try (var store =
        new LedgerStore(
            connections,
            new RetryBackoff(
                bound -> 0,
                millis -> {
                  throw new InterruptedException("cancel retry");
                }))) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  store.tx(
                      c -> {
                        throw new SQLException("retry", "40001");
                      }));
      assertEquals("INTERRUPTED", failure.code);
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(1, connections.events.stream().filter("open"::equals).count());
      assertEquals(0, store.retryCount());
      assertTrue(connections.events.contains("rollback"));
      assertTrue(connections.events.contains("close"));
    } finally {
      Thread.interrupted();
    }
  }
}
