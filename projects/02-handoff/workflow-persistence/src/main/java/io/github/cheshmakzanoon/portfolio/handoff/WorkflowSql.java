package io.github.cheshmakzanoon.portfolio.handoff;

import java.sql.*;

/** Prepared SQL with a common statement budget. Callers retain transaction ownership. */
final class WorkflowSql {
  private WorkflowSql() {}

  static PreparedStatement statement(Connection c, String sql, Object... params)
      throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    try {
      s.setQueryTimeout(15);
      for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
      return s;
    } catch (SQLException | RuntimeException | Error failure) {
      try {
        s.close();
      } catch (SQLException | RuntimeException | Error cleanup) {
        if (cleanup != failure) failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement s = statement(c, sql, params)) {
      return s.executeUpdate();
    }
  }
}
