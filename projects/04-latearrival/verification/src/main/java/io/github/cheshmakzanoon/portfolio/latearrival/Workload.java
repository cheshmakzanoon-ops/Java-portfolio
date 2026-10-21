package io.github.cheshmakzanoon.portfolio.latearrival;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;

/**
 * Closed-loop durable workload; producer preparation is reported separately from measured drain.
 */
public final class Workload {
  private Workload() {}

  public static void main(String[] args) throws Exception {
    String url = args[0], bootstrap = args[1], mode = args.length > 2 ? args[2] : "full";
    if (!Set.of("smoke", "full").contains(mode)) throw new IllegalArgumentException("smoke|full");
    int count = mode.equals("full") ? 1_000_000 : 10_000, keys = mode.equals("full") ? 10_000 : 100;
    String run = "workload_" + System.currentTimeMillis(), topic = "pulse-" + run;
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
      admin
          .createTopics(List.of(new NewTopic(topic, 8, (short) 1)))
          .all()
          .get(30, TimeUnit.SECONDS);
    }
    var limits = new Engine.Limits(8, 50_000, 100_000, 2_000_000, 1_000_000);
    long databaseSetupStart = System.nanoTime();
    String password = System.getenv().getOrDefault("PULSE_DB_PASSWORD", "pulse-local");
    String database = "pulse_" + run;
    if (!database.matches("[a-z0-9_]{1,63}"))
      throw new IllegalArgumentException("database identity");
    var administrator = new PostgresStore(url, "pulse", password);
    try (var c = administrator.connection();
        var create = c.createStatement()) {
      create.setQueryTimeout(30);
      create.executeUpdate("CREATE DATABASE \"" + database + "\"");
    }
    int query = url.indexOf('?');
    String sourceUrl = query < 0 ? url : url.substring(0, query);
    String suffix = query < 0 ? "" : url.substring(query);
    String isolatedUrl = sourceUrl.substring(0, sourceUrl.lastIndexOf('/') + 1) + database + suffix;
    var store = new PostgresStore(isolatedUrl, "pulse", password);
    store.migrate();
    long databaseSetupNanos = System.nanoTime() - databaseSetupStart;
    String topicId = KafkaPump.topicIdentity(bootstrap, topic, 8);
    Random random = new Random(4404);
    WorkloadOracle oracle = new WorkloadOracle(keys);
    java.util.concurrent.atomic.AtomicLongArray sourceMaximum =
        new java.util.concurrent.atomic.AtomicLongArray(8);
    for (int p = 0; p < 8; p++) sourceMaximum.set(p, Long.MIN_VALUE);
    long preparation = System.nanoTime();
    AtomicReference<Throwable> sendFailure = new AtomicReference<>();
    try (var producer = ReplayWorker.producer(bootstrap)) {
      for (int i = 0; i < count; i++) {
        long ts = (i / keys) * 1000L;
        if (i % 10 == 0) ts = Math.max(0, ts - 15_000);
        var e =
            new Engine.Event(
                1,
                run,
                "e" + i,
                "t",
                "sensor" + (i % keys),
                ts,
                random.nextInt(2001) - 1000,
                false);
        oracle.measurement(i % keys, ts, e.valueMilliUnits());
        var record = new ProducerRecord<String, byte[]>(topic, e.key(), EventCodec.encode(e));
        final long sourceTime = ts;
        Callback receipt =
            (m, f) -> {
              if (f != null) sendFailure.compareAndSet(null, f);
              else sourceMaximum.accumulateAndGet(m.partition(), sourceTime, Math::max);
            };
        producer.send(record, receipt);
        if (i % 1000 == 0) producer.send(record, receipt);
      }
      producer.flush();
    }
    if (sendFailure.get() != null)
      throw new IllegalStateException("producer failed", sendFailure.get());
    long producerNanos = System.nanoTime() - preparation,
        dbBefore = databaseSize(store),
        maxHeap = 0;
    List<Long> checkpointNanos = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    int maxQueue;
    var os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (allocation.isThreadAllocatedMemorySupported()
        && !allocation.isThreadAllocatedMemoryEnabled())
      allocation.setThreadAllocatedMemoryEnabled(true);
    long cpuStart = os.getProcessCpuTime(),
        allocationStart = allocated(allocation),
        gcCountStart = gcCount(),
        gcMillisStart = gcMillis();
    long accepted,
        duplicates,
        finalWatermark,
        elapsed,
        cpuNanos,
        allocatedBytes,
        gcCollections,
        gcMilliseconds;
    String beforeDigest;
    long start = System.nanoTime();
    try (DurableSession session =
            new DurableSession(store, run, topicId, limits, 1_000_000, Duration.ofSeconds(60));
        KafkaPump pump =
            new KafkaPump(bootstrap, topic, "workload-" + run, 8, 8, 1000, failure::set)
                .clock(() -> 1_000_000L)) {
      pump.start(session.checkpoint());
      pump.awaitReady(Duration.ofSeconds(20));
      long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
      while (session.state().seen.size() < count
          || session.state().duplicates < (count + 999) / 1000) {
        if (failure.get() != null) throw new IllegalStateException("poll failure", failure.get());
        if (System.nanoTime() > deadline) throw new IllegalStateException("workload deadline");
        var batch = pump.take(Duration.ofSeconds(1));
        if (batch == null) continue;
        long begin = System.nanoTime();
        session.apply(batch);
        checkpointNanos.add(System.nanoTime() - begin);
        pump.committed(session.checkpoint());
        maxHeap =
            Math.max(maxHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        if (!session.state().late.isEmpty())
          throw new AssertionError("bounded15s reordering must fit30s window lateness");
        if (!session.state().quarantine.isEmpty())
          throw new AssertionError(
              "unexpected quarantine " + session.state().quarantine.getFirst().reason());
      }
      elapsed = System.nanoTime() - start;
      cpuNanos = os.getProcessCpuTime() - cpuStart;
      long allocatedEnd = allocated(allocation);
      allocatedBytes =
          allocatedEnd < 0 || allocationStart < 0 ? -1 : allocatedEnd - allocationStart;
      gcCollections = gcCount() - gcCountStart;
      gcMilliseconds = gcMillis() - gcMillisStart;
      finalWatermark = session.state().watermark;
      long expectedWatermark = Long.MAX_VALUE;
      for (int p = 0; p < 8; p++) {
        if (sourceMaximum.get(p) == Long.MIN_VALUE)
          throw new AssertionError("unseen reference partition");
        expectedWatermark = Math.min(expectedWatermark, sourceMaximum.get(p) - 5000);
      }
      ReplayWorker.require(
          finalWatermark == expectedWatermark, "independently derived final source watermark");
      beforeDigest = stateDigest(session.state());
      accepted = session.state().seen.size();
      duplicates = session.state().duplicates;
      maxQueue = pump.maxQueue();
    }
    long dbAfter = databaseSize(store), recoveryStart = System.nanoTime();
    String afterDigest;
    try (DurableSession recovered =
            new DurableSession(store, run, topicId, limits, 1_000_000, Duration.ofSeconds(60));
        KafkaPump pump =
            new KafkaPump(bootstrap, topic, "workload-recovery-" + run, 8, 8, 1000, failure::set)) {
      pump.start(recovered.checkpoint());
      pump.awaitReady(Duration.ofSeconds(20));
      ReplayWorker.require(recovered.state().seen.size() == count, "recovered dedup count");
      afterDigest = stateDigest(recovered.state());
      ReplayWorker.require(
          beforeDigest.equals(afterDigest), "complete committed-state recovery digest");
    }
    long recoveryNanos = System.nanoTime() - recoveryStart;
    long validationStart = System.nanoTime();
    var proof = oracle.verify(store, run, finalWatermark);
    ReplayWorker.require(
        proof.resultDigest().equals(proof.referenceDigest()),
        "independent published-window oracle digest");
    long validationNanos = System.nanoTime() - validationStart;
    ReplayWorker.require(
        accepted == count && duplicates == (count + 999) / 1000, "exact admitted/duplicate totals");
    checkpointNanos.sort(Long::compareTo);
    Map<String, Object> measurements = new LinkedHashMap<>();
    measurements.put("acceptedMeasurements", accepted);
    measurements.put("duplicateCount", duplicates);
    measurements.put("acceptedEventsPerSecond", accepted / (elapsed / 1_000_000_000.0));
    measurements.put("elapsedSeconds", elapsed / 1_000_000_000.0);
    measurements.put("producerPreparationSeconds", producerNanos / 1_000_000_000.0);
    measurements.put("databasePreparationSeconds", databaseSetupNanos / 1_000_000_000.0);
    measurements.put("checkpointSamples", checkpointNanos.size());
    measurements.put("checkpointP50Millis", percentile(checkpointNanos, 0.50) / 1_000_000.0);
    measurements.put("checkpointP95Millis", percentile(checkpointNanos, 0.95) / 1_000_000.0);
    measurements.put("checkpointP99Millis", percentile(checkpointNanos, 0.99) / 1_000_000.0);
    measurements.put("maximumQueueBatches", maxQueue);
    measurements.put("peakObservedHeapBytes", maxHeap);
    measurements.put("databaseBytes", dbAfter);
    measurements.put("databaseGrowthBytes", dbAfter - dbBefore);
    measurements.put("recoveryMillis", recoveryNanos / 1_000_000.0);
    measurements.put("processCpuSeconds", cpuNanos / 1_000_000_000.0);
    measurements.put("averageProcessCpuCores", cpuNanos / (double) elapsed);
    measurements.put("allocatedBytes", allocatedBytes);
    measurements.put("gcCollections", gcCollections);
    measurements.put("gcMillis", gcMilliseconds);
    measurements.put("oracleValidationSeconds", validationNanos / 1_000_000_000.0);
    measurements.put("publishedWindows", proof.windows());
    measurements.put("watermark", finalWatermark);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("project", "latearrival");
    result.put("workload", "durable-v1-" + mode);
    result.put("seed", 4404);
    result.put("run", run);
    result.put("database", database);
    result.put("sourceRevision", System.getProperty("pulse.sourceFingerprint", "UNRECORDED"));
    result.put("recoveryDigest", afterDigest);
    result.put("committedDigest", beforeDigest);
    result.put("resultDigest", proof.resultDigest());
    result.put("oracleDigest", proof.referenceDigest());
    result.put("assertions", Map.of("passed", 6, "failed", 0));
    result.put(
        "assertionBoundary",
        "exact admitted count, exact duplicates, independent source watermark, full recovered state digest, all published window fields/row count, independent result digest");
    result.put("measurements", measurements);
    result.put("jdk", System.getProperty("java.runtime.version"));
    result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
    result.put("timestamp", Instant.now().toString());
    result.put(
        "limitations",
        List.of(
            "Closed-loop drain after durable Kafka preparation; checkpoint latency excludes offered-load queueing and has coordinated-omission limits.",
            "Each run starts a fresh isolated PostgreSQL database; setup/schema and source preparation are excluded from drain and reported separately.",
            "Result outbox delivery excluded; SQL result/outbox writes included."));
    String json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(result);
    System.out.println(json);
    if (args.length > 3) Files.writeString(Path.of(args[3]), json + "\n");
  }

  private static long allocated(com.sun.management.ThreadMXBean bean) {
    try {
      return bean.isThreadAllocatedMemorySupported() ? bean.getTotalThreadAllocatedBytes() : -1;
    } catch (UnsupportedOperationException e) {
      return -1;
    }
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0, b.getCollectionCount()))
        .sum();
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0, b.getCollectionTime()))
        .sum();
  }

  private static String stateDigest(Engine.State s) {
    final java.security.MessageDigest digest;
    try {
      digest = java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    java.util.function.Consumer<String> row =
        value -> {
          digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          digest.update((byte) '\n');
        };
    row.accept("watermark=" + s.watermark + ",duplicates=" + s.duplicates);
    s.offsets.forEach((p, n) -> row.accept("offset:" + p + ":" + n));
    s.partitions.forEach((p, v) -> row.accept("source:" + p + ":" + v.maxTimestamp()));
    s.seen.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> row.accept("seen:" + e.getKey() + ":" + e.getValue()));
    s.panes.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().toString()))
        .forEach(e -> row.accept("pane:" + e.getKey() + ":" + e.getValue()));
    s.results.entrySet().stream()
        .filter(e -> !e.getValue().finalized())
        .sorted(Comparator.comparing(e -> e.getKey().toString()))
        .forEach(e -> row.accept("result:" + e.getKey() + ":" + e.getValue()));
    s.pending.stream().map(Object::toString).sorted().forEach(w -> row.accept("pending:" + w));
    s.admitted.stream().map(Object::toString).sorted().forEach(k -> row.accept("admitted:" + k));
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static long percentile(List<Long> values, double quantile) {
    return values.get(Math.max(0, (int) Math.ceil(quantile * values.size()) - 1));
  }

  private static long databaseSize(PostgresStore store) throws SQLException {
    try (var c = store.connection();
        var p = c.prepareStatement("SELECT pg_database_size(current_database())");
        var r = p.executeQuery()) {
      r.next();
      return r.getLong(1);
    }
  }
}
