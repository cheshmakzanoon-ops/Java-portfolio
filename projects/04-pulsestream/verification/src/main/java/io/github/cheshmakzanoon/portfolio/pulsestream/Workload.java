package io.github.cheshmakzanoon.portfolio.pulsestream;

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
    var store =
        new PostgresStore(
            url, "pulse", System.getenv().getOrDefault("PULSE_DB_PASSWORD", "pulse-local"));
    store.migrate();
    String topicId = KafkaPump.topicIdentity(bootstrap, topic, 8);
    Random random = new Random(4404);
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
        var record = new ProducerRecord<String, byte[]>(topic, e.key(), EventCodec.encode(e));
        producer.send(
            record,
            (m, f) -> {
              if (f != null) sendFailure.compareAndSet(null, f);
            });
        if (i % 1000 == 0)
          producer.send(
              record,
              (m, f) -> {
                if (f != null) sendFailure.compareAndSet(null, f);
              });
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
    long accepted, duplicates, start = System.nanoTime();
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
        if (!session.state().quarantine.isEmpty())
          throw new AssertionError(
              "unexpected quarantine " + session.state().quarantine.getFirst().reason());
      }
      accepted = session.state().seen.size();
      duplicates = session.state().duplicates;
      maxQueue = pump.maxQueue();
    }
    long elapsed = System.nanoTime() - start,
        dbAfter = databaseSize(store),
        recoveryStart = System.nanoTime();
    try (DurableSession recovered =
            new DurableSession(store, run, topicId, limits, 1_000_000, Duration.ofSeconds(60));
        KafkaPump pump =
            new KafkaPump(bootstrap, topic, "workload-recovery-" + run, 8, 8, 1000, failure::set)) {
      pump.start(recovered.checkpoint());
      pump.awaitReady(Duration.ofSeconds(20));
      ReplayWorker.require(recovered.state().seen.size() == count, "recovered dedup count");
    }
    long recoveryNanos = System.nanoTime() - recoveryStart;
    checkpointNanos.sort(Long::compareTo);
    Map<String, Object> measurements = new LinkedHashMap<>();
    measurements.put("acceptedMeasurements", accepted);
    measurements.put("duplicateCount", duplicates);
    measurements.put("acceptedEventsPerSecond", accepted / (elapsed / 1_000_000_000.0));
    measurements.put("elapsedSeconds", elapsed / 1_000_000_000.0);
    measurements.put("producerPreparationSeconds", producerNanos / 1_000_000_000.0);
    measurements.put("checkpointSamples", checkpointNanos.size());
    measurements.put("checkpointP50Millis", percentile(checkpointNanos, 0.50) / 1_000_000.0);
    measurements.put("checkpointP95Millis", percentile(checkpointNanos, 0.95) / 1_000_000.0);
    measurements.put("checkpointP99Millis", percentile(checkpointNanos, 0.99) / 1_000_000.0);
    measurements.put("maximumQueueBatches", maxQueue);
    measurements.put("peakObservedHeapBytes", maxHeap);
    measurements.put("databaseBytes", dbAfter);
    measurements.put("databaseGrowthBytes", dbAfter - dbBefore);
    measurements.put("recoveryMillis", recoveryNanos / 1_000_000.0);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("project", "pulsestream");
    result.put("workload", "durable-v1-" + mode);
    result.put("seed", 4404);
    result.put("run", run);
    result.put("measurements", measurements);
    result.put("jdk", System.getProperty("java.runtime.version"));
    result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
    result.put("timestamp", Instant.now().toString());
    result.put(
        "limitations",
        List.of(
            "Closed-loop drain after durable Kafka preparation; checkpoint latency excludes offered-load queueing and has coordinated-omission limits.",
            "Shared-host measurements require three independent isolated runs before a baseline claim.",
            "Result outbox delivery excluded; SQL result/outbox writes included."));
    String json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(result);
    System.out.println(json);
    if (args.length > 3) Files.writeString(Path.of(args[3]), json + "\n");
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
