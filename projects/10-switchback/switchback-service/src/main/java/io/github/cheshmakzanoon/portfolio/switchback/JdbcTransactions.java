package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.JdbcTransactions.*;
import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;
import static io.github.cheshmakzanoon.portfolio.switchback.Store.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Owns connection lifetime, transaction rollback, SQL timeouts and parameter binding. */
final class JdbcTransactions {
  private final DataSource dataSource;

  JdbcTransactions(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource);
  }

  @FunctionalInterface
  interface Tx<T> {
    T run(Connection c) throws SQLException;
  }

  <T> T tx(Tx<T> action) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (Statement timeout = c.createStatement()) {
        timeout.execute("SET LOCAL statement_timeout='10s'");
        timeout.execute("SET LOCAL lock_timeout='5s'");
      }
      try {
        T result = action.run(c);
        c.commit();
        return result;
      } catch (RuntimeException | SQLException e) {
        try {
          c.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("database operation failed", e);
    }
  }

  void initialize() {
    try (Connection c = dataSource.getConnection();
        java.io.InputStream resource =
            Objects.requireNonNull(Store.class.getResourceAsStream("/schema.sql"))) {
      c.setAutoCommit(false);
      String sql = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      try (Statement statement = c.createStatement()) {
        statement.execute("SET LOCAL statement_timeout='15s'");
        statement.execute("SELECT pg_advisory_xact_lock(19000011)");
        statement.execute(sql);
      }
      c.commit();
    } catch (Exception e) {
      throw new IllegalStateException("PostgreSQL schema initialization failed", e);
    }
  }

  static int update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    }
  }

  static void bind(PreparedStatement s, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      s.setObject(
          i + 1,
          args[i] instanceof Instant at ? OffsetDateTime.ofInstant(at, ZoneOffset.UTC) : args[i]);
  }

  static String string(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new Missing();
        return r.getString(1);
      }
    }
  }

  static Instant instant(ResultSet rows, String column) throws SQLException {
    Timestamp value = rows.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
