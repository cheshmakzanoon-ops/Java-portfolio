package io.github.cheshmakzanoon.portfolio.latearrival;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

/** Real subprocess used by both crash tests and the deterministic public demo. */
public final class ReplayWorker {
  public static final Engine.Limits LIMITS =
      new Engine.Limits(2, 50_000, 100_000, 1_000_000, 1_000_000);

  public static final Duration SOURCE_LEASE = Duration.ofSeconds(30);

  private ReplayWorker() {}

  public static void main(String[] args) throws Exception {
    String url = args[0],
        user = args[1],
        password = System.getenv().getOrDefault("PULSE_DB_PASSWORD", "pulse-local"),
        bootstrap = args[2],
        run = args[3],
        topic = args[4],
        phase = args[5];
    PostgresStore store = new PostgresStore(url, user, password);
    if (phase.equals("late-before") || phase.equals("late-after"))
      store.withStateBarrier(
          (b, s) -> {
            if (s.seen.containsKey("t:five")
                && b
                    == (phase.equals("late-before")
                        ? PostgresStore.Barrier.BEFORE_COMMIT
                        : PostgresStore.Barrier.AFTER_COMMIT)) Runtime.getRuntime().halt(74);
          });
    if (phase.equals("outbox-crash"))
      store =
          new PostgresStore(
              url,
              user,
              password,
              b -> {
                if (b == PostgresStore.Barrier.DURING_OUTBOX) Runtime.getRuntime().halt(74);
              });
    store.migrate();
    // Outbox delivery has no source-consumer work. Establish the producer before
    // acquiring the short lease, so Kafka startup cannot consume the ownership budget.
    if (phase.equals("outbox-crash") || phase.equals("outbox-retry")) {
      try (KafkaResultPublisher publisher =
          new KafkaResultPublisher(bootstrap, topic + "-results", run)) {
        var owner = store.acquire(run, Duration.ofSeconds(5));
        try {
          int count = store.deliverOutbox(owner, publisher, 100);
          if (phase.equals("outbox-crash")) throw new AssertionError("outbox hook not reached");
          require(count > 0, "outbox retry publishes");
          System.out.println("OUTBOX_RETRIED=" + count);
        } finally {
          store.release(owner);
        }
      }
      System.out.println("PHASE_PASS=" + phase);
      return;
    }
    String topicId = KafkaPump.topicIdentity(bootstrap, topic, 2);
    AtomicLong clock = new AtomicLong(phase.equals("seed") ? 0 : 65_000);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    int sensorPartition = EventCodec.partition("t:a", 2);
    String other = controlSensor(1 - sensorPartition);
    // Client construction is not owned processing. Complete it before taking the
    // source lease, whose budget matches production and exceeds readiness's 20s bound.
    try (KafkaPump pump =
            new KafkaPump(bootstrap, topic, "replay-" + run, 2, 8, 100, failure::set)
                .clock(clock::get);
        KafkaProducer<String, byte[]> producer = producer(bootstrap);
        DurableSession session =
            new DurableSession(store, run, topicId, LIMITS, clock.get(), SOURCE_LEASE)) {
      pump.start(session.checkpoint());
      pump.awaitReady(Duration.ofSeconds(20));
      if (phase.equals("verify")) {
        verify(store, run, session.state().duplicates);
      } else if (phase.equals("seed")) {
        send(producer, topic, event(run, "ten", "a", 0, 10, false));
        send(producer, topic, event(run, "twenty", "a", 20_000, 20, false));
        clock.set(65_000);
        send(producer, topic, event(run, "progress-other", other, 65_000, 0, true));
        send(producer, topic, event(run, "progress-a", "a", 65_000, 0, true));
        drain(session, pump, failure, () -> session.state().watermark >= 60_000, 20);
        require(
            store.windows(run, "t", "a", "TUMBLING", 100, Long.MIN_VALUE).getFirst().sum() == 30,
            "seed sum");
      } else if (phase.startsWith("late-")) {
        send(producer, topic, event(run, "five", "a", 50_000, 5, false));
        drain(session, pump, failure, () -> false, 20);
        throw new AssertionError("crash hook not reached");
      } else if (phase.equals("recover")) {
        // Replayed precommit records are consumed from SQL checkpoint; postcommit record is not
        // reread.
        drain(session, pump, failure, () -> session.state().seen.containsKey("t:five"), 20);
        clock.set(95_000);
        drain(
            session,
            pump,
            failure,
            () -> session.state().partitions.get(sensorPartition).idle(),
            20);
        clock.set(100_000);
        send(producer, topic, event(run, "progress100", other, 105_000, 0, true));
        drain(session, pump, failure, () -> session.state().watermark >= 100_000, 20);
        send(producer, topic, event(run, "resume", "a", 40_000, 9, false));
        send(producer, topic, event(run, "twenty", "a", 20_000, 20, false));
        drain(session, pump, failure, () -> session.state().duplicates == 1, 20);
        require(session.state().watermark == 100_000, "watermark never decreases");
        // Finalize the remaining overlapping sliding windows for a complete oracle comparison.
        clock.set(400_000);
        send(producer, topic, event(run, "end-a", "a", 400_000, 0, true));
        send(producer, topic, event(run, "end-other", other, 400_000, 0, true));
        drain(session, pump, failure, () -> session.state().watermark >= 395_000, 20);
        verify(store, run, session.state().duplicates);
      } else throw new IllegalArgumentException("phase");
      pump.committed(session.checkpoint());
      System.out.println("PHASE_PASS=" + phase);
    }
  }

  public static void verify(PostgresStore store, String run, long duplicates) throws Exception {
    var tumbling = store.windows(run, "t", "a", "TUMBLING", 100, Long.MIN_VALUE);
    require(tumbling.size() == 1, "one tumbling window");
    var first = tumbling.getFirst();
    require(
        first.count() == 3 && first.sum() == 35 && first.finalized(), "first count=3 sum=35 final");
    require(duplicates == 1, "logical duplicate");
    try (var c = store.connection();
        var p =
            c.prepareStatement(
                "SELECT count(*) FROM late_record WHERE run_id=? AND window_id LIKE '%:TUMBLING:%'")) {
      p.setString(1, run);
      try (var r = p.executeQuery()) {
        r.next();
        require(r.getLong(1) == 1, "one rejected late tumbling contribution");
      }
    }
    BatchOracle oracle = new BatchOracle();
    oracle.measurement(event(run, "ten", "a", 0, 10, false), Long.MIN_VALUE);
    oracle.measurement(event(run, "twenty", "a", 20_000, 20, false), Long.MIN_VALUE);
    oracle.measurement(event(run, "five", "a", 50_000, 5, false), 60_000);
    oracle.measurement(event(run, "resume", "a", 40_000, 9, false), 100_000);
    oracle.measurement(event(run, "twenty", "a", 20_000, 20, false), 100_000);
    List<String> actual = new ArrayList<>(), expected = new ArrayList<>();
    for (String type : List.of("TUMBLING", "SLIDING"))
      for (var r : store.windows(run, "t", "a", type, 100, Long.MIN_VALUE))
        actual.add(
            type
                + ":"
                + r.start()
                + ":"
                + r.count()
                + ":"
                + r.sum()
                + ":"
                + r.minimum()
                + ":"
                + r.maximum()
                + ":"
                + r.finalized());
    oracle
        .totals()
        .forEach(
            (k, v) ->
                expected.add(
                    k.type()
                        + ":"
                        + k.start()
                        + ":"
                        + v.count()
                        + ":"
                        + v.sum()
                        + ":"
                        + v.minimum()
                        + ":"
                        + v.maximum()
                        + ":true"));
    Collections.sort(actual);
    Collections.sort(expected);
    require(actual.equals(expected), "recovered aggregates equal independent batch oracle");
    String digest = Engine.hash(String.join("\n", actual));
    System.out.println("TUMBLING=count:3,sum:35,final:true DUPLICATES=1 LATE_TUMBLING=1");
    actual.forEach(System.out::println);
    System.out.println(
        "RECOVERY_DIGEST=" + digest + " ORACLE_DIGEST=" + Engine.hash(String.join("\n", expected)));
  }

  public static Engine.Event event(
      String run, String id, String sensor, long time, long value, boolean progress) {
    return new Engine.Event(1, run, id, "t", sensor, time, value, progress);
  }

  public static String controlSensor(int p) {
    for (int i = 0; ; i++) if (EventCodec.partition("t:c" + i, 2) == p) return "c" + i;
  }

  public static KafkaProducer<String, byte[]> producer(String bootstrap) {
    return new KafkaProducer<>(
        Map.of(
            "bootstrap.servers",
            bootstrap,
            "key.serializer",
            StringSerializer.class,
            "value.serializer",
            ByteArraySerializer.class,
            "acks",
            "all",
            "delivery.timeout.ms",
            15000,
            "request.timeout.ms",
            10000,
            "max.block.ms",
            10000));
  }

  public static void send(KafkaProducer<String, byte[]> producer, String topic, Engine.Event event)
      throws Exception {
    producer
        .send(new ProducerRecord<>(topic, event.key(), EventCodec.encode(event)))
        .get(20, TimeUnit.SECONDS);
  }

  private static void drain(
      DurableSession session,
      KafkaPump pump,
      AtomicReference<Throwable> failure,
      java.util.function.BooleanSupplier done,
      int seconds)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (!done.getAsBoolean()) {
      if (failure.get() != null) throw new IllegalStateException("poll failed", failure.get());
      if (System.nanoTime() > deadline) throw new AssertionError("barrier timeout");
      var batch = pump.take(Duration.ofMillis(100));
      if (batch != null) {
        session.apply(batch);
        pump.committed(session.checkpoint());
      }
    }
  }

  public static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
