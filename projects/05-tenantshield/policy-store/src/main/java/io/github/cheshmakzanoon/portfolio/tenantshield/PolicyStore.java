package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * JDBC transactions use a transaction-local tenant variable; no session tenant state survives a
 * pool return.
 */
public final class PolicyStore {
  public record Principal(String issuer, String subject) {
    public Principal {
      if (issuer == null || subject == null || subject.length() > 128)
        throw new IllegalArgumentException("principal");
    }
  }

  public record Membership(
      Set<String> roles,
      String department,
      Long clearance,
      Boolean suspended,
      boolean admin,
      boolean active) {
    public Membership {
      roles = Set.copyOf(roles);
    }
  }

  public record Revision(long version, String content, String digest) {}

  public record Resource(
      String id, String type, String department, Long classification, long version) {}

  public record Snapshot(long epoch, Membership membership, Revision policy, Resource resource) {}

  public record Audit(
      UUID id,
      String principal,
      String action,
      String resource,
      long epoch,
      String result,
      String reason) {}

  public record Outcome(Snapshot snapshot, PolicyEngine.Decision decision, UUID id) {}

  public static class StoreFailure extends RuntimeException {
    public StoreFailure(Exception e) {
      super("authorization dependency unavailable", e);
    }
  }

  public static class Forbidden extends RuntimeException {}

  public static class Conflict extends RuntimeException {}

  private final DataSource dataSource;
  private final ThreadLocal<long[]> transactionQueries = new ThreadLocal<>();
  private final Map<String, Long> tenantQueries =
      Collections.synchronizedMap(new LinkedHashMap<>());

  public long queryCount(String tenant) {
    return tenantQueries.getOrDefault(tenant, 0L);
  }

  private final java.util.concurrent.atomic.LongAdder queryCount =
      new java.util.concurrent.atomic.LongAdder();

  private PreparedStatement prepare(Connection c, String sql) throws SQLException {
    queryCount.increment();
    long[] count = transactionQueries.get();
    if (count != null) count[0]++;
    return c.prepareStatement(sql);
  }

  public long queryCount() {
    return queryCount.sum();
  }

  public PolicyStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  private <T> T transaction(String tenant, boolean write, Work<T> work) {
    if (tenant == null || !tenant.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("invalid tenant");
    transactionQueries.set(new long[] {0});
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(
          write ? Connection.TRANSACTION_SERIALIZABLE : Connection.TRANSACTION_REPEATABLE_READ);
      try (PreparedStatement s =
          prepare(
              c,
              "SELECT set_config('tenantshield.tenant', ?, true), set_config('statement_timeout','5000',true), set_config('lock_timeout','3000',true)")) {
        s.setString(1, tenant);
        s.execute();
      }
      try {
        T result = work.run(c);
        c.commit();
        synchronized (tenantQueries) {
          if (tenantQueries.size() < 1000 || tenantQueries.containsKey(tenant))
            tenantQueries.merge(tenant, transactionQueries.get()[0], Long::sum);
        }
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      if ("40001".equals(e.getSQLState())) throw new Conflict();
      throw new StoreFailure(e);
    } finally {
      transactionQueries.remove();
    }
  }

  private long epoch(Connection c, String tenant, boolean lock) throws SQLException {
    // Epoch writers serialize, but tenant identity does not change. KEY SHARE checks for the
    // decision-audit FK must remain compatible with a writer across an older RR snapshot.
    try (PreparedStatement s =
        prepare(
            c, "SELECT epoch FROM tenant WHERE tenant_id=?" + (lock ? " FOR NO KEY UPDATE" : ""))) {
      s.setString(1, tenant);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new Forbidden();
        return r.getLong(1);
      }
    }
  }

  private Membership membership(Connection c, String tenant, Principal p) throws SQLException {
    try (PreparedStatement s =
        prepare(
            c,
            "SELECT roles,department,clearance,suspended,administrative,active FROM membership WHERE tenant_id=? AND issuer=? AND subject_id=?")) {
      s.setString(1, tenant);
      s.setString(2, p.issuer());
      s.setString(3, p.subject());
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) return null;
        return new Membership(
            Set.of((String[]) r.getArray(1).getArray()),
            r.getString(2),
            r.getObject(3, Long.class),
            r.getObject(4, Boolean.class),
            r.getBoolean(5),
            r.getBoolean(6));
      }
    }
  }

  private void administrative(Connection c, String tenant, Principal p) throws SQLException {
    Membership m = membership(c, tenant, p);
    if (m == null || !m.active() || !m.admin()) throw new Forbidden();
  }

  private Revision revision(Connection c, String tenant, long version) throws SQLException {
    try (PreparedStatement s =
        prepare(
            c,
            "SELECT version,content,digest FROM policy_version WHERE tenant_id=? AND version=?")) {
      s.setString(1, tenant);
      s.setLong(2, version);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new IllegalArgumentException("unknown version");
        return new Revision(r.getLong(1), r.getString(2), r.getString(3));
      }
    }
  }

  private long active(Connection c, String tenant) throws SQLException {
    try (PreparedStatement s = prepare(c, "SELECT version FROM active_policy WHERE tenant_id=?")) {
      s.setString(1, tenant);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  private Resource resource(Connection c, String tenant, String id) throws SQLException {
    try (PreparedStatement s =
        prepare(
            c,
            "SELECT resource_id,resource_type,department,classification,resource_version FROM resource WHERE tenant_id=? AND resource_id=?")) {
      s.setString(1, tenant);
      s.setString(2, id);
      try (ResultSet r = s.executeQuery()) {
        return r.next()
            ? new Resource(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getObject(4, Long.class),
                r.getLong(5))
            : null;
      }
    }
  }

  public Outcome decide(
      String tenant,
      Principal principal,
      String action,
      String id,
      Function<Snapshot, PolicyEngine.Decision> evaluator) {
    return transaction(
        tenant,
        false,
        c -> {
          long e = epoch(c, tenant, false);
          Membership m = membership(c, tenant, principal);
          long v = m != null && m.active() ? active(c, tenant) : 0;
          Snapshot snap =
              new Snapshot(
                  m == null ? 0 : e,
                  m,
                  v == 0 ? null : revision(c, tenant, v),
                  m != null && m.active() ? resource(c, tenant, id) : null);
          PolicyEngine.Decision d = evaluator.apply(snap);
          UUID decisionId = UUID.randomUUID();
          audit(
              c,
              tenant,
              new Audit(
                  decisionId,
                  principalRef(principal),
                  action,
                  id,
                  e,
                  d.allowed() ? "ALLOW" : "DENY",
                  d.reason()));
          return new Outcome(snap, d, decisionId);
        });
  }

  public long draft(String tenant, Principal p, String content, PolicyEngine.Compiled compiled) {
    return transaction(
        tenant,
        true,
        c -> {
          epoch(c, tenant, true);
          administrative(c, tenant, p);
          long version;
          try (PreparedStatement s =
              prepare(
                  c, "SELECT COALESCE(MAX(version),0)+1 FROM policy_version WHERE tenant_id=?")) {
            s.setString(1, tenant);
            try (ResultSet r = s.executeQuery()) {
              r.next();
              version = r.getLong(1);
            }
          }
          try (PreparedStatement s =
              prepare(
                  c,
                  "INSERT INTO policy_version(tenant_id,version,content,digest) VALUES(?,?,?,?)")) {
            s.setString(1, tenant);
            s.setLong(2, version);
            s.setString(3, content);
            s.setString(4, compiled.digest());
            s.executeUpdate();
          }
          for (String role : compiled.definition().parents().keySet()) {
            try (PreparedStatement s = prepare(c, "INSERT INTO role VALUES(?,?,?)")) {
              s.setString(1, tenant);
              s.setLong(2, version);
              s.setString(3, role);
              s.executeUpdate();
            }
          }
          for (var entry : compiled.definition().parents().entrySet())
            for (String parent : entry.getValue()) {
              try (PreparedStatement s = prepare(c, "INSERT INTO role_edge VALUES(?,?,?,?)")) {
                s.setString(1, tenant);
                s.setLong(2, version);
                s.setString(3, entry.getKey());
                s.setString(4, parent);
                s.executeUpdate();
              }
            }
          for (var g : compiled.definition().grants()) {
            try (PreparedStatement s = prepare(c, "INSERT INTO \"grant\" VALUES(?,?,?,?,?)")) {
              s.setString(1, tenant);
              s.setLong(2, version);
              s.setString(3, g.role());
              s.setString(4, g.action());
              s.setString(5, g.resourceType());
              s.executeUpdate();
            }
          }
          audit(
              c,
              tenant,
              new Audit(
                  UUID.randomUUID(),
                  principalRef(p),
                  "policy:draft",
                  Long.toString(version),
                  epoch(c, tenant, false),
                  "CREATED",
                  "VALIDATED"));
          return version;
        });
  }

  public long publish(String tenant, Principal p, long version, long expected) {
    return transaction(
        tenant,
        true,
        c -> {
          long e = epoch(c, tenant, true);
          administrative(c, tenant, p);
          long old = active(c, tenant);
          if (old != expected || version <= old) throw new Conflict();
          revision(c, tenant, version);
          try (PreparedStatement s =
              prepare(
                  c,
                  "INSERT INTO active_policy VALUES(?,?) ON CONFLICT(tenant_id) DO UPDATE SET version=EXCLUDED.version")) {
            s.setString(1, tenant);
            s.setLong(2, version);
            s.executeUpdate();
          }

          audit(
              c,
              tenant,
              new Audit(
                  UUID.randomUUID(),
                  principalRef(p),
                  "policy:publish",
                  Long.toString(version),
                  e + 1,
                  "PUBLISHED",
                  "EXPECTED_VERSION"));
          return e + 1;
        });
  }

  public record Published(long policyVersion, long tenantEpoch) {}

  public Published rollback(String tenant, Principal principal, long sourceVersion, long expected) {
    return transaction(
        tenant,
        true,
        c -> {
          long epoch = epoch(c, tenant, true);
          administrative(c, tenant, principal);
          if (active(c, tenant) != expected) throw new Conflict();
          revision(c, tenant, sourceVersion);
          long version;
          try (PreparedStatement q =
              prepare(
                  c, "SELECT COALESCE(MAX(version),0)+1 FROM policy_version WHERE tenant_id=?")) {
            q.setString(1, tenant);
            try (ResultSet r = q.executeQuery()) {
              r.next();
              version = r.getLong(1);
            }
          }
          for (String sql :
              List.of(
                  "INSERT INTO policy_version(tenant_id,version,content,digest) SELECT tenant_id,?,content,digest FROM policy_version WHERE tenant_id=? AND version=?",
                  "INSERT INTO role SELECT tenant_id,?,role_name FROM role WHERE tenant_id=? AND version=?",
                  "INSERT INTO role_edge SELECT tenant_id,?,child,parent FROM role_edge WHERE tenant_id=? AND version=?",
                  "INSERT INTO \"grant\" SELECT tenant_id,?,role_name,action,resource_type FROM \"grant\" WHERE tenant_id=? AND version=?")) {
            try (PreparedStatement q = prepare(c, sql)) {
              q.setLong(1, version);
              q.setString(2, tenant);
              q.setLong(3, sourceVersion);
              q.executeUpdate();
            }
          }
          try (PreparedStatement q =
              prepare(
                  c,
                  "INSERT INTO active_policy VALUES(?,?) ON CONFLICT(tenant_id) DO UPDATE SET version=EXCLUDED.version")) {
            q.setString(1, tenant);
            q.setLong(2, version);
            q.executeUpdate();
          }
          audit(
              c,
              tenant,
              new Audit(
                  UUID.randomUUID(),
                  principalRef(principal),
                  "policy:rollback",
                  Long.toString(version),
                  epoch + 1,
                  "PUBLISHED",
                  "COPIED_IMMUTABLE_VERSION"));
          return new Published(version, epoch + 1);
        });
  }

  public long membership(String tenant, Principal actor, Principal target, Membership value) {
    return transaction(
        tenant,
        true,
        c -> {
          long e = epoch(c, tenant, true);
          administrative(c, tenant, actor);
          try (PreparedStatement s =
              prepare(
                  c,
                  "INSERT INTO membership VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,issuer,subject_id) DO UPDATE SET roles=EXCLUDED.roles,department=EXCLUDED.department,clearance=EXCLUDED.clearance,suspended=EXCLUDED.suspended,administrative=EXCLUDED.administrative,active=EXCLUDED.active")) {
            s.setString(1, tenant);
            s.setString(2, target.issuer());
            s.setString(3, target.subject());
            s.setArray(4, c.createArrayOf("text", value.roles().toArray()));
            s.setString(5, value.department());
            s.setObject(6, value.clearance());
            s.setObject(7, value.suspended());
            s.setBoolean(8, value.admin());
            s.setBoolean(9, value.active());
            s.executeUpdate();
          }

          audit(
              c,
              tenant,
              new Audit(
                  UUID.randomUUID(),
                  principalRef(actor),
                  "membership:update",
                  principalRef(target),
                  e + 1,
                  value.active() ? "ACTIVE" : "REVOKED",
                  "ADMINISTRATIVE"));
          return e + 1;
        });
  }

  public Revision readForAdmin(String tenant, Principal p, long version) {
    return transaction(
        tenant,
        false,
        c -> {
          administrative(c, tenant, p);
          return revision(c, tenant, version);
        });
  }

  public List<Audit> audits(String tenant, Principal p) {
    return transaction(
        tenant,
        false,
        c -> {
          administrative(c, tenant, p);
          List<Audit> out = new ArrayList<>();
          try (PreparedStatement s =
              prepare(
                  c,
                  "SELECT audit_id,principal_ref,action,resource_ref,epoch,result,reason FROM audit WHERE tenant_id=? ORDER BY created_at DESC,audit_id LIMIT 100")) {
            s.setString(1, tenant);
            try (ResultSet r = s.executeQuery()) {
              while (r.next())
                out.add(
                    new Audit(
                        r.getObject(1, UUID.class),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getLong(5),
                        r.getString(6),
                        r.getString(7)));
            }
          }
          return List.copyOf(out);
        });
  }

  public <T> T simulate(String tenant, Principal p, Function<Membership, T> work) {
    return transaction(
        tenant,
        false,
        c -> {
          administrative(c, tenant, p);
          return work.apply(membership(c, tenant, p));
        });
  }

  private void audit(Connection c, String tenant, Audit a) throws SQLException {
    try (PreparedStatement s =
        prepare(
            c,
            "INSERT INTO audit(tenant_id,audit_id,principal_ref,action,resource_ref,epoch,result,reason) VALUES(?,?,?,?,?,?,?,?)")) {
      s.setString(1, tenant);
      s.setObject(2, a.id());
      s.setString(3, a.principal());
      s.setString(4, a.action());
      s.setString(5, a.resource());
      s.setLong(6, a.epoch());
      s.setString(7, a.result());
      s.setString(8, a.reason());
      s.executeUpdate();
    }
  }

  public static String principalRef(Principal p) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((p.issuer() + "\0" + p.subject()).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
