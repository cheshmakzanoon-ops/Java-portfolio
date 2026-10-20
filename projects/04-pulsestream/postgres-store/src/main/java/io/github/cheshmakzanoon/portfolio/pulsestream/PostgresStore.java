package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;

/** PostgreSQL is the recovery authority. All batch-visible effects share one fenced transaction. */
public final class PostgresStore {
  public enum Barrier {
    BEFORE_COMMIT,
    AFTER_COMMIT,
    DURING_OUTBOX
  }

  public record Owner(String run, long epoch, Duration lease) {}

  public record WindowRow(
      String tenant,
      String sensor,
      String type,
      long start,
      long end,
      long count,
      long sum,
      long minimum,
      long maximum,
      long revision,
      boolean finalized) {}

  public record Diagnostic(long id, String eventId, String reason, String windowId) {}

  private final String url, user, password;
  private final Consumer<Barrier> barrier;
  private final SqlTransactions transactions = new SqlTransactions(this::connection);
  private java.util.function.BiConsumer<Barrier, Engine.State> stateBarrier = (b, s) -> {};

  public PostgresStore withStateBarrier(java.util.function.BiConsumer<Barrier, Engine.State> hook) {
    this.stateBarrier = hook;
    return this;
  }

  public PostgresStore(String url, String user, String password) {
    this(url, user, password, b -> {});
  }

  public PostgresStore(String url, String user, String password, Consumer<Barrier> barrier) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.barrier = barrier;
  }

  public Connection connection() throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", user);
    p.setProperty("password", password);
    p.setProperty("connectTimeout", "5");
    p.setProperty("socketTimeout", "15");
    p.setProperty("ApplicationName", "pulsestream");
    return DriverManager.getConnection(url, p);
  }

  public void migrate() {
    Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  public Owner acquire(String run, Duration lease) throws SQLException {
    if (!Engine.identifier(run) || lease.toMillis() < 100)
      throw new IllegalArgumentException("owner");
    return transactions.run(
        c -> {
          execute(c, "INSERT INTO run_owner VALUES (?,0,'-infinity') ON CONFLICT DO NOTHING", run);
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT epoch,lease_until<=clock_timestamp() FROM run_owner WHERE run_id=? FOR UPDATE")) {
            bind(p, run);
            try (ResultSet rs = p.executeQuery()) {
              if (!rs.next() || !rs.getBoolean(2)) throw new SQLException("RUN_OWNED");
              long epoch = Math.addExact(rs.getLong(1), 1);
              execute(
                  c,
                  "UPDATE run_owner SET epoch=?,lease_until=clock_timestamp()+(? * interval '1 millisecond') WHERE run_id=?",
                  epoch,
                  lease.toMillis(),
                  run);
              return new Owner(run, epoch, lease);
            }
          }
        });
  }

  private void fence(Connection c, Owner owner) throws SQLException {
    try (PreparedStatement p =
        c.prepareStatement(
            "SELECT epoch,lease_until>clock_timestamp() FROM run_owner WHERE run_id=? FOR UPDATE")) {
      bind(p, owner.run);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next() || rs.getLong(1) != owner.epoch || !rs.getBoolean(2))
          throw new SQLException("FENCED_OWNER");
      }
    }
    renewLocked(c, owner);
  }

  // fence() retains the run_owner row lock until transaction completion. Even if
  // bounded SQL/output work outlasts the lease, no successor can acquire that row
  // while this transaction owns it. Renew again immediately before commit.
  private void renewLocked(Connection c, Owner owner) throws SQLException {
    execute(
        c,
        "UPDATE run_owner SET lease_until=clock_timestamp()+(? * interval '1 millisecond') WHERE run_id=?",
        owner.lease.toMillis(),
        owner.run);
  }

  public void release(Owner owner) throws SQLException {
    try (Connection c = connection()) {
      execute(
          c,
          "UPDATE run_owner SET lease_until='-infinity' WHERE run_id=? AND epoch=?",
          owner.run,
          owner.epoch);
    }
  }

  public Engine.State restore(String run, String topicId, Engine.Limits limits, long now)
      throws SQLException {
    try (Connection c = connection();
        PreparedStatement p =
            c.prepareStatement("SELECT topic_id,snapshot FROM run_state WHERE run_id=?")) {
      bind(p, run);
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return new Engine.State(run, limits, now);
        if (!topicId.equals(rs.getString(1))) throw new SQLException("TOPIC_ID_CHANGED");
        Engine.State state = SnapshotCodec.decode(rs.getBytes(2), Engine.State.class);
        if (!limits.equals(state.limits)) throw new SQLException("RUN_LIMITS_CHANGED");
        try (PreparedStatement seen =
            c.prepareStatement(
                "SELECT tenant_id,event_id,payload_hash FROM seen_event WHERE run_id=?")) {
          bind(seen, run);
          try (ResultSet rows = seen.executeQuery()) {
            while (rows.next())
              state.seen.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
          }
        }
        return state.afterRestart(now);
      }
    }
  }

  public void commit(Owner owner, String topicId, Engine.State previous, Engine.State next)
      throws SQLException {
    if (!owner.run.equals(next.runId)) throw new IllegalArgumentException("run");
    transactions.run(
        c -> {
          fence(c, owner);
          long addedDiagnostics = (long) next.quarantine.size() + next.late.size();
          execute(
              c,
              "INSERT INTO run_usage(run_id,diagnostics) VALUES (?,?) ON CONFLICT(run_id) DO UPDATE SET diagnostics=run_usage.diagnostics+EXCLUDED.diagnostics",
              owner.run,
              addedDiagnostics);
          try (PreparedStatement usage =
              c.prepareStatement("SELECT diagnostics FROM run_usage WHERE run_id=?")) {
            bind(usage, owner.run);
            try (ResultSet count = usage.executeQuery()) {
              count.next();
              if (count.getLong(1) > 6L * next.limits.events())
                throw new SQLException("DIAGNOSTIC_QUOTA_EXHAUSTED");
            }
          }

          try (PreparedStatement p =
              c.prepareStatement("SELECT topic_id FROM run_state WHERE run_id=?")) {
            bind(p, owner.run);
            try (ResultSet rs = p.executeQuery()) {
              if (rs.next() && !topicId.equals(rs.getString(1)))
                throw new SQLException("TOPIC_ID_CHANGED");
            }
          }
          // Snapshot excludes dedup and per-batch delivery deltas; normalized rows own those
          // histories.
          Engine.State snapshot = new Engine.State(next.runId, next.limits, 0);
          snapshot.watermark = next.watermark;
          snapshot.duplicates = next.duplicates;
          snapshot.panes.putAll(next.panes);
          snapshot.results.putAll(next.results);
          snapshot.pending.addAll(next.pending);
          snapshot.admitted.addAll(next.admitted);
          snapshot.offsets.putAll(next.offsets);
          snapshot.partitions.putAll(next.partitions);
          execute(
              c,
              "INSERT INTO run_state VALUES (?,?,?,clock_timestamp()) ON CONFLICT(run_id) DO UPDATE SET snapshot=EXCLUDED.snapshot,committed_at=EXCLUDED.committed_at",
              owner.run,
              topicId,
              SnapshotCodec.encode(snapshot));
          for (var e : next.offsets.entrySet())
            execute(
                c,
                "INSERT INTO checkpoint VALUES (?,?,?,?) ON CONFLICT(run_id,partition_id) DO UPDATE SET next_offset=EXCLUDED.next_offset",
                owner.run,
                topicId,
                e.getKey(),
                e.getValue());
          for (var e : next.partitions.entrySet())
            execute(
                c,
                "INSERT INTO partition_progress VALUES (?,?,?,?,?) ON CONFLICT(run_id,partition_id) DO UPDATE SET max_timestamp=EXCLUDED.max_timestamp,last_activity=EXCLUDED.last_activity,idle=EXCLUDED.idle",
                owner.run,
                e.getKey(),
                e.getValue().maxTimestamp(),
                e.getValue().lastActivity(),
                e.getValue().idle());
          execute(c, "DELETE FROM pane_state WHERE run_id=?", owner.run);
          try (PreparedStatement p =
              c.prepareStatement("INSERT INTO pane_state VALUES (?,?,?,?,?,?,?,?)")) {
            for (var e : next.panes.entrySet()) {
              bind(
                  p,
                  owner.run,
                  e.getKey().key().tenant(),
                  e.getKey().key().sensor(),
                  e.getKey().start(),
                  e.getValue().count(),
                  e.getValue().sum(),
                  e.getValue().minimum(),
                  e.getValue().maximum());
              p.addBatch();
            }
            p.executeBatch();
          }
          try (PreparedStatement p =
              c.prepareStatement("INSERT INTO seen_event VALUES (?,?,?,?)")) {
            for (var e : next.seen.entrySet())
              if (!previous.seen.containsKey(e.getKey())) {
                String[] id = e.getKey().split(":", 2);
                bind(p, owner.run, id[0], id[1], e.getValue());
                p.addBatch();
              }
            p.executeBatch();
          }
          for (var r : next.outbox) {
            var w = r.window();
            var a = r.aggregate();
            execute(
                c,
                "INSERT INTO window_result VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id,tenant_id,sensor_id,type,start_time) DO UPDATE SET count=EXCLUDED.count,sum=EXCLUDED.sum,minimum=EXCLUDED.minimum,maximum=EXCLUDED.maximum,revision=EXCLUDED.revision,final=EXCLUDED.final WHERE NOT window_result.final",
                owner.run,
                w.key().tenant(),
                w.key().sensor(),
                w.type(),
                w.start(),
                w.end(),
                a.count(),
                a.sum(),
                a.minimum(),
                a.maximum(),
                r.revision(),
                r.finalized());
            execute(
                c,
                "INSERT INTO outbox(run_id,window_id,revision,payload) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                owner.run,
                w.id(),
                r.revision(),
                SnapshotCodec.encode(r));
          }
          insertRejections(c, "quarantine", owner.run, next.quarantine);
          insertRejections(c, "late_record", owner.run, next.late);
          stateBarrier.accept(Barrier.BEFORE_COMMIT, next);
          barrier.accept(Barrier.BEFORE_COMMIT);
          renewLocked(c, owner);
          return null;
        });
    stateBarrier.accept(Barrier.AFTER_COMMIT, next);
    barrier.accept(Barrier.AFTER_COMMIT);
  }

  private static void insertRejections(
      Connection c, String table, String run, List<Engine.Rejection> rows) throws SQLException {
    // table is a closed internal constant, never an API input.
    try (PreparedStatement p =
        c.prepareStatement(
            "INSERT INTO "
                + table
                + "(run_id,tenant_id,event_id,reason,window_id) VALUES (?,?,?,?,?)")) {
      for (var r : rows) {
        bind(p, run, r.tenant(), r.eventId(), r.reason(), r.windowId());
        p.addBatch();
      }
      p.executeBatch();
    }
  }

  public int deliverOutbox(Owner owner, Consumer<Engine.Result> publish, int limit)
      throws SQLException {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit");
    return transactions.run(
        c -> {
          fence(c, owner);
          int delivered = 0;
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT window_id,revision,payload FROM outbox WHERE run_id=? AND NOT delivered ORDER BY window_id,revision LIMIT ? FOR UPDATE SKIP LOCKED")) {
            bind(p, owner.run, limit);
            try (ResultSet rs = p.executeQuery()) {
              while (rs.next()) {
                publish.accept(SnapshotCodec.decode(rs.getBytes(3), Engine.Result.class));
                barrier.accept(Barrier.DURING_OUTBOX);
                execute(
                    c,
                    "UPDATE outbox SET delivered=true WHERE run_id=? AND window_id=? AND revision=?",
                    owner.run,
                    rs.getString(1),
                    rs.getLong(2));
                delivered++;
              }
            }
          }
          renewLocked(c, owner);
          return delivered;
        });
  }

  public List<WindowRow> windows(
      String run, String tenant, String sensor, String type, int limit, long after)
      throws SQLException {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit");
    List<WindowRow> result = new ArrayList<>();
    try (Connection c = connection();
        PreparedStatement p =
            c.prepareStatement(
                "SELECT tenant_id,sensor_id,type,start_time,end_time,count,sum,minimum,maximum,revision,final FROM window_result WHERE run_id=? AND tenant_id=? AND sensor_id=? AND type=? AND start_time>? ORDER BY start_time LIMIT ?")) {
      bind(p, run, tenant, sensor, type, after, limit);
      try (ResultSet r = p.executeQuery()) {
        while (r.next())
          result.add(
              new WindowRow(
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getLong(4),
                  r.getLong(5),
                  r.getLong(6),
                  r.getLong(7),
                  r.getLong(8),
                  r.getLong(9),
                  r.getLong(10),
                  r.getBoolean(11)));
      }
    }
    return result;
  }

  public List<Diagnostic> quarantine(String run, String tenant, long after, int limit)
      throws SQLException {
    if (limit < 1 || limit > 1000 || after < 0) throw new IllegalArgumentException("pagination");
    List<Diagnostic> result = new ArrayList<>();
    try (Connection c = connection();
        PreparedStatement p =
            c.prepareStatement(
                "SELECT id,event_id,reason,window_id FROM quarantine WHERE run_id=? AND tenant_id=? AND id>? ORDER BY id LIMIT ?")) {
      bind(p, run, tenant, after, limit);
      try (ResultSet r = p.executeQuery()) {
        while (r.next())
          result.add(new Diagnostic(r.getLong(1), r.getString(2), r.getString(3), r.getString(4)));
      }
    }
    return result;
  }

  public Map<String, Object> status(String run, String tenant) throws SQLException {
    try (Connection c = connection();
        PreparedStatement p =
            c.prepareStatement(
                "SELECT snapshot,extract(epoch FROM clock_timestamp()-committed_at)*1000 FROM run_state WHERE run_id=?")) {
      bind(p, run);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return Map.of("state", "NOT_STARTED");
        var s = SnapshotCodec.decode(r.getBytes(1), Engine.State.class);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("watermark", s.watermark);
        status.put("partitions", s.partitions);
        status.put("checkpoint", s.offsets);
        status.put("checkpointAgeMillis", r.getDouble(2));
        status.put("duplicateCount", s.duplicates);
        status.put("openPanes", s.panes.size());
        status.put(
            "activePartitions", s.partitions.values().stream().filter(v -> !v.idle()).count());
        status.put(
            "idlePartitions",
            s.partitions.values().stream().filter(Engine.Partition::idle).count());
        Map<String, Long> late = new TreeMap<>();
        try (PreparedStatement counts =
            c.prepareStatement(
                "SELECT window_id,count(*) FROM late_record WHERE run_id=? AND tenant_id=? GROUP BY window_id ORDER BY window_id LIMIT 101")) {
          bind(counts, run, tenant);
          try (ResultSet rows = counts.executeQuery()) {
            while (rows.next()) late.put(rows.getString(1), rows.getLong(2));
          }
        }
        status.put("lateContributionsTruncated", late.size() > 100);
        if (late.size() > 100)
          late.remove(late.keySet().stream().max(String::compareTo).orElseThrow());
        status.put("lateContributions", late);
        return status;
      }
    }
  }

  public static void validateRetainedPositions(
      Map<Integer, Long> next, Map<Integer, Long> beginning, Map<Integer, Long> end) {
    next.forEach(
        (p, n) -> {
          if (!beginning.containsKey(p) || n < beginning.get(p) || n > end.get(p))
            throw new IllegalStateException("SOURCE_POSITION_UNAVAILABLE partition=" + p);
        });
  }

  private static void execute(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      p.executeUpdate();
    }
  }

  private static void bind(PreparedStatement p, Object... args) throws SQLException {
    p.setQueryTimeout(15);
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }
}
