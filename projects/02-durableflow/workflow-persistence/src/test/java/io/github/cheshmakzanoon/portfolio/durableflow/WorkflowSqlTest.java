package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowSqlTest {
  @Test
  void timeoutConfigurationFailureClosesPreparedStatementAndRetainsCause() {
    SQLException failure = new SQLException("cannot configure timeout", "08006");
    var calls = new ArrayList<String>();
    Connection connection = connection(calls, "setQueryTimeout", failure, null);
    assertSame(
        failure,
        assertThrows(SQLException.class, () -> WorkflowSql.statement(connection, "SELECT ?", 1)));
    assertEquals(List.of("prepare", "setQueryTimeout", "close"), calls);
  }

  @Test
  void bindingFailureClosesStatementAndSuppressesCleanupFailure() {
    SQLException failure = new SQLException("invalid parameter", "22023");
    SQLException cleanup = new SQLException("cannot close", "08006");
    var calls = new ArrayList<String>();
    Connection connection = connection(calls, "setObject", failure, cleanup);
    assertSame(
        failure,
        assertThrows(SQLException.class, () -> WorkflowSql.statement(connection, "SELECT ?", 1)));
    assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
    assertEquals(List.of("prepare", "setQueryTimeout", "setObject", "close"), calls);
  }

  @Test
  void runtimeAndFatalDriverFailuresDoNotLeakStatementHandles() {
    for (Throwable failure :
        List.of(new IllegalStateException("driver failure"), new AssertionError("driver error"))) {
      var calls = new ArrayList<String>();
      Connection connection = connection(calls, "setObject", failure, failure);
      assertSame(
          failure,
          assertThrows(failure.getClass(), () -> WorkflowSql.statement(connection, "SELECT ?", 1)));
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(List.of("prepare", "setQueryTimeout", "setObject", "close"), calls);
    }
  }

  @Test
  void successfulPreparationTransfersOwnershipToTheCaller() throws Exception {
    var calls = new ArrayList<String>();
    try (PreparedStatement statement =
        WorkflowSql.statement(connection(calls, "never", null, null), "SELECT ?,?", "one", 2)) {
      assertNotNull(statement);
      assertEquals(List.of("prepare", "setQueryTimeout", "setObject", "setObject"), calls);
    }
    assertEquals("close", calls.getLast());
  }

  private Connection connection(
      List<String> calls, String failAt, Throwable failure, Throwable closeFailure) {
    PreparedStatement statement =
        (PreparedStatement)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                  calls.add(method.getName());
                  if (method.getName().equals("close") && closeFailure != null) throw closeFailure;
                  if (method.getName().equals(failAt)) throw failure;
                  return null;
                });
    return (Connection)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("prepareStatement")) {
                calls.add("prepare");
                return statement;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
