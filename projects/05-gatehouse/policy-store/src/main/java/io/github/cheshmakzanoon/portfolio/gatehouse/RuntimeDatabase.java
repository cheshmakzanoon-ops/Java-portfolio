package io.github.cheshmakzanoon.portfolio.gatehouse;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Verifies the deployed database boundary, rather than assuming migrations remain intact. */
public final class RuntimeDatabase {
  private static final Set<String> TABLES =
      Set.of(
          "tenant",
          "membership",
          "resource",
          "policy_version",
          "role",
          "role_edge",
          "grant",
          "active_policy",
          "audit");

  private RuntimeDatabase() {}

  public static void verify(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var roles =
            statement.executeQuery(
                "SELECT rolsuper,rolbypassrls,rolcreaterole,rolcreatedb FROM pg_roles WHERE rolname=current_user")) {
      if (!roles.next()
          || roles.getBoolean(1)
          || roles.getBoolean(2)
          || roles.getBoolean(3)
          || roles.getBoolean(4))
        throw new IllegalStateException(
            "runtime role must have no administrative database attributes");
    }
    try (var statement = connection.createStatement();
        var roles =
            statement.executeQuery(
                "SELECT 1 FROM pg_roles WHERE (rolsuper OR rolbypassrls) AND pg_has_role(oid,'MEMBER')")) {
      if (roles.next())
        throw new IllegalStateException("runtime role can assume an RLS-bypassing role");
    }
    Set<String> seen = new HashSet<>();
    try (var statement = connection.createStatement();
        var tables =
            statement.executeQuery(
                "SELECT c.relname,c.relrowsecurity,c.relforcerowsecurity,pg_has_role(c.relowner,'MEMBER') "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                    + "WHERE n.nspname='public' AND c.relkind='r'")) {
      while (tables.next()) {
        String table = tables.getString(1);
        if (!TABLES.contains(table)) continue;
        seen.add(table);
        if (!tables.getBoolean(2) || !tables.getBoolean(3) || tables.getBoolean(4))
          throw new IllegalStateException("runtime RLS/ownership boundary is invalid for " + table);
      }
    }
    if (!seen.equals(TABLES))
      throw new IllegalStateException("required authorization tables are missing");
  }
}
