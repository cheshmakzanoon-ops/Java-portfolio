package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.JdbcTransactions.*;
import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static io.github.cheshmakzanoon.portfolio.routeweaver.Store.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Immutable input snapshots and the active-version pointer share one transaction boundary. */
final class ProblemRepository {
  private final JdbcTransactions database;

  ProblemRepository(JdbcTransactions database) {
    this.database = database;
  }

  public Problem create(Problem p) {
    if (p.version() != 1) throw new IllegalArgumentException("initial version must be 1");
    return database.tx(
        c -> {
          try {
            snapshot(c, p);
            update(c, "INSERT INTO active_problem VALUES (?,?,?)", p.tenant(), p.id(), p.version());
            update(
                c,
                "INSERT INTO published_plan(tenant,id,problem_version) VALUES(?,?,?)",
                p.tenant(),
                p.id(),
                p.version());
            event(c, p, "CREATED", p.canonicalHash());
            return p;
          } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new Conflict("problem already exists");
            throw e;
          }
        });
  }

  void snapshot(Connection c, Problem p) throws SQLException {
    update(
        c,
        "INSERT INTO problem_snapshot(tenant,problem_id,version,matrix_version,input_hash,snapshot) VALUES(?,?,?,?,?,?)",
        p.tenant(),
        p.id(),
        p.version(),
        p.matrix().version(),
        p.canonicalHash(),
        Json.write(p));
  }

  public Problem load(String tenant, String id, long version) {
    return database.tx(c -> load(c, tenant, id, version));
  }

  Problem load(Connection c, String tenant, String id, long version) throws SQLException {
    return Json.read(
        string(
            c,
            "SELECT snapshot FROM problem_snapshot WHERE tenant=? AND problem_id=? AND version=?",
            tenant,
            id,
            version),
        Problem.class);
  }

  public long active(String tenant, String id) {
    return database.tx(
        c ->
            Long.parseLong(
                string(
                    c,
                    "SELECT version FROM active_problem WHERE tenant=? AND problem_id=?",
                    tenant,
                    id)));
  }

  void event(Connection c, Problem p, String kind, String detail) throws SQLException {
    update(
        c,
        "INSERT INTO plan_event(tenant,problem_id,problem_version,kind,details) VALUES(?,?,?,?,?)",
        p.tenant(),
        p.id(),
        p.version(),
        kind,
        detail);
  }
}
