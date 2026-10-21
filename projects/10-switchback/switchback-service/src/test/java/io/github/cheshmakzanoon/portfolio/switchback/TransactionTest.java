package io.github.cheshmakzanoon.portfolio.switchback;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TransactionTest {
  @Test
  void rollbackFailureNeverReplacesTheOriginalFailureAndConnectionCloses() {
    AtomicBoolean closed = new AtomicBoolean();
    Statement statement =
        (Statement)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> method.getName().equals("execute") ? true : null);
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("createStatement")) return statement;
                  if (method.getName().equals("rollback"))
                    throw new SQLException("rollback unavailable");
                  if (method.getName().equals("close")) closed.set(true);
                  return null;
                });
    DataSource source =
        (DataSource)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> connection);
    IllegalStateException original = new IllegalStateException("primary failure");
    assertSame(
        original,
        assertThrows(
            IllegalStateException.class,
            () ->
                new JdbcTransactions(source)
                    .tx(
                        c -> {
                          throw original;
                        })));
    assertEquals(1, original.getSuppressed().length);
    assertInstanceOf(SQLException.class, original.getSuppressed()[0]);
    assertTrue(closed.get());
  }
}
