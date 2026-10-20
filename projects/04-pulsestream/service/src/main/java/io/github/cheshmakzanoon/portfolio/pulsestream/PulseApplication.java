package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class PulseApplication {
  public static void main(String[] args) {
    SpringApplication.run(PulseApplication.class, args);
  }

  @Bean
  PostgresStore store(Environment e) {
    var store =
        new PostgresStore(
            e.getRequiredProperty("pulse.db.url"),
            e.getRequiredProperty("pulse.db.user"),
            e.getRequiredProperty("pulse.db.password"));
    String crash = e.getProperty("pulse.crash-at", "");
    if (!crash.isEmpty()) {
      if (!e.getProperty("pulse.security", "local").equals("local"))
        throw new IllegalStateException("CRASH_HOOK_LOCAL_ONLY");
      String identity = e.getRequiredProperty("pulse.crash-event");
      store.withStateBarrier(
          (b, s) -> {
            if (b.name().equals(crash) && s.seen.containsKey(identity))
              Runtime.getRuntime().halt(74);
          });
    }
    store.migrate();
    return store;
  }

  @Bean(destroyMethod = "close")
  Processor processor(Environment e, PostgresStore store) throws Exception {
    if (!e.getProperty("pulse.consume", Boolean.class, true)) return new Processor(store);
    var limits =
        new Engine.Limits(
            e.getProperty("pulse.partitions", Integer.class, 2),
            e.getProperty("pulse.keys", Integer.class, 50_000),
            e.getProperty("pulse.panes", Integer.class, 100_000),
            e.getProperty("pulse.events", Integer.class, 1_100_000),
            e.getProperty("pulse.future-skew-ms", Long.class, 60_000L));
    return new Processor(
        store,
        e.getProperty("pulse.run", "demo"),
        e.getRequiredProperty("pulse.bootstrap"),
        e.getProperty("pulse.topic", "pulse-demo"),
        limits,
        e.getProperty("pulse.queue-batches", Integer.class, 8),
        e.getProperty("pulse.batch", Integer.class, 500));
  }

  /**
   * Single engine owner. An ambiguous database error stops progress until SQL-authoritative
   * restart.
   */
  public static final class Processor implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Processor.class.getName());
    private final PostgresStore store;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicLong outboxFailures = new AtomicLong();
    private DurableSession session;
    private KafkaPump pump;
    private KafkaResultPublisher publisher;
    private Thread thread;
    private volatile boolean running = true;
    private volatile long watermark = Long.MIN_VALUE;
    private volatile long commitNanos;

    public Processor(PostgresStore store) {
      this.store = store;
    }

    public Processor(
        PostgresStore store,
        String run,
        String bootstrap,
        String topic,
        Engine.Limits limits,
        int queueBatches,
        int batch)
        throws Exception {
      this.store = store;
      String topicId = KafkaPump.topicIdentity(bootstrap, topic, limits.partitions());
      try {
        pump =
            new KafkaPump(
                bootstrap,
                topic,
                "pulse-" + run,
                limits.partitions(),
                queueBatches,
                batch,
                t -> failure.compareAndSet(null, t));
        publisher = new KafkaResultPublisher(bootstrap, topic + "-results", run);
        session =
            new DurableSession(
                store, run, topicId, limits, System.currentTimeMillis(), Duration.ofSeconds(30));
        pump.start(session.checkpoint());
        pump.awaitReady(Duration.ofSeconds(20));
        thread = Thread.ofPlatform().name("pulsestream-engine").start(this::process);
      } catch (Exception e) {
        try {
          close();
        } catch (RuntimeException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
        throw e;
      }
    }

    private void process() {
      try {
        while (running && failure.get() == null) {
          KafkaPump.Batch batch = pump.take(Duration.ofSeconds(1));
          if (batch == null) continue;
          long start = System.nanoTime();
          session.apply(batch);
          commitNanos = System.nanoTime() - start;
          watermark = session.state().watermark;
          pump.committed(session.checkpoint());
          try {
            store.deliverOutbox(session.owner(), publisher, 100);
          } catch (RuntimeException | SQLException e) {
            outboxFailures.incrementAndGet();
          }
        }
      } catch (InterruptedException e) {
        if (running)
          failure.compareAndSet(null, new IllegalStateException("PROCESSOR_INTERRUPTED", e));
        Thread.currentThread().interrupt();
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
      }
    }

    public Map<String, Object> metrics() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("healthy", failure.get() == null);
      m.put("failure", failure.get() == null ? "" : failure.get().getClass().getSimpleName());
      m.put("sourceLag", pump == null ? 0 : pump.sourceLag());
      m.put("queueDepth", pump == null ? 0 : pump.depth());
      m.put("maximumQueueDepth", pump == null ? 0 : pump.maxQueue());
      m.put("queueUtilization", pump == null ? 0 : pump.depth() / (double) pump.capacity());
      m.put("watermark", watermark);
      m.put(
          "watermarkLagMillis",
          watermark == Long.MIN_VALUE ? -1 : Math.max(0, System.currentTimeMillis() - watermark));
      m.put("lastCheckpointMillis", commitNanos / 1_000_000.0);
      m.put("outboxDeliveryFailures", outboxFailures.get());
      return m;
    }

    @Override
    public void close() {
      running = false;
      boolean interrupted = Thread.interrupted();
      try {
        // Cancel delivery/waits first. Do not release ownership or close its publisher
        // until the engine owner has actually stopped using them.
        if (thread != null) {
          thread.interrupt();
          ThreadTermination.await(thread, Duration.ofSeconds(20));
        }
        interrupted |= Thread.interrupted();
        if (pump != null) pump.close();
        interrupted |= Thread.interrupted();
        if (publisher != null) publisher.close();
        interrupted |= Thread.interrupted();
        if (session != null)
          try {
            session.close();
          } catch (SQLException releaseFailure) {
            LOG.log(
                System.Logger.Level.WARNING,
                "Owner release failed; database-time lease will expire; SQL state {0}",
                releaseFailure.getSQLState());
          }
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }
}
