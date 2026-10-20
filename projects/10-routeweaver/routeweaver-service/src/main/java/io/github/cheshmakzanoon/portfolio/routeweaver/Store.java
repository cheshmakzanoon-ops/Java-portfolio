package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** PostgreSQL is the authority for state, leases, fencing, and publication, across processes. */
public final class Store {
  private final DataSource dataSource;

  public Store(DataSource ds) {
    dataSource = ds;
  }

  public static final class Conflict extends RuntimeException {
    public Conflict(String message) {
      super(message);
    }
  }

  public static final class Missing extends RuntimeException {
    public Missing() {
      super("not found");
    }
  }

  public static final class Busy extends RuntimeException {
    public Busy() {
      super("solver admission capacity reached");
    }
  }

  public record SolveRequest(
      long expectedVersion, String requestId, long seed, int budgetMs, int steps) {
    public SolveRequest {
      Model.text(requestId);
      if (expectedVersion < 1 || budgetMs < 1 || budgetMs > 30000 || steps < 1 || steps > 100000)
        throw new IllegalArgumentException("bounded budget/version required");
    }
  }

  public record JobView(
      String tenant,
      UUID id,
      String problemId,
      long problemVersion,
      String inputHash,
      String matrixVersion,
      String algorithmVersion,
      String configVersion,
      String sourceRevision,
      long seed,
      int budgetMs,
      int steps,
      String state,
      long fence,
      Instant leaseUntil,
      UUID bestCandidate,
      String stopCondition,
      String failure,
      Instant createdAt,
      Instant startedAt,
      Instant finishedAt,
      Validation incumbent) {}

  public record PlanView(String id, long revision, long problemVersion, Validation solution) {}

  public record PublishRequest(UUID solveJobId, long expectedVersion, long expectedRevision) {
    public PublishRequest {
      if (solveJobId == null || expectedVersion < 1 || expectedRevision < 0)
        throw new IllegalArgumentException(
            "solve job, positive version and nonnegative revision required");
    }
  }

  @FunctionalInterface
  private interface Tx<T> {
    T run(Connection c) throws SQLException;
  }

  private <T> T tx(Tx<T> action) {
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
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("database operation failed", e);
    }
  }

  public void initialize() {
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

  private static int update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    }
  }

  private static void bind(PreparedStatement s, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++)
      s.setObject(
          i + 1,
          args[i] instanceof Instant at ? OffsetDateTime.ofInstant(at, ZoneOffset.UTC) : args[i]);
  }

  private static String string(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new Missing();
        return r.getString(1);
      }
    }
  }

  public Problem create(Problem p) {
    if (p.version() != 1) throw new IllegalArgumentException("initial version must be 1");
    return tx(
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

  private void snapshot(Connection c, Problem p) throws SQLException {
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
    return tx(c -> load(c, tenant, id, version));
  }

  private Problem load(Connection c, String tenant, String id, long version) throws SQLException {
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
    return tx(
        c ->
            Long.parseLong(
                string(
                    c,
                    "SELECT version FROM active_problem WHERE tenant=? AND problem_id=?",
                    tenant,
                    id)));
  }

  public JobView submit(
      String tenant, String problemId, SolveRequest request, String revision, int maxOutstanding) {
    return tx(
        c -> {
          // Global bounded admission is serialized across processes; the in-memory executor is
          // separately bounded.
          string(c, "SELECT pg_advisory_xact_lock(19000010)::text");
          try {
            JobView old = jobByKey(c, tenant, request.requestId());
            if (!old.problemId().equals(problemId)
                || old.problemVersion() != request.expectedVersion()
                || old.seed() != request.seed()
                || old.budgetMs() != request.budgetMs()
                || old.steps() != request.steps())
              throw new Conflict("idempotency key reused with different request");
            return old;
          } catch (Missing ignored) {
          }
          long version =
              Long.parseLong(
                  string(
                      c,
                      "SELECT version FROM active_problem WHERE tenant=? AND problem_id=? FOR UPDATE",
                      tenant,
                      problemId));
          if (version != request.expectedVersion()) throw new Conflict("stale input version");
          long count =
              Long.parseLong(
                  string(
                      c,
                      "SELECT count(*) FROM solve_job WHERE state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"));
          if (count >= maxOutstanding) throw new Busy();
          Problem p = load(c, tenant, problemId, version);
          UUID id = UUID.randomUUID();
          update(
              c,
              "INSERT INTO solve_job(tenant,id,problem_id,problem_version,input_hash,matrix_version,request_key,algorithm_version,config_version,source_revision,seed,budget_ms,steps,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED')",
              tenant,
              id,
              problemId,
              version,
              p.canonicalHash(),
              p.matrix().version(),
              request.requestId(),
              "nearest-directed-timefold-2.7.1-v1",
              p.objective().version(),
              revision,
              request.seed(),
              request.budgetMs(),
              request.steps());
          return job(c, tenant, id);
        });
  }

  private JobView jobByKey(Connection c, String tenant, String key) throws SQLException {
    UUID id =
        UUID.fromString(
            string(c, "SELECT id FROM solve_job WHERE tenant=? AND request_key=?", tenant, key));
    return job(c, tenant, id);
  }

  public JobView job(String tenant, UUID id) {
    return tx(c -> job(c, tenant, id));
  }

  private JobView job(Connection c, String tenant, UUID id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT * FROM solve_job WHERE tenant=? AND id=?")) {
      bind(s, tenant, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new Missing();
        UUID best = r.getObject("best_candidate", UUID.class);
        Validation incumbent = best == null ? null : candidate(c, tenant, best);
        return new JobView(
            tenant,
            id,
            r.getString("problem_id"),
            r.getLong("problem_version"),
            r.getString("input_hash"),
            r.getString("matrix_version"),
            r.getString("algorithm_version"),
            r.getString("config_version"),
            r.getString("source_revision"),
            r.getLong("seed"),
            r.getInt("budget_ms"),
            r.getInt("steps"),
            r.getString("state"),
            r.getLong("fence"),
            instant(r, "lease_until"),
            best,
            r.getString("stop_condition"),
            r.getString("failure"),
            instant(r, "created_at"),
            instant(r, "started_at"),
            instant(r, "finished_at"),
            incumbent);
      }
    }
  }

  private static Instant instant(ResultSet r, String column) throws SQLException {
    Timestamp v = r.getTimestamp(column);
    return v == null ? null : v.toInstant();
  }

  private Validation candidate(Connection c, String tenant, UUID id) throws SQLException {
    return Json.read(
        string(c, "SELECT validated FROM solution_candidate WHERE tenant=? AND id=?", tenant, id),
        Validation.class);
  }

  public JobView claim(String owner, int leaseSeconds) {
    return tx(
        c -> {
          recover(c);
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT tenant,id FROM solve_job WHERE state='QUEUED' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1")) {
            try (ResultSet r = s.executeQuery()) {
              if (!r.next()) return null;
              String tenant = r.getString(1);
              UUID id = r.getObject(2, UUID.class);
              update(
                  c,
                  "UPDATE solve_job SET state='RUNNING',fence=fence+1,owner=?,lease_until=clock_timestamp()+(?*interval '1 second'),started_at=clock_timestamp() WHERE tenant=? AND id=?",
                  owner,
                  leaseSeconds,
                  tenant,
                  id);
              return job(c, tenant, id);
            }
          }
        });
  }

  public int recover() {
    return tx(this::recover);
  }

  private int recover(Connection c) throws SQLException {
    int cancelled =
        update(
            c,
            "UPDATE solve_job SET state='CANCELLED',fence=fence+1,lease_until=NULL,stop_condition='RESTART_CANCEL_ACK',finished_at=clock_timestamp() WHERE state='CANCEL_REQUESTED' AND lease_until<clock_timestamp()");
    return cancelled
        + update(
            c,
            "UPDATE solve_job SET state='QUEUED',fence=fence+1,owner=NULL,lease_until=NULL,stop_condition='LEASE_EXPIRED_REQUEUED' WHERE state='RUNNING' AND lease_until<clock_timestamp()");
  }

  public boolean heartbeat(JobView job, int leaseSeconds) {
    return tx(
        c ->
            update(
                    c,
                    "UPDATE solve_job SET lease_until=clock_timestamp()+(?*interval '1 second') WHERE tenant=? AND id=? AND fence=? AND state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until>clock_timestamp()",
                    leaseSeconds,
                    job.tenant(),
                    job.id(),
                    job.fence())
                == 1);
  }

  public boolean cancellation(JobView worker) {
    return tx(
        c ->
            !Boolean.parseBoolean(
                string(
                    c,
                    "SELECT CASE WHEN fence=? AND state='RUNNING' AND lease_until>clock_timestamp() THEN 'true' ELSE 'false' END FROM solve_job WHERE tenant=? AND id=?",
                    worker.fence(),
                    worker.tenant(),
                    worker.id())));
  }

  public JobView cancel(String tenant, UUID id) {
    return tx(
        c -> {
          string(c, "SELECT state FROM solve_job WHERE tenant=? AND id=? FOR UPDATE", tenant, id);
          update(
              c,
              "UPDATE solve_job SET state='CANCELLED',stop_condition='QUEUED_CANCELLED',finished_at=clock_timestamp() WHERE tenant=? AND id=? AND state='QUEUED'",
              tenant,
              id);
          update(
              c,
              "UPDATE solve_job SET state='CANCEL_REQUESTED' WHERE tenant=? AND id=? AND state='RUNNING'",
              tenant,
              id);
          return job(c, tenant, id);
        });
  }

  public boolean record(JobView worker, Validation offered) {
    return tx(
        c -> {
          string(
              c,
              "SELECT state FROM solve_job WHERE tenant=? AND id=? FOR UPDATE",
              worker.tenant(),
              worker.id());
          JobView now = job(c, worker.tenant(), worker.id());
          Problem p = load(c, worker.tenant(), worker.problemId(), worker.problemVersion());
          Validation checked = new Validator().validate(p, offered.candidate());
          boolean live =
              now.fence() == worker.fence()
                  && Set.of("RUNNING", "CANCEL_REQUESTED").contains(now.state())
                  && Boolean.parseBoolean(
                      string(
                          c,
                          "SELECT CASE WHEN lease_until>clock_timestamp() THEN 'true' ELSE 'false' END FROM solve_job WHERE tenant=? AND id=?",
                          worker.tenant(),
                          worker.id()));
          boolean authority = live && checked.valid();
          UUID id = UUID.randomUUID();
          update(
              c,
              "INSERT INTO solution_candidate(tenant,id,job_id,fence,authoritative,input_hash,validated) VALUES(?,?,?,?,?,?,?)",
              worker.tenant(),
              id,
              worker.id(),
              worker.fence(),
              authority,
              p.canonicalHash(),
              Json.write(checked));
          if (authority
              && (now.incumbent() == null
                  || checked.score().compareTo(now.incumbent().score()) < 0))
            update(
                c,
                "UPDATE solve_job SET best_candidate=? WHERE tenant=? AND id=? AND fence=?",
                id,
                worker.tenant(),
                worker.id(),
                worker.fence());
          return authority;
        });
  }

  public boolean finish(JobView worker, String failure) {
    return tx(
        c -> {
          string(
              c,
              "SELECT state FROM solve_job WHERE tenant=? AND id=? FOR UPDATE",
              worker.tenant(),
              worker.id());
          JobView now = job(c, worker.tenant(), worker.id());
          if (now.fence() != worker.fence()) return false;
          return update(
                  c,
                  "UPDATE solve_job SET state=CASE WHEN state='CANCEL_REQUESTED' THEN 'CANCELLED' WHEN CAST(? AS text) IS NOT NULL THEN 'FAILED' ELSE 'COMPLETED' END, stop_condition=CASE WHEN state='CANCEL_REQUESTED' THEN 'COOPERATIVE_CANCEL_ACK' WHEN CAST(? AS text) IS NOT NULL THEN 'FAILURE' ELSE 'TIME_OR_STEP_BUDGET' END, failure=?,finished_at=clock_timestamp(),lease_until=NULL WHERE tenant=? AND id=? AND fence=? AND state IN ('RUNNING','CANCEL_REQUESTED') AND lease_until>clock_timestamp()",
                  failure,
                  failure,
                  failure,
                  worker.tenant(),
                  worker.id(),
                  worker.fence())
              == 1;
        });
  }

  public record TracePoint(Instant at, long fence, boolean authoritative, Score score) {}

  public List<TracePoint> trace(String tenant, UUID id, int offset, int limit) {
    if (offset < 0 || offset > 100000 || limit < 1 || limit > 1000)
      throw new IllegalArgumentException("trace pagination bounds");
    return tx(
        c -> {
          job(c, tenant, id);
          List<TracePoint> result = new ArrayList<>();
          try (PreparedStatement statement =
              c.prepareStatement(
                  "SELECT created_at,fence,authoritative,validated FROM solution_candidate WHERE tenant=? AND job_id=? ORDER BY created_at,id OFFSET ? LIMIT ?")) {
            bind(statement, tenant, id, offset, limit);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next())
                result.add(
                    new TracePoint(
                        rows.getTimestamp(1).toInstant(),
                        rows.getLong(2),
                        rows.getBoolean(3),
                        Json.read(rows.getString(4), Validation.class).score()));
            }
          }
          return List.copyOf(result);
        });
  }

  public Validation baseline(String tenant, UUID jobId) {
    return tx(
        c -> {
          job(c, tenant, jobId);
          return Json.read(
              string(
                  c,
                  "SELECT validated FROM solution_candidate WHERE tenant=? AND job_id=? AND authoritative ORDER BY created_at LIMIT 1",
                  tenant,
                  jobId),
              Validation.class);
        });
  }

  public PlanView plan(String tenant, String id) {
    return tx(c -> plan(c, tenant, id));
  }

  private PlanView plan(Connection c, String tenant, String id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT revision,problem_version,candidate_id FROM published_plan WHERE tenant=? AND id=?")) {
      bind(s, tenant, id);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new Missing();
        UUID candidate = r.getObject(3, UUID.class);
        return new PlanView(
            id,
            r.getLong(1),
            r.getLong(2),
            candidate == null ? null : candidate(c, tenant, candidate));
      }
    }
  }

  public PlanView publish(String tenant, String id, PublishRequest request) {
    return tx(
        c -> {
          long active =
              Long.parseLong(
                  string(
                      c,
                      "SELECT version FROM active_problem WHERE tenant=? AND problem_id=? FOR UPDATE",
                      tenant,
                      id));
          string(
              c,
              "SELECT revision FROM published_plan WHERE tenant=? AND id=? FOR UPDATE",
              tenant,
              id);
          PlanView current = plan(c, tenant, id);
          JobView result = job(c, tenant, request.solveJobId());
          if (active != request.expectedVersion()
              || result.problemVersion() != active
              || current.revision() != request.expectedRevision()
              || !result.problemId().equals(id))
            throw new Conflict("STALE_PUBLICATION: active input/revision changed");
          if (!Set.of("COMPLETED", "CANCELLED").contains(result.state())
              || result.incumbent() == null)
            throw new Conflict("terminal validated incumbent required");
          Problem p = load(c, tenant, id, active);
          Validation validation = new Validator().validate(p, result.incumbent().candidate());
          if (!validation.valid() || !p.canonicalHash().equals(result.inputHash()))
            throw new Conflict("invalid candidate identity");
          update(
              c,
              "UPDATE published_plan SET revision=revision+1,problem_version=?,candidate_id=? WHERE tenant=? AND id=? AND revision=?",
              active,
              result.bestCandidate(),
              tenant,
              id,
              current.revision());
          event(c, p, "PUBLISHED", "job=" + result.id() + " revision=" + (current.revision() + 1));
          return plan(c, tenant, id);
        });
  }

  public Problem replan(
      String tenant,
      String id,
      long expectedVersion,
      long expectedRevision,
      Instant at,
      List<Replanner.Observation> observations,
      List<Job> urgent) {
    return tx(
        c -> {
          long active =
              Long.parseLong(
                  string(
                      c,
                      "SELECT version FROM active_problem WHERE tenant=? AND problem_id=? FOR UPDATE",
                      tenant,
                      id));
          string(
              c,
              "SELECT revision FROM published_plan WHERE tenant=? AND id=? FOR UPDATE",
              tenant,
              id);
          PlanView plan = plan(c, tenant, id);
          if (active != expectedVersion
              || plan.revision() != expectedRevision
              || plan.problemVersion() != active) throw new Conflict("stale replan base");
          if (plan.solution() == null) throw new Conflict("published plan required");
          Problem p;
          try {
            p =
                new Replanner()
                    .replan(
                        load(c, tenant, id, active),
                        plan.solution().candidate(),
                        at,
                        observations,
                        urgent);
          } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("BLOCKED_"))
              throw new Conflict(e.getMessage());
            throw e;
          }
          snapshot(c, p);
          update(
              c,
              "UPDATE active_problem SET version=? WHERE tenant=? AND problem_id=?",
              p.version(),
              tenant,
              id);
          for (String audit : p.audit()) event(c, p, "REPLAN_AUDIT", audit);
          return p;
        });
  }

  private void event(Connection c, Problem p, String kind, String detail) throws SQLException {
    update(
        c,
        "INSERT INTO plan_event(tenant,problem_id,problem_version,kind,details) VALUES(?,?,?,?,?)",
        p.tenant(),
        p.id(),
        p.version(),
        kind,
        detail);
  }

  /**
   * Explicit demo reset is tenant scoped; only enabled by the trusted loopback development adapter.
   */
  public void resetDemo() {
    tx(
        c -> {
          string(c, "SELECT pg_advisory_xact_lock(19000010)::text");
          try (PreparedStatement lock =
                  c.prepareStatement(
                      "SELECT problem_id FROM active_problem WHERE tenant='demo' ORDER BY problem_id FOR UPDATE");
              ResultSet ignored = lock.executeQuery()) {
            while (ignored.next()) {
              /* lock every demo input before plan deletion */
            }
          }
          if (Long.parseLong(
                  string(
                      c,
                      "SELECT count(*) FROM solve_job WHERE tenant='demo' AND state IN ('QUEUED','RUNNING','CANCEL_REQUESTED')"))
              > 0) throw new Conflict("cancel and await active demo jobs before reset");
          for (String table :
              List.of(
                  "published_plan",
                  "solution_candidate",
                  "solve_job",
                  "active_problem",
                  "problem_snapshot",
                  "plan_event")) update(c, "DELETE FROM " + table + " WHERE tenant='demo'");
          return null;
        });
  }
}
