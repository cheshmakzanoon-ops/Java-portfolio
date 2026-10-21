package io.github.cheshmakzanoon.portfolio.switchback;

import static io.github.cheshmakzanoon.portfolio.switchback.Model.*;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/**
 * Compatible application facade. Repositories own SQL; JdbcTransactions owns connections and
 * rollback. No transaction crosses an HTTP request or solver callback, and no solver object crosses
 * this API.
 */
public final class Store {
  private final JdbcTransactions database;
  private final ProblemRepository problems;
  private final SolveJobRepository jobs;
  private final PlanRepository plans;

  public Store(DataSource dataSource) {
    database = new JdbcTransactions(dataSource);
    problems = new ProblemRepository(database);
    jobs = new SolveJobRepository(database, problems);
    plans = new PlanRepository(database, problems, jobs);
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

  public record TracePoint(Instant at, long fence, boolean authoritative, Score score) {}

  public void initialize() {
    database.initialize();
  }

  public Problem create(Problem p) {
    return problems.create(p);
  }

  public Problem load(String tenant, String id, long version) {
    return problems.load(tenant, id, version);
  }

  public long active(String tenant, String id) {
    return problems.active(tenant, id);
  }

  public JobView submit(
      String tenant, String id, SolveRequest request, String source, int capacity) {
    return jobs.submit(tenant, id, request, source, capacity);
  }

  public JobView job(String tenant, UUID id) {
    return jobs.job(tenant, id);
  }

  public JobView claim(String owner, int leaseSeconds) {
    return jobs.claim(owner, leaseSeconds);
  }

  public int recover() {
    return jobs.recover();
  }

  public boolean release(JobView job, String reason) {
    return jobs.release(job, reason);
  }

  public boolean heartbeat(JobView job, int leaseSeconds) {
    return jobs.heartbeat(job, leaseSeconds);
  }

  public boolean cancellation(JobView job) {
    return jobs.cancellation(job);
  }

  public JobView cancel(String tenant, UUID id) {
    return jobs.cancel(tenant, id);
  }

  public boolean record(JobView job, Validation value) {
    return jobs.record(job, value);
  }

  public boolean finish(JobView job, String failure) {
    return jobs.finish(job, failure);
  }

  public List<TracePoint> trace(String tenant, UUID id, int offset, int limit) {
    return jobs.trace(tenant, id, offset, limit);
  }

  public Validation baseline(String tenant, UUID id) {
    return jobs.baseline(tenant, id);
  }

  public PlanView plan(String tenant, String id) {
    return plans.plan(tenant, id);
  }

  public PlanView publish(String tenant, String id, PublishRequest request) {
    return plans.publish(tenant, id, request);
  }

  public Problem replan(
      String tenant,
      String id,
      long version,
      long revision,
      Instant at,
      List<Replanner.Observation> observations,
      List<Job> urgent) {
    return plans.replan(tenant, id, version, revision, at, observations, urgent);
  }

  public void resetDemo() {
    plans.resetDemo();
  }
}
