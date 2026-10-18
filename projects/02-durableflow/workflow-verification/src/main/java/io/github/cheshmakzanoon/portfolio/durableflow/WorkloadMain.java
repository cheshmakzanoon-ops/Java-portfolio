package io.github.cheshmakzanoon.portfolio.durableflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Closed-loop database boundary driver; independent worker/scheduler processes do the work. */
public final class WorkloadMain {
  private WorkloadMain() {}

  public static void main(String[] args) throws Exception {
    int executions = args.length == 0 ? 10000 : Integer.parseInt(args[0]);
    if (executions < 10 || executions > 10000)
      throw new IllegalArgumentException("10..10000 executions");
    String tenant = System.getenv().getOrDefault("TENANT", "demo"),
        url = System.getenv("DB_URL"),
        user = System.getenv("DB_USER"),
        password = System.getenv("DB_PASSWORD");
    WorkflowStore store = new WorkflowStore(url, user, password);
    String run = "w-" + UUID.randomUUID();
    String[] hashes = new String[2];
    for (int timer = 0; timer < 2; timer++) {
      List<Node> nodes = new ArrayList<>();
      nodes.add(Fixtures.node("approval", Kind.SIGNAL, List.of(), null, null, null, 604800));
      if (timer == 1)
        nodes.add(Fixtures.node("wait", Kind.TIMER, List.of("approval"), null, null, null, 1));
      for (int n = 0; n < 8; n++)
        nodes.add(
            Fixtures.node(
                "a" + n,
                Kind.ACTIVITY,
                List.of(n == 0 ? (timer == 1 ? "wait" : "approval") : "a" + (n - 1)),
                "reserve",
                Safety.IDEMPOTENT_WITH_KEY,
                null,
                0));
      hashes[timer] =
          store.deploy(
              tenant,
              new Definition(
                  "load-" + timer, 1, nodes, Map.of("ordinal", Type.INTEGER, "run", Type.TEXT)));
    }
    long databaseBefore;
    try (Connection c = DriverManager.getConnection(url, user, password);
        Statement query = c.createStatement();
        ResultSet r = query.executeQuery("SELECT pg_database_size(current_database())")) {
      r.next();
      databaseBefore = r.getLong(1);
    }
    Instant startedAt = Instant.now();
    long start = System.nanoTime();
    List<UUID> ids = new ArrayList<>();
    for (int n = 0; n < executions; n++) {
      UUID id =
          store.start(
              tenant,
              hashes[n % 2],
              run + "-" + n,
              new ExecutionInput(
                  Value.text("seed-42"),
                  Map.of("ordinal", Value.integer(n), "run", Value.text(run))));
      ids.add(id);
      if (!store.signal(tenant, id, "approval", "approval", "yes")
          || !store.signal(tenant, id, "approval", "approval", "yes"))
        throw new AssertionError("signal lost");
    }
    long until = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
    long completed = 0;
    List<Map<String, Object>> timeline = new ArrayList<>();
    long maximumQueue = 0;
    while (completed < executions && System.nanoTime() < until) {
      try (Connection c = DriverManager.getConnection(url, user, password);
          PreparedStatement s =
              c.prepareStatement(
                  "SELECT state,count(*) FROM workflow_instance WHERE tenant=? AND start_key LIKE ? GROUP BY state")) {
        s.setString(1, tenant);
        s.setString(2, run + "-%");
        try (ResultSet r = s.executeQuery()) {
          completed = 0;
          while (r.next()) {
            String state = r.getString(1);
            long count = r.getLong(2);
            if (state.equals("COMPLETED")) completed = count;
            if (Set.of("FAILED", "COMPENSATION_FAILED", "MANUAL_REVIEW").contains(state))
              throw new AssertionError("failed workload: " + state);
          }
        }
      }
      long queue;
      try (Connection c = DriverManager.getConnection(url, user, password);
          PreparedStatement q =
              c.prepareStatement(
                  "SELECT count(*) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND t.status IN ('READY','LEASED')")) {
        q.setString(1, tenant);
        q.setString(2, run + "-%");
        try (ResultSet r = q.executeQuery()) {
          r.next();
          queue = r.getLong(1);
        }
      }
      maximumQueue = Math.max(maximumQueue, queue);
      timeline.add(
          Map.of(
              "elapsedSeconds",
              (System.nanoTime() - start) / 1e9,
              "completed",
              completed,
              "queueDepth",
              queue));
      System.out.println("completed " + completed + "/" + executions);
      Thread.sleep(500);
    }
    if (completed != executions) throw new AssertionError("workload deadline exceeded");
    Map<String, Object> measurements = new LinkedHashMap<>();
    measurements.put("elapsedSeconds", (System.nanoTime() - start) / 1e9);
    measurements.put("executions", executions);
    measurements.put("activities", executions * 8);
    measurements.put("timerWaits", executions / 2);
    measurements.put("seed", 42);
    measurements.put("peakSampledQueueDepth", maximumQueue);
    measurements.put("expectedTransientFirstFailures", executions * 8 / 5);
    measurements.put("databaseBytesBefore", databaseBefore);
    measurements.put(
        "driverHeapUsedBytes",
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      for (String[] metric :
          new String[][] {
            {
              "completionLatencyMs",
              "SELECT percentile_cont(ARRAY[0.5,0.95,0.99]) WITHIN GROUP(ORDER BY extract(epoch FROM(h.at-i.created_at))*1000) FROM workflow_instance i JOIN LATERAL(SELECT max(at) at FROM workflow_history WHERE instance_id=i.id)h ON true WHERE i.tenant=? AND i.start_key LIKE ?"
            },
            {
              "scheduleToStartMs",
              "SELECT percentile_cont(ARRAY[0.5,0.95,0.99]) WITHIN GROUP(ORDER BY extract(epoch FROM(a.dispatched_at-t.created_at))*1000) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id JOIN activity_attempt a ON a.instance_id=t.instance_id AND a.logical_id=t.logical_id WHERE i.tenant=? AND i.start_key LIKE ? AND a.dispatched_at IS NOT NULL AND a.generation=1"
            },
            {
              "completedActivities",
              "SELECT count(*) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND t.status='COMPLETED'"
            },
            {
              "firedBusinessTimers",
              "SELECT count(*) FROM timer t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND t.logical_id='wait' AND t.status='FIRED'"
            },
            {
              "acceptedUniqueSignals",
              "SELECT count(*) FROM signal_inbox s JOIN workflow_instance i ON i.id=s.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND s.accepted=true"
            },
            {
              "attemptsPerLogicalTask",
              "SELECT avg(t.attempt) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND i.start_key LIKE ?"
            },
            {
              "queuedAtEnd",
              "SELECT count(*) FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND t.status IN ('READY','LEASED')"
            },
            {
              "leaseExpirations",
              "SELECT count(*) FROM activity_attempt a JOIN workflow_instance i ON i.id=a.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND a.outcome='LEASE_EXPIRED'"
            },
            {
              "transientRejectedAttempts",
              "SELECT count(*) FROM activity_attempt a JOIN workflow_instance i ON i.id=a.instance_id WHERE i.tenant=? AND i.start_key LIKE ? AND a.outcome='REJECTED'"
            },
            {
              "timerLatenessMs",
              "SELECT percentile_cont(ARRAY[0.5,0.95,0.99]) WITHIN GROUP(ORDER BY extract(epoch FROM(h.at-t.due_at))*1000) FROM timer t JOIN workflow_instance i ON i.id=t.instance_id JOIN workflow_history h ON h.instance_id=t.instance_id AND h.task=t.logical_id AND h.type='TIMER_FIRED' WHERE i.tenant=? AND i.start_key LIKE ?"
            }
          }) {
        try (PreparedStatement s = c.prepareStatement(metric[1])) {
          s.setString(1, tenant);
          s.setString(2, run + "-%");
          try (ResultSet r = s.executeQuery()) {
            r.next();
            measurements.put(metric[0], r.getString(1));
          }
        }
      }
      try (Statement s = c.createStatement();
          ResultSet r = s.executeQuery("SELECT pg_database_size(current_database())")) {
        r.next();
        measurements.put("databaseBytesAtEnd", r.getLong(1));
        measurements.put("databaseGrowthBytes", r.getLong(1) - databaseBefore);
      }
    }
    for (var expected :
        Map.of(
                "completedActivities",
                executions * 8L,
                "firedBusinessTimers",
                executions / 2L,
                "acceptedUniqueSignals",
                (long) executions)
            .entrySet()) {
      if (Long.parseLong((String) measurements.get(expected.getKey())) != expected.getValue())
        throw new AssertionError("observed count mismatch: " + expected.getKey());
    }
    Set<String> selectedInstances = new HashSet<>();
    ids.forEach(id -> selectedInstances.add(id.toString()));
    Set<String> effectKeys = new HashSet<>();
    long observedEffects = 0;
    Path effectLog =
        Path.of(
            Objects.requireNonNull(
                System.getenv("EFFECT_LOG"), "independent provider log required"));
    try (var lines = Files.lines(effectLog)) {
      var iterator = lines.iterator();
      while (iterator.hasNext()) {
        String[] fields = iterator.next().split("\\t", 4);
        if (fields.length != 4 || !fields[0].equals("EFFECT")) continue;
        int separator = fields[1].indexOf(':');
        if (separator < 0 || !selectedInstances.contains(fields[1].substring(0, separator)))
          continue;
        observedEffects++;
        if (!effectKeys.add(fields[1]))
          throw new AssertionError("duplicate independent provider effect");
      }
    }
    if (observedEffects != executions * 8L)
      throw new AssertionError("independent provider effect count mismatch");
    measurements.put("independentProviderEffects", observedEffects);
    if (Long.parseLong((String) measurements.get("queuedAtEnd")) != 0)
      throw new AssertionError("queue not drained");
    if (Long.parseLong((String) measurements.get("transientRejectedAttempts"))
        != executions * 8L / 5)
      throw new AssertionError(
          "transient workload did not produce exactly 20% first-attempt rejection");
    Map<String, Object> result =
        Map.of(
            "schemaVersion",
            1,
            "project",
            "durableflow",
            "workload",
            "seeded-eight-activities-v1",
            "startedAt",
            startedAt.toString(),
            "measurements",
            measurements,
            "timeline",
            timeline,
            "limitations",
            List.of(
                "Closed-loop submissions and shared host: coordinated omission possible",
                "Recovery/lease expiry measured separately by fault demo",
                "Database size includes prior demo data; run reset-demo first"));
    Path out = Path.of(System.getenv().getOrDefault("RESULT_FILE", "target/workload.json"));
    Files.createDirectories(out.toAbsolutePath().getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
    System.out.println("asserted workload evidence: " + out);
  }
}
