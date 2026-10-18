package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.flywaydb.core.Flyway;

public final class LedgerStore implements AutoCloseable {
  private final com.zaxxer.hikari.HikariDataSource pool;
  private final RetryBackoff backoff;
  private final java.util.concurrent.atomic.AtomicLong retries =
      new java.util.concurrent.atomic.AtomicLong();

  public long retryCount() {
    return retries.get();
  }

  public record Failure(String code, boolean temporary) {}

  public static final class LedgerException extends RuntimeException {
    public final String code;
    public final boolean temporary;

    LedgerException(String c, boolean t, Throwable e) {
      super(c, e);
      code = c;
      temporary = t;
    }
  }

  public LedgerStore(String url, String user, String password) {
    this(url, user, password, 32);
  }

  public LedgerStore(String url, String user, String password, int poolSize) {
    backoff = new RetryBackoff();
    var config = new com.zaxxer.hikari.HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(15000);
    config.setInitializationFailTimeout(-1);
    config.setPoolName("clearledger-" + UUID.randomUUID());
    pool = new com.zaxxer.hikari.HikariDataSource(config);
  }

  LedgerStore(com.zaxxer.hikari.HikariDataSource pool, RetryBackoff backoff) {
    this.pool = Objects.requireNonNull(pool);
    this.backoff = Objects.requireNonNull(backoff);
  }

  public void close() {
    pool.close();
  }

  public static void migrate(String url, String user, String password) {
    Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @FunctionalInterface
  public interface SqlWork<T> {
    T run(Connection c) throws SQLException;
  }

  public <T> T tx(SqlWork<T> work) {
    for (int attempt = 0; attempt < 12; attempt++) {
      try (var c = pool.getConnection()) {
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try (var st = c.createStatement()) {
          st.execute("SET LOCAL statement_timeout='15s'");
          st.execute("SET LOCAL lock_timeout='10s'");
        }
        try {
          T result = work.run(c);
          c.commit();
          return result;
        } catch (SQLException | RuntimeException e) {
          c.rollback();
          throw e;
        }
      } catch (SQLException e) {
        if (("40001".equals(e.getSQLState())
                || "40P01".equals(e.getSQLState())
                || "55P03".equals(e.getSQLState())
                || "23505".equals(e.getSQLState()))
            && attempt < 11) {
          try {
            backoff.pause(attempt);
            retries.incrementAndGet();
          } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new LedgerException("INTERRUPTED", true, x);
          }
          continue;
        }
        throw new LedgerException(
            e.getSQLState() != null && e.getSQLState().startsWith("P0")
                ? (e instanceof org.postgresql.util.PSQLException pg
                        && pg.getServerErrorMessage() != null
                    ? pg.getServerErrorMessage().getMessage()
                    : "POSTING_REJECTED")
                : "DATABASE_REJECTED",
            e instanceof SQLTransientException
                || "40001".equals(e.getSQLState())
                || "40P01".equals(e.getSQLState())
                || "55P03".equals(e.getSQLState())
                || "57014".equals(e.getSQLState())
                || e.getSQLState() != null && e.getSQLState().startsWith("08"),
            e);
      }
    }
    throw new IllegalStateException();
  }

  public static int execute(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      return p.executeUpdate();
    }
  }

  public static List<Map<String, String>> query(Connection c, String sql, Object... args)
      throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      bind(p, args);
      try (var r = p.executeQuery()) {
        var list = new ArrayList<Map<String, String>>();
        while (r.next()) {
          var m = new LinkedHashMap<String, String>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
            m.put(r.getMetaData().getColumnLabel(i), r.getString(i));
          list.add(m);
        }
        return list;
      }
    }
  }

  private static void bind(PreparedStatement p, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  public UUID account(UUID tenant, String currency, Journal.Side normal, String name) {
    if (!Money.SCALES.containsKey(currency)
        || name == null
        || name.isBlank()
        || name.length() > 120) throw new IllegalArgumentException("ACCOUNT_INPUT");
    var id = UUID.randomUUID();
    return tx(
        c -> {
          execute(
              c,
              "INSERT INTO ledger.account VALUES(?,?,?,?,?)",
              tenant,
              id,
              currency,
              normal.name(),
              name);
          return id;
        });
  }

  public UUID post(UUID tenant, String key, Journal j) {
    return post(tenant, key, j, null);
  }

  public UUID post(UUID tenant, String key, Journal j, UUID reversal) {
    if (key == null || key.isBlank() || key.length() > 120)
      throw new IllegalArgumentException("KEY_BOUND");
    var id = UUID.randomUUID();
    return tx(c -> postOn(c, tenant, key, j, reversal, id));
  }

  public static UUID postOn(
      Connection c, UUID tenant, String key, Journal j, UUID reversal, UUID id)
      throws SQLException {
    var json = new StringBuilder("[");
    for (var l : j.lines()) {
      if (json.length() > 1) json.append(',');
      json.append("{\"account\":\"")
          .append(l.account())
          .append("\",\"side\":\"")
          .append(l.side())
          .append("\",\"amount\":\"")
          .append(l.minor())
          .append("\"}");
    }
    json.append(']');
    try (var p = c.prepareStatement("SELECT ledger.post_journal(?,?,?,?,?,?,?,?,?::jsonb,?,?)")) {
      bind(
          p,
          new Object[] {
            tenant,
            key,
            j.digest(),
            id,
            j.period(),
            j.date(),
            j.currency(),
            j.reference(),
            json.toString(),
            reversal,
            reversal == null ? "POST" : "REVERSE"
          });
      try (var r = p.executeQuery()) {
        r.next();
        return r.getObject(1, UUID.class);
      }
    }
  }

  public UUID reverse(UUID tenant, UUID original, String key, LocalDate date) {
    return tx(
        c -> {
          var rows =
              query(
                  c,
                  "SELECT j.period_id,j.currency,j.reference,p.account_id,p.side,p.amount FROM ledger.journal j JOIN ledger.posting p ON p.tenant_id=j.tenant_id AND p.journal_id=j.id WHERE j.tenant_id=? AND j.id=? ORDER BY p.line_no",
                  tenant,
                  original);
          if (rows.isEmpty()) throw new IllegalArgumentException("JOURNAL_NOT_FOUND");
          var first = rows.getFirst();
          var lines =
              rows.stream()
                  .map(
                      r ->
                          new Journal.Line(
                              UUID.fromString(r.get("account_id")),
                              Journal.Side.valueOf(r.get("side")).opposite(),
                              new BigInteger(r.get("amount"))))
                  .toList();
          var journal =
              new Journal(
                  UUID.fromString(first.get("period_id")),
                  date,
                  first.get("currency"),
                  "reversal:" + original,
                  lines);
          return postOn(c, tenant, key, journal, original, UUID.randomUUID());
        });
  }

  public void close(UUID tenant, UUID period) {
    tx(
        c -> {
          query(c, "SELECT ledger.close_period(?,?)", tenant, period);
          return null;
        });
  }

  public List<Map<String, String>> receipt(UUID tenant, String key) {
    return tx(
        c ->
            query(
                c,
                "SELECT operation,response FROM ledger.idempotency_receipt WHERE tenant_id=? AND key=? ORDER BY operation",
                tenant,
                key));
  }

  /**
   * Cursor is the last journal date/id/line and fixed recorded-at boundary; later requests preserve
   * it.
   */
  public String visibilitySnapshot() {
    return tx(c -> query(c, "SELECT pg_current_snapshot() AS snapshot").getFirst().get("snapshot"));
  }

  public List<Map<String, String>> statement(
      UUID tenant,
      UUID account,
      LocalDate afterDate,
      UUID afterId,
      int afterLine,
      Instant boundary,
      String snapshot,
      int limit) {
    if (snapshot == null || snapshot.length() > 16384 || !snapshot.matches("[0-9]+:[0-9]+:[0-9,]*"))
      throw new IllegalArgumentException("SNAPSHOT_BOUND");
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("PAGE_BOUND");
    return tx(
        c ->
            query(
                c,
                "SELECT j.id,j.effective_date,j.recorded_at,j.currency,j.reference,p.line_no,p.side,p.amount::text FROM ledger.journal j JOIN ledger.posting p ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE p.tenant_id=? AND p.account_id=? AND pg_visible_in_snapshot(j.recorded_xid,?::pg_snapshot) AND j.recorded_at<=? AND (j.effective_date,j.id,p.line_no)>(?,?,?) ORDER BY j.effective_date,j.id,p.line_no LIMIT ?",
                tenant,
                account,
                snapshot,
                java.sql.Timestamp.from(boundary),
                afterDate,
                afterId,
                afterLine,
                limit));
  }

  public Map<String, String> statementSummary(
      UUID tenant, UUID account, Instant boundary, String snapshot) {
    return tx(
        c -> {
          var accounts =
              query(
                  c,
                  "SELECT currency,normal_side FROM ledger.account WHERE tenant_id=? AND id=?",
                  tenant,
                  account);
          if (accounts.isEmpty()) return Map.of();
          var sums =
              query(
                  c,
                  "SELECT coalesce(sum(CASE p.side WHEN 'DEBIT' THEN p.amount ELSE 0 END),0)::text AS debits,coalesce(sum(CASE p.side WHEN 'CREDIT' THEN p.amount ELSE 0 END),0)::text AS credits FROM ledger.posting p JOIN ledger.journal j ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE p.tenant_id=? AND p.account_id=? AND pg_visible_in_snapshot(j.recorded_xid,?::pg_snapshot) AND j.recorded_at<=?",
                  tenant,
                  account,
                  snapshot,
                  java.sql.Timestamp.from(boundary));
          var accountInfo = accounts.getFirst();
          var totals = sums.getFirst();
          var signed =
              new BigInteger(totals.get("debits")).subtract(new BigInteger(totals.get("credits")));
          var presented = accountInfo.get("normal_side").equals("DEBIT") ? signed : signed.negate();
          return Map.of(
              "currency",
              accountInfo.get("currency"),
              "normalSide",
              accountInfo.get("normal_side"),
              "debitsMinor",
              totals.get("debits"),
              "creditsMinor",
              totals.get("credits"),
              "normalBalanceMinor",
              presented.toString(),
              "normalBalanceDecimal",
              new Money(accountInfo.get("currency"), presented).decimal());
        });
  }

  public List<Map<String, String>> balances(UUID tenant) {
    return tx(
        c ->
            query(
                c,
                "SELECT a.id,a.currency,a.normal_side,coalesce(b.debits,0)::text AS debits,coalesce(b.credits,0)::text AS credits FROM ledger.account a LEFT JOIN ledger.account_balance b ON b.tenant_id=a.tenant_id AND b.account_id=a.id WHERE a.tenant_id=? ORDER BY a.id",
                tenant));
  }
}
