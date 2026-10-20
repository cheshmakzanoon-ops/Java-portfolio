package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.JdbcTransactions.*;
import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;
import static io.github.cheshmakzanoon.portfolio.routeweaver.Store.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable admission, lease generations, candidate authority and terminal job transitions. */
final class SolveJobRepository {
  private final JdbcTransactions database;
  private final ProblemRepository problems;

  SolveJobRepository(JdbcTransactions database, ProblemRepository problems) {
    this.database = database;
    this.problems = problems;
  }

  public JobView submit(
      String tenant, String problemId, SolveRequest request, String revision, int maxOutstanding) {
    Model.text(revision);
    if (maxOutstanding < 1 || maxOutstanding > Dispatcher.MAX_OUTSTANDING)
      throw new IllegalArgumentException("admission capacity outside supported bounds");
    return database.tx(
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
          Problem p = problems.load(c, tenant, problemId, version);
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
    return database.tx(c -> job(c, tenant, id));
  }

  JobView job(Connection c, String tenant, UUID id) throws SQLException {
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

  Validation candidate(Connection c, String tenant, UUID id) throws SQLException {
    return Json.read(
        string(c, "SELECT validated FROM solution_candidate WHERE tenant=? AND id=?", tenant, id),
        Validation.class);
  }

  public JobView claim(String owner, int leaseSeconds) {
    Model.text(owner);
    if (leaseSeconds < 1 || leaseSeconds > 60)
      throw new IllegalArgumentException("lease seconds outside [1,60]");
    return database.tx(
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
    return database.tx(this::recover);
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

  /** Fences a stopped generation before making its retained job available to another process. */
  public boolean release(JobView worker, String reason) {
    Model.text(reason);
    return database.tx(
        c ->
            update(
                    c,
                    "UPDATE solve_job SET state=CASE WHEN state='CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'QUEUED' END,"
                        + "fence=fence+1,owner=NULL,lease_until=NULL,stop_condition=?,"
                        + "finished_at=CASE WHEN state='CANCEL_REQUESTED' THEN clock_timestamp() ELSE NULL END"
                        + " WHERE tenant=? AND id=? AND fence=? AND state IN ('RUNNING','CANCEL_REQUESTED')",
                    reason,
                    worker.tenant(),
                    worker.id(),
                    worker.fence())
                == 1);
  }

  public boolean heartbeat(JobView job, int leaseSeconds) {
    if (leaseSeconds < 1 || leaseSeconds > 60)
      throw new IllegalArgumentException("lease seconds outside [1,60]");
    return database.tx(
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
    return database.tx(
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
    return database.tx(
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
    return database.tx(
        c -> {
          string(
              c,
              "SELECT state FROM solve_job WHERE tenant=? AND id=? FOR UPDATE",
              worker.tenant(),
              worker.id());
          JobView now = job(c, worker.tenant(), worker.id());
          Problem p = problems.load(c, now.tenant(), now.problemId(), now.problemVersion());
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
    return database.tx(
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

  public List<TracePoint> trace(String tenant, UUID id, int offset, int limit) {
    if (offset < 0 || offset > 100000 || limit < 1 || limit > 1000)
      throw new IllegalArgumentException("trace pagination bounds");
    return database.tx(
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
    return database.tx(
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
}
