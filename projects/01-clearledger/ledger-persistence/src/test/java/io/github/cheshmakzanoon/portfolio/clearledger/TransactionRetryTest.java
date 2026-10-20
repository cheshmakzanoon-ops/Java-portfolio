package io.github.cheshmakzanoon.portfolio.clearledger;

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
  static final class Connections extends HikariDataSource {
    final List<String> events = new ArrayList<>();
    SQLException rollbackFailure;

    @Override
    public Connection getConnection() {
      events.add("open");
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setAutoCommit" -> assertEquals(false, args[0]);
                  case "setTransactionIsolation" -> {
                    assertEquals(Connection.TRANSACTION_SERIALIZABLE, args[0]);
                    events.add("serializable");
                  }
                  case "createStatement" -> {
                    return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {Statement.class},
                        (statement, call, parameters) ->
                            call.getName().equals("execute") ? true : null);
                  }
                  case "rollback" -> {
                    events.add("rollback");
                    if (rollbackFailure != null) throw rollbackFailure;
                  }
                  case "commit", "close" -> events.add(method.getName());
                  default -> throw new AssertionError("Unexpected connection method " + method);
                }
                return null;
              });
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
