package io.github.cheshmakzanoon.portfolio.durableflow;

import java.sql.*;

/** Prepared SQL with a common statement budget. Callers retain transaction ownership. */
final class WorkflowSql {
  private WorkflowSql() {}

  static PreparedStatement statement(Connection c, String sql, Object... params)
      throws SQLException {
    PreparedStatement s = c.prepareStatement(sql);
    s.setQueryTimeout(15);
    for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
    return s;
  }

  static int update(Connection c, String sql, Object... params) throws SQLException {
    try (PreparedStatement s = statement(c, sql, params)) {
      return s.executeUpdate();
    }
  }
}
