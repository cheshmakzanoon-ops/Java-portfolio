package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.JdbcTransactions.*;
import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;
import static io.github.cheshmakzanoon.portfolio.switchback.Store.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Publication and residual revisions serialize on the active-input row, then plan row. */
final class PlanRepository {
  private final JdbcTransactions database;
  private final ProblemRepository problems;
  private final SolveJobRepository jobs;

  PlanRepository(JdbcTransactions database, ProblemRepository problems, SolveJobRepository jobs) {
    this.database = database;
    this.problems = problems;
    this.jobs = jobs;
  }

  public PlanView plan(String tenant, String id) {
    return database.tx(c -> plan(c, tenant, id));
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
            candidate == null ? null : jobs.candidate(c, tenant, candidate));
      }
    }
  }

  public PlanView publish(String tenant, String id, PublishRequest request) {
    return database.tx(
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
          JobView result = jobs.job(c, tenant, request.solveJobId());
          if (active != request.expectedVersion()
              || result.problemVersion() != active
              || current.revision() != request.expectedRevision()
              || !result.problemId().equals(id))
            throw new Conflict("STALE_PUBLICATION: active input/revision changed");
          if (!Set.of("COMPLETED", "CANCELLED").contains(result.state())
              || result.incumbent() == null)
            throw new Conflict("terminal validated incumbent required");
          Problem p = problems.load(c, tenant, id, active);
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
          problems.event(
              c, p, "PUBLISHED", "job=" + result.id() + " revision=" + (current.revision() + 1));
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
    return database.tx(
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
                        problems.load(c, tenant, id, active),
                        plan.solution().candidate(),
                        at,
                        observations,
                        urgent);
          } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("BLOCKED_"))
              throw new Conflict(e.getMessage());
            throw e;
          }
          problems.snapshot(c, p);
          update(
              c,
              "UPDATE active_problem SET version=? WHERE tenant=? AND problem_id=?",
              p.version(),
              tenant,
              id);
          for (String audit : p.audit()) problems.event(c, p, "REPLAN_AUDIT", audit);
          return p;
        });
  }

  /**
   * Explicit demo reset is tenant scoped; only enabled by the trusted loopback development adapter.
   */
  public void resetDemo() {
    database.tx(
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
