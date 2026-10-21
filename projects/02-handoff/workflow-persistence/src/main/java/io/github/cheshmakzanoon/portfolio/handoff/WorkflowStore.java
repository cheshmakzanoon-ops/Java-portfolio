package io.github.cheshmakzanoon.portfolio.handoff;

import static io.github.cheshmakzanoon.portfolio.handoff.WorkflowSql.*;

import com.fasterxml.jackson.databind.*;
import io.github.cheshmakzanoon.portfolio.handoff.Data.*;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** PostgreSQL is the serialization boundary; no process-local state is authoritative. */
public final class WorkflowStore implements AutoCloseable {
  private final com.zaxxer.hikari.HikariDataSource pool;
  private final String url, user, password;
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final WorkflowReplay replay = new WorkflowReplay(json);
  private final WorkflowHistory history = new WorkflowHistory(json);
  private final JdbcTransactions transactions;
  private final WorkflowInspection inspection;

  public record Claim(
      UUID instance,
      String tenant,
      String task,
      String activity,
      Safety safety,
      String parameters,
      long generation,
      int attempt,
      String worker,
      String credential,
      String semantics,
      Type outputType) {}

  public record View(
      UUID id,
      String tenant,
      String definitionHash,
      String semantics,
      State state,
      long version,
      Map<String, Output> values) {}

  public WorkflowStore(String url, String user, String password) {
    this(url, user, password, () -> {});
  }

  public WorkflowStore(String url, String user, String password, Runnable beforeCommit) {
    this.url = url;
    this.user = user;
    this.password = password;
    var configuration = new com.zaxxer.hikari.HikariConfig();
    configuration.setJdbcUrl(url);
    configuration.setUsername(user);
    configuration.setPassword(password);
    configuration.setMaximumPoolSize(4);
    configuration.setMinimumIdle(0);
    configuration.setConnectionTimeout(10000);
    configuration.setValidationTimeout(3000);
    configuration.setPoolName("handoff-db");
    pool = new com.zaxxer.hikari.HikariDataSource(configuration);
    transactions = new JdbcTransactions(pool, beforeCommit);
    inspection = new WorkflowInspection(transactions, history, replay);
  }

  public void migrate() {
    Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private <T> T tx(JdbcTransactions.Work<T> work) {
    return transactions.execute(work);
  }

  private String encode(Object object) throws Exception {
    return json.writeValueAsString(object);
  }

  private record Deployment(String body, String semantics) {}

  private Deployment deployment(Connection c, String tenant, String hash) throws Exception {
    try (PreparedStatement s =
            statement(
                c,
                "SELECT body,semantics FROM workflow_definition WHERE tenant=? AND hash=?",
                tenant,
                hash);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new IllegalArgumentException("definition missing");
      return new Deployment(r.getString(1), r.getString(2));
    }
  }

  private Definition definition(Connection c, String tenant, String hash) throws Exception {
    Deployment d = deployment(c, tenant, hash);
    if (!Set.of("2", Interpreter.SEMANTICS).contains(d.semantics()))
      throw new IllegalArgumentException("definition is pinned to legacy semantics");
    return json.readValue(d.body(), Definition.class);
  }

  public String deploy(String tenant, Definition definition) {
    definition.validate();
    return tx(
        c -> {
          String hash = definition.contentHash();
          int n =
              update(
                  c,
                  "INSERT INTO workflow_definition(tenant,name,version,hash,body,semantics) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                  tenant,
                  definition.name(),
                  definition.version(),
                  hash,
                  encode(definition),
                  Interpreter.SEMANTICS);
          if (n == 0) {
            try (PreparedStatement s =
                    statement(
                        c,
                        "SELECT hash FROM workflow_definition WHERE tenant=? AND name=? AND version=?",
                        tenant,
                        definition.name(),
                        definition.version());
                ResultSet r = s.executeQuery()) {
              if (!r.next() || !r.getString(1).equals(hash))
                throw new IllegalArgumentException("immutable version conflict");
            }
          }
          return hash;
        });
  }

  public UUID start(String tenant, String hash, String key, Map<String, String> context) {
    return start(tenant, hash, key, ExecutionInput.textContext(context));
  }

  public UUID start(String tenant, String hash, String key, ExecutionInput input) {
    boundedKey(key);
    if (hash == null || !hash.matches("[a-f0-9]{64}") || input == null)
      throw new IllegalArgumentException("typed input required");
    return tx(
        c -> {
          Deployment selected = deployment(c, tenant, hash);
          String body;
          if (Set.of("2", Interpreter.SEMANTICS).contains(selected.semantics())) {
            Definition definition = json.readValue(selected.body(), Definition.class).validate();
            input.validate(definition);
            body = encode(input);
          } else if (selected.semantics().equals("1")) {
            Map<String, String> legacy = new TreeMap<>();
            for (var entry : input.context().entrySet()) {
              if (entry.getValue().type() != Type.TEXT)
                throw new IllegalArgumentException("legacy context accepts TEXT only");
              legacy.put(entry.getKey(), (String) entry.getValue().data());
            }
            body = encode(legacy);
          } else throw new IllegalStateException("unsupported pinned semantics");
          if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > WorkflowLimits.EXECUTION_INPUT_BYTES)
            throw new IllegalArgumentException("input byte cap");
          String request = hash + ":" + body;
          try (PreparedStatement s =
                  statement(
                      c,
                      "SELECT id,request_hash FROM workflow_instance WHERE tenant=? AND start_key=?",
                      tenant,
                      key);
              ResultSet r = s.executeQuery()) {
            if (r.next()) {
              if (!request.equals(r.getString(2)))
                throw new IllegalArgumentException("idempotency conflict");
              return r.getObject(1, UUID.class);
            }
          }
          // Serialize tenant quota and concurrent duplicate starts without taking a global lock.
          try (PreparedStatement lock =
              statement(c, "SELECT pg_advisory_xact_lock(hashtext(?))", tenant)) {
            lock.execute();
          }
          try (PreparedStatement s =
                  statement(
                      c,
                      "SELECT id,request_hash FROM workflow_instance WHERE tenant=? AND start_key=?",
                      tenant,
                      key);
              ResultSet r = s.executeQuery()) {
            if (r.next()) {
              if (!request.equals(r.getString(2)))
                throw new IllegalArgumentException("idempotency conflict");
              return r.getObject(1, UUID.class);
            }
          }
          try (PreparedStatement s =
                  statement(
                      c,
                      "SELECT count(*) FROM workflow_instance WHERE tenant=? AND state NOT IN ('COMPLETED','FAILED','CANCELLED')",
                      tenant);
              ResultSet r = s.executeQuery()) {
            r.next();
            if (r.getLong(1) >= 20000) throw new IllegalArgumentException("tenant execution quota");
          }
          try (PreparedStatement quota =
                  statement(
                      c,
                      "SELECT count(*) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND t.status IN ('READY','LEASED')",
                      tenant);
              ResultSet count = quota.executeQuery()) {
            count.next();
            if (count.getLong(1) >= 100000)
              throw new IllegalArgumentException("tenant queue quota");
          }
          UUID id = UUID.randomUUID();
          update(
              c,
              "INSERT INTO workflow_instance(id,tenant,definition_hash,semantics,input,start_key,request_hash,state) VALUES(?,?,?,?,?,?,?,'RUNNING')",
              id,
              tenant,
              hash,
              selected.semantics(),
              body,
              key,
              request);
          append(c, id, EventType.STARTED, "", "started");
          schedule(c, id);
          return id;
        });
  }

  private static void boundedKey(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9_-]{1,128}"))
      throw new IllegalArgumentException("idempotency key required");
  }

  private View lock(Connection c, String tenant, UUID id) throws Exception {
    try (PreparedStatement s =
            statement(
                c,
                "SELECT definition_hash,semantics,state,version FROM workflow_instance WHERE id=? AND tenant=? FOR UPDATE",
                id,
                tenant);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new NoSuchElementException("execution missing");
      return new View(
          id,
          tenant,
          r.getString(1),
          r.getString(2),
          State.valueOf(r.getString(3)),
          r.getLong(4),
          Map.of());
    }
  }

  private void append(Connection c, UUID id, EventType type, String task, String value)
      throws Exception {
    history.append(c, id, type, task, value);
  }

  private void append(
      Connection c, UUID id, EventType type, String task, String value, List<Value> result)
      throws Exception {
    history.append(c, id, type, task, value, result);
  }

  private List<Event> history(Connection c, UUID id) throws Exception {
    return history.read(c, id);
  }

  public List<Event> history(String tenant, UUID id) {
    return inspection.history(tenant, id);
  }

  public HistoryPage historyPage(
      String tenant, UUID id, long after, Long snapshotVersion, int limit) {
    return inspection.page(tenant, id, after, snapshotVersion, limit);
  }

  private Result schedule(Connection c, UUID id) throws Exception {
    String tenant, hash, input, semantics;
    try (PreparedStatement s =
            statement(
                c,
                "SELECT tenant,definition_hash,input,semantics FROM workflow_instance WHERE id=?",
                id);
        ResultSet r = s.executeQuery()) {
      r.next();
      tenant = r.getString(1);
      hash = r.getString(2);
      input = r.getString(3);
      semantics = r.getString(4);
    }
    Result result =
        replay.replay(semantics, deployment(c, tenant, hash).body(), history(c, id), input);
    // Cancellation waits for all in-flight leases to settle before selecting reverse-order undo.
    if (result.state() == State.COMPENSATING
        || result.state() == State.CANCELLED
        || result.state() == State.CANCEL_REQUESTED
        || result.state() == State.FAILED) {
      try (PreparedStatement leased =
              statement(
                  c,
                  "SELECT count(*) FROM activity_task WHERE instance_id=? AND status='LEASED' AND logical_id NOT LIKE 'comp-%'",
                  id);
          ResultSet remaining = leased.executeQuery()) {
        remaining.next();
        if (remaining.getInt(1) > 0)
          result = new Result(State.CANCEL_REQUESTED, List.of(), result.values());
      }
    }
    update(c, "UPDATE workflow_instance SET state=? WHERE id=?", result.state().name(), id);
    if (result.state() == State.COMPENSATING
        || result.state() == State.CANCELLED
        || result.state() == State.CANCEL_REQUESTED
        || result.state() == State.FAILED
        || result.state() == State.COMPENSATION_FAILED) {
      update(
          c,
          "UPDATE activity_task SET status='CANCELLED' WHERE instance_id=? AND status='READY' AND logical_id NOT LIKE 'comp-%'",
          id);
      update(c, "UPDATE timer SET status='CANCELLED' WHERE instance_id=? AND status='WAITING'", id);
    }
    int additional = 0;
    for (Decision decision : result.decisions())
      if (decision.kind() == Kind.ACTIVITY) {
        try (PreparedStatement exists =
                statement(
                    c,
                    "SELECT 1 FROM activity_task WHERE instance_id=? AND logical_id=?",
                    id,
                    decision.id());
            ResultSet row = exists.executeQuery()) {
          if (!row.next()) additional++;
        }
      }
    if (additional > 0) {
      try (PreparedStatement quotaLock =
          statement(c, "SELECT pg_advisory_xact_lock(hashtext(?))", tenant)) {
        quotaLock.execute();
      }
      try (PreparedStatement quota =
              statement(
                  c,
                  "SELECT count(*) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND t.status IN ('READY','LEASED')",
                  tenant);
          ResultSet count = quota.executeQuery()) {
        count.next();
        if (count.getLong(1) + additional > 100000)
          throw new IllegalArgumentException("tenant queue quota");
      }
    }
    for (Decision choice : result.decisions()) {
      if (choice.kind() == Kind.ACTIVITY) {
        String parameters = semantics.equals("1") ? input : encode(choice.input());
        if (parameters.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > WorkflowLimits.TASK_INPUT_BYTES)
          throw new IllegalArgumentException("task input byte cap");
        update(
            c,
            "INSERT INTO activity_task(instance_id,logical_id,activity,safety,parameters,status,due_at,output_type) VALUES(?,?,?,?,?,'READY',?,?) ON CONFLICT DO NOTHING",
            id,
            choice.id(),
            choice.activity(),
            choice.safety().name(),
            parameters,
            Timestamp.from(choice.due()),
            choice.outputType().name());
      }
      if (choice.kind() == Kind.TIMER || choice.kind() == Kind.SIGNAL)
        update(
            c,
            "INSERT INTO timer(instance_id,logical_id,due_at,status) VALUES(?,?,?,'WAITING') ON CONFLICT DO NOTHING",
            id,
            choice.id(),
            Timestamp.from(choice.due()));
    }
    return result;
  }

  public View get(String tenant, UUID id) {
    return inspection.get(tenant, id);
  }

  public List<Claim> claim(String tenant, String worker, int batch, int leaseSeconds) {
    if (batch < 1
        || batch > 16
        || leaseSeconds < 1
        || leaseSeconds > 120
        || worker == null
        || !worker.matches("[a-zA-Z0-9-]{1,64}"))
      throw new IllegalArgumentException("claim bounds");
    return tx(
        c -> {
          List<Claim> claims = new ArrayList<>();
          List<UUID> instances = new ArrayList<>();
          // Match the instance-before-task lock order used by cancellation/completion.
          try (PreparedStatement eligible =
                  statement(
                      c,
                      "SELECT i.id FROM workflow_instance i WHERE i.tenant=? AND i.scheduler_retry_at<=clock_timestamp() AND i.state IN ('RUNNING','COMPENSATING') AND EXISTS(SELECT 1 FROM activity_task t WHERE t.instance_id=i.id AND t.status='READY' AND t.due_at<=clock_timestamp()) ORDER BY i.id FOR UPDATE SKIP LOCKED LIMIT ?",
                      tenant,
                      batch);
              ResultSet rows = eligible.executeQuery()) {
            while (rows.next()) instances.add(rows.getObject(1, UUID.class));
          }
          for (UUID id : instances) {
            if (claims.size() >= batch) break;
            try (PreparedStatement s =
                    statement(
                        c,
                        "SELECT logical_id,activity,safety,parameters,generation,attempt,output_type FROM activity_task WHERE instance_id=? AND status='READY' AND due_at<=clock_timestamp() ORDER BY due_at,logical_id FOR UPDATE SKIP LOCKED LIMIT ?",
                        id,
                        batch - claims.size());
                ResultSet r = s.executeQuery()) {
              while (r.next()) {
                String task = r.getString(1), credential = UUID.randomUUID().toString();
                long generation = r.getLong(5) + 1;
                int attempt = r.getInt(6) + 1;
                update(
                    c,
                    "UPDATE activity_task SET status='LEASED',generation=?,attempt=?,worker=?,credential=?,lease_until=clock_timestamp()+(? * interval '1 second'),dispatch_intent=false WHERE instance_id=? AND logical_id=?",
                    generation,
                    attempt,
                    worker,
                    credential,
                    leaseSeconds,
                    id,
                    task);
                update(
                    c,
                    "INSERT INTO activity_attempt(instance_id,logical_id,generation,worker) VALUES(?,?,?,?)",
                    id,
                    task,
                    generation,
                    worker);
                claims.add(
                    new Claim(
                        id,
                        tenant,
                        task,
                        r.getString(2),
                        Safety.valueOf(r.getString(3)),
                        r.getString(4),
                        generation,
                        attempt,
                        worker,
                        credential,
                        getSemantics(c, id),
                        Type.valueOf(r.getString(7))));
              }
            }
          }
          return claims;
        });
  }

  private void authorize(Connection c, Claim q) throws Exception {
    try (PreparedStatement s =
            statement(
                c,
                "SELECT 1 FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE instance_id=? AND logical_id=? AND generation=? AND attempt=? AND worker=? AND credential=? AND status='LEASED' AND lease_until>clock_timestamp() AND safety=? AND activity=? AND parameters=? AND output_type=? AND i.semantics=? FOR UPDATE OF t",
                q.instance(),
                q.task(),
                q.generation(),
                q.attempt(),
                q.worker(),
                q.credential(),
                q.safety().name(),
                q.activity(),
                q.parameters(),
                q.outputType().name(),
                q.semantics());
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new IllegalStateException("STALE_GENERATION");
    }
  }

  public void heartbeat(Claim q, int seconds) {
    if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("lease cap");
    tx(
        c -> {
          lock(c, q.tenant(), q.instance());
          authorize(c, q);
          update(
              c,
              "UPDATE activity_task SET lease_until=clock_timestamp()+(? * interval '1 second') WHERE instance_id=? AND logical_id=?",
              seconds,
              q.instance(),
              q.task());
          return null;
        });
  }

  public void dispatch(Claim q) {
    tx(
        c -> {
          View view = lock(c, q.tenant(), q.instance());
          authorize(c, q);
          if ((q.task().startsWith("comp-") && view.state() != State.COMPENSATING)
              || (!q.task().startsWith("comp-") && view.state() != State.RUNNING))
            throw new IllegalStateException("dispatch paused");
          int authorized =
              update(
                  c,
                  "UPDATE activity_task SET dispatch_intent=true WHERE instance_id=? AND logical_id=? AND (safety<>'NON_IDEMPOTENT' OR dispatch_intent=false)",
                  q.instance(),
                  q.task());
          if (authorized != 1) throw new IllegalStateException("DISPATCH_ALREADY_AUTHORIZED");
          update(
              c,
              "UPDATE activity_attempt SET dispatched_at=clock_timestamp() WHERE instance_id=? AND logical_id=? AND generation=?",
              q.instance(),
              q.task(),
              q.generation());
          append(c, q.instance(), EventType.DISPATCHED, q.task(), "authorized");
          return null;
        });
  }

  public void complete(Claim q, String result) {
    complete(q, List.of(Value.text(result)));
  }

  public void complete(Claim q, List<Value> result) {
    Output.of(result).require(q.outputType());
    tx(
        c -> {
          lock(c, q.tenant(), q.instance());
          authorize(c, q);
          if (Set.of("2", Interpreter.SEMANTICS).contains(q.semantics()))
            Data.activityResult(
                q.activity(),
                q.outputType(),
                json.readValue(q.parameters(), TaskInput.class).arguments().size(),
                result);
          else if (result.size() != 1)
            throw new IllegalArgumentException("legacy scalar result required");
          update(
              c,
              "UPDATE activity_task SET status='COMPLETED' WHERE instance_id=? AND logical_id=?",
              q.instance(),
              q.task());
          update(
              c,
              "UPDATE activity_attempt SET completed_at=clock_timestamp(),outcome='COMPLETED' WHERE instance_id=? AND logical_id=? AND generation=?",
              q.instance(),
              q.task(),
              q.generation());
          append(
              c,
              q.instance(),
              EventType.COMPLETED,
              q.task(),
              q.semantics().equals("1") ? (String) result.getFirst().data() : "completed",
              result);
          obligation(c, q.tenant(), q.instance(), q.task());
          schedule(c, q.instance());
          return null;
        });
  }

  private void obligation(Connection c, String tenant, UUID id, String task) throws Exception {
    if (getSemantics(c, id).equals("1")) {
      LegacyDefinitionV1 d =
          json.readValue(deployment(c, tenant, getHash(c, id)).body(), LegacyDefinitionV1.class);
      for (var n : d.nodes())
        if (n.id().equals(task) && n.compensation() != null)
          update(
              c,
              "INSERT INTO compensation_obligation(instance_id,logical_id,activity,completion_sequence) SELECT id,?,?,version FROM workflow_instance WHERE id=? ON CONFLICT DO NOTHING",
              n.id(),
              n.compensation(),
              id);
    } else {
      Definition d = definition(c, tenant, getHash(c, id));
      for (Node n : d.nodes())
        if (n.id().equals(task) && n.compensation() != null)
          update(
              c,
              "INSERT INTO compensation_obligation(instance_id,logical_id,activity,completion_sequence) SELECT id,?,?,version FROM workflow_instance WHERE id=? ON CONFLICT DO NOTHING",
              n.id(),
              n.compensation(),
              id);
    }
    if (task.startsWith("comp-"))
      update(
          c,
          "UPDATE compensation_obligation SET status='COMPLETED' WHERE instance_id=? AND logical_id=?",
          id,
          task.substring(5));
  }

  private String getSemantics(Connection c, UUID id) throws Exception {
    try (PreparedStatement s =
            statement(c, "SELECT semantics FROM workflow_instance WHERE id=?", id);
        ResultSet r = s.executeQuery()) {
      if (!r.next()) throw new NoSuchElementException("execution missing");
      return r.getString(1);
    }
  }

  private String getHash(Connection c, UUID id) throws Exception {
    try (PreparedStatement s =
            statement(c, "SELECT definition_hash FROM workflow_instance WHERE id=?", id);
        ResultSet r = s.executeQuery()) {
      r.next();
      return r.getString(1);
    }
  }

  public void fail(Claim q, boolean definitive, String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 256)
      throw new IllegalArgumentException("failure reason required");
    tx(
        c -> {
          lock(c, q.tenant(), q.instance());
          authorize(c, q);
          boolean dispatched;
          try (PreparedStatement s =
                  statement(
                      c,
                      "SELECT dispatch_intent FROM activity_task WHERE instance_id=? AND logical_id=?",
                      q.instance(),
                      q.task());
              ResultSet r = s.executeQuery()) {
            r.next();
            dispatched = r.getBoolean(1);
          }
          failure(
              c, q.instance(), q.task(), q.safety(), q.attempt(), dispatched, definitive, reason);
          schedule(c, q.instance());
          return null;
        });
  }

  private void failure(
      Connection c,
      UUID id,
      String task,
      Safety safety,
      int attempt,
      boolean dispatched,
      boolean definitive,
      String reason)
      throws Exception {
    ActivityFailure decision =
        ActivityFailure.decide(id, task, safety, attempt, dispatched, definitive, reason);
    update(
        c,
        "UPDATE activity_attempt SET completed_at=clock_timestamp(),outcome=? WHERE instance_id=? AND logical_id=? AND generation=(SELECT generation FROM activity_task WHERE instance_id=? AND logical_id=?)",
        decision.attemptOutcome(),
        id,
        task,
        id,
        task);
    if (task.startsWith("comp-") && decision.disposition() != ActivityFailure.Disposition.RETRY)
      update(
          c,
          "UPDATE compensation_obligation SET status=? WHERE instance_id=? AND logical_id=?",
          decision.disposition() == ActivityFailure.Disposition.UNKNOWN_OUTCOME
              ? "UNKNOWN_OUTCOME"
              : "FAILED",
          id,
          task.substring(5));
    if (decision.disposition() == ActivityFailure.Disposition.UNKNOWN_OUTCOME) {
      update(
          c,
          "UPDATE activity_task SET status='UNKNOWN_OUTCOME' WHERE instance_id=? AND logical_id=?",
          id,
          task);
      append(c, id, EventType.UNKNOWN_OUTCOME, task, "external result uncertain");
    } else if (decision.disposition() == ActivityFailure.Disposition.FAILED) {
      update(
          c,
          "UPDATE activity_task SET status='FAILED' WHERE instance_id=? AND logical_id=?",
          id,
          task);
      append(c, id, EventType.FAILED, task, reason);
    } else {
      update(
          c,
          "UPDATE activity_task SET status='READY',due_at=clock_timestamp()+(? * interval '1 millisecond') WHERE instance_id=? AND logical_id=?",
          decision.retryDelayMillis(),
          id,
          task);
    }
  }

  public record SchedulerFailure(UUID instance, String category) {}

  public record SchedulerReport(int processed, List<SchedulerFailure> failures) {
    public SchedulerReport {
      failures = List.copyOf(failures);
    }
  }

  public int tick(int limit) {
    return tickReport(limit).processed();
  }

  public SchedulerReport tickReport(int limit) {
    return new SchedulerBatch(transactions, this::expireDueWork).tick(limit);
  }

  private void expireDueWork(Connection c, UUID id) throws Exception {
    try (PreparedStatement s =
            statement(
                c,
                "SELECT logical_id FROM timer WHERE instance_id=? AND status='WAITING' AND due_at<=clock_timestamp() FOR UPDATE",
                id);
        ResultSet r = s.executeQuery()) {
      while (r.next()) {
        String task = r.getString(1);
        update(c, "UPDATE timer SET status='FIRED' WHERE instance_id=? AND logical_id=?", id, task);
        append(c, id, EventType.TIMER_FIRED, task, "timeout");
      }
    }
    try (PreparedStatement s =
            statement(
                c,
                "SELECT logical_id,safety,attempt,dispatch_intent FROM activity_task WHERE instance_id=? AND status='LEASED' AND lease_until<=clock_timestamp() FOR UPDATE",
                id);
        ResultSet r = s.executeQuery()) {
      while (r.next())
        failure(
            c,
            id,
            r.getString(1),
            Safety.valueOf(r.getString(2)),
            r.getInt(3),
            r.getBoolean(4),
            false,
            "lease expired");
    }
    schedule(c, id);
  }

  public boolean signal(String tenant, UUID id, String key, String signal, String payload) {
    return signal(tenant, id, key, signal, Value.text(payload));
  }

  public boolean signal(String tenant, UUID id, String key, String signal, Value typedPayload) {
    boundedKey(key);
    if (signal == null || !signal.matches("[a-z][a-z0-9-]{0,63}") || typedPayload == null)
      throw new IllegalArgumentException("signal bounds");
    return tx(
        c -> {
          View view = lock(c, tenant, id);
          String payload;
          if (view.semantics().equals("1")) {
            if (typedPayload.type() != Type.TEXT)
              throw new IllegalArgumentException("legacy signal requires TEXT");
            payload = (String) typedPayload.data();
          } else {
            Definition d = definition(c, tenant, view.definitionHash());
            Node target =
                d.nodes().stream()
                    .filter(n -> n.kind() == Kind.SIGNAL && signal.equals(n.signal()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown signal"));
            if (typedPayload.type() != target.outputType())
              throw new IllegalArgumentException("signal type mismatch");
            payload = encode(typedPayload);
          }

          try (PreparedStatement s =
                  statement(
                      c,
                      "SELECT signal,payload,accepted FROM signal_inbox WHERE instance_id=? AND request_key=?",
                      id,
                      key);
              ResultSet r = s.executeQuery()) {
            if (r.next()) {
              if (!r.getString(1).equals(signal) || !r.getString(2).equals(payload))
                throw new IllegalArgumentException("signal idempotency conflict");
              return r.getBoolean(3);
            }
          }
          Result state = schedule(c, id);
          Decision choice =
              state.decisions().stream()
                  .filter(d -> d.kind() == Kind.SIGNAL && signal.equals(d.signal()))
                  .findFirst()
                  .orElse(null);
          boolean accepted = false;
          if (choice != null) {
            int n =
                update(
                    c,
                    "UPDATE timer SET status='SIGNALED' WHERE instance_id=? AND logical_id=? AND status='WAITING'",
                    id,
                    choice.id());
            accepted = n == 1;
            if (accepted)
              append(
                  c,
                  id,
                  EventType.SIGNAL,
                  choice.id(),
                  view.semantics().equals("1") ? payload : "signal received",
                  List.of(typedPayload));
          }
          if (!accepted) append(c, id, EventType.LATE_EVENT, "", "late signal:" + signal);
          update(
              c,
              "INSERT INTO signal_inbox(instance_id,request_key,signal,payload,accepted) VALUES(?,?,?,?,?)",
              id,
              key,
              signal,
              payload,
              accepted);
          schedule(c, id);
          return accepted;
        });
  }

  public void cancel(String tenant, UUID id) {
    tx(
        c -> {
          View view = lock(c, tenant, id);
          if (view.state() == State.COMPLETED
              || view.state() == State.CANCELLED
              || view.state() == State.FAILED)
            throw new IllegalArgumentException("terminal execution");
          List<Value> evidence = List.of();
          if (view.semantics().equals(Interpreter.SEMANTICS)) {
            try (PreparedStatement query =
                    statement(
                        c,
                        "SELECT count(*) FROM activity_task WHERE instance_id=? AND status='LEASED' AND logical_id NOT LIKE 'comp-%'",
                        id);
                ResultSet count = query.executeQuery()) {
              count.next();
              evidence = List.of(Value.integer(count.getLong(1)));
            }
          }
          append(c, id, EventType.CANCELLED, "", "cooperative cancellation", evidence);
          schedule(c, id);
          return null;
        });
  }

  public void resolve(
      String tenant, UUID id, String task, String decision, String operator, String reason) {
    resolve(
        tenant,
        id,
        task,
        decision,
        operator,
        reason,
        "APPLIED".equals(decision) ? List.of(Value.text("resolved")) : List.of());
  }

  public void resolve(
      String tenant,
      UUID id,
      String task,
      String decision,
      String operator,
      String reason,
      List<Value> result) {
    if (!Set.of("APPLIED", "NOT_APPLIED").contains(decision)
        || reason == null
        || reason.isBlank()
        || reason.length() > 256) throw new IllegalArgumentException("resolution reason required");
    tx(
        c -> {
          lock(c, tenant, id);
          try (PreparedStatement expected =
                  statement(
                      c,
                      "SELECT t.output_type,t.activity,t.parameters,i.semantics FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE t.instance_id=? AND t.logical_id=?",
                      id,
                      task);
              ResultSet row = expected.executeQuery()) {
            if (!row.next()) throw new IllegalArgumentException("task missing");
            if ("APPLIED".equals(decision)) {
              Output.of(result).require(Type.valueOf(row.getString(1)));
              if (Set.of("2", Interpreter.SEMANTICS).contains(row.getString(4)))
                Data.activityResult(
                    row.getString(2),
                    Type.valueOf(row.getString(1)),
                    json.readValue(row.getString(3), TaskInput.class).arguments().size(),
                    result);
              else if (result.size() != 1)
                throw new IllegalArgumentException("legacy scalar result required");
            } else if (result == null || !result.isEmpty())
              throw new IllegalArgumentException("NOT_APPLIED has no result");
          }

          int n =
              update(
                  c,
                  "UPDATE activity_task SET status=? WHERE instance_id=? AND logical_id=? AND status='UNKNOWN_OUTCOME'",
                  decision.equals("APPLIED") ? "COMPLETED" : "FAILED",
                  id,
                  task);
          if (n != 1) throw new IllegalArgumentException("not unknown");
          append(c, id, EventType.OPERATOR_RESOLVED, task, decision, result);
          if (decision.equals("APPLIED")) obligation(c, tenant, id, task);
          else if (task.startsWith("comp-"))
            update(
                c,
                "UPDATE compensation_obligation SET status='FAILED' WHERE instance_id=? AND logical_id=?",
                id,
                task.substring(5));

          update(
              c,
              "INSERT INTO operator_decision(instance_id,sequence,operator_id,task,resolution,reason) SELECT id,version,?,?,?,? FROM workflow_instance WHERE id=?",
              operator,
              task,
              decision,
              reason,
              id);
          schedule(c, id);
          return null;
        });
  }

  public void retryCompensation(
      String tenant, UUID id, String task, String operator, String reason) {
    if (task == null
        || !task.startsWith("comp-")
        || reason == null
        || reason.isBlank()
        || reason.length() > 256)
      throw new IllegalArgumentException("compensation and reason required");
    tx(
        c -> {
          lock(c, tenant, id);
          int count =
              update(
                  c,
                  "UPDATE activity_task SET status='READY',attempt=0,due_at=clock_timestamp() WHERE instance_id=? AND logical_id=? AND status='FAILED'",
                  id,
                  task);
          if (count != 1) throw new IllegalArgumentException("compensation not failed");
          append(c, id, EventType.OPERATOR_RETRY, task, "authorized retry");
          update(
              c,
              "INSERT INTO operator_decision(instance_id,sequence,operator_id,task,resolution,reason) SELECT id,version,?,?,'RETRY_COMPENSATION',? FROM workflow_instance WHERE id=?",
              operator,
              task,
              reason,
              id);
          update(
              c,
              "UPDATE compensation_obligation SET status='PENDING' WHERE instance_id=? AND logical_id=?",
              id,
              task.substring(5));
          schedule(c, id);
          return null;
        });
  }

  public boolean healthy() {
    return tx(
        c -> {
          try (PreparedStatement s = statement(c, "SELECT 1");
              ResultSet result = s.executeQuery()) {
            return result.next() && result.getInt(1) == 1;
          }
        });
  }

  @Override
  public void close() {
    pool.close();
  }
}
