package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;

class KafkaPumpLifecycleTest {
  @Test
  void failedStartupCanCloseUnstartedConsumerAndReleaseItsJmxRegistration() throws Exception {
    String group = "lifecycle_" + System.nanoTime();
    var server = ManagementFactory.getPlatformMBeanServer();
    var name = new ObjectName("kafka.consumer:type=app-info,id=consumer-" + group + "-*");
    var pump =
        new KafkaPump(
            "127.0.0.1:1", "unused", group, 1, 2, 1, t -> fail("unstarted consumer must not poll"));
    assertFalse(
        server.queryNames(name, null).isEmpty(), "constructed Kafka client is actually registered");
    pump.close();
    pump.close();
    assertTrue(
        server.queryNames(name, null).isEmpty(),
        "close must release unstarted client's registration");
  }

  @Test
  void closedPumpRejectsStartupAndCompletesReadinessWithoutAWait() throws Exception {
    var consumer = mockConsumer();
    var pump = new KafkaPump(consumer, "source", 1, 3, t -> fail(t));
    pump.close();
    assertTrue(consumer.closed());
    assertEquals(
        "PUMP_CLOSED",
        assertThrows(IllegalStateException.class, () -> pump.start(java.util.Map.of()))
            .getMessage());
    var failure =
        assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> pump.awaitReady(java.time.Duration.ofMillis(100)));
    assertEquals("PUMP_CLOSED", failure.getCause().getMessage());
    pump.close();
  }

  @Test
  void uncommittedHandoffContinuesToBlockSourceSilenceAndOnlyAcknowledgedOffsetsMirror()
      throws Exception {
    var consumer = mockConsumer();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var observe = new java.util.concurrent.CountDownLatch(1);
    var clock = new java.util.concurrent.atomic.AtomicLong(1000);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    consumer.schedulePollTask(
        () ->
            consumer.addRecord(
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                    "source",
                    0,
                    0,
                    "t:a",
                    EventCodec.encode(new Engine.Event(1, "run", "one", "t", "a", 0, 7, false)))));
    consumer.schedulePollTask(
        () -> {
          entered.countDown();
          try {
            if (!observe.await(3, java.util.concurrent.TimeUnit.SECONDS))
              throw new AssertionError("observation barrier");
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
          clock.set(32000);
        });
    try (var pump = new KafkaPump(consumer, "source", 1, 3, failure::set).clock(clock::get)) {
      pump.start(java.util.Map.of());
      pump.awaitReady(java.time.Duration.ofSeconds(3));
      var batch = pump.take(java.time.Duration.ofSeconds(3));
      assertNotNull(batch);
      assertEquals(1, batch.inputs().size());
      assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, () -> pump.take(java.time.Duration.ZERO));
      assertNull(consumer.acknowledged.get());
      observe.countDown();
      // The queued observation was captured while the first batch was still in flight.
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
      while (pump.depth() == 0 && failure.get() == null && System.nanoTime() < deadline)
        Thread.sleep(1);
      assertTrue(pump.depth() > 0);
      pump.committed(java.util.Map.of(0, 1L));
      var observation = pump.take(java.time.Duration.ofSeconds(3));
      assertNotNull(observation);
      assertTrue(observation.inputs().isEmpty());
      assertTrue(observation.observations().getFirst().queued() > 0);
      pump.committed(java.util.Map.of(0, 1L));
      assertTrue(consumer.mirrored.await(3, java.util.concurrent.TimeUnit.SECONDS));
      assertNull(failure.get());
    } finally {
      observe.countDown();
    }
    assertTrue(consumer.closed());
    assertEquals(
        1,
        consumer
            .acknowledged
            .get()
            .get(new org.apache.kafka.common.TopicPartition("source", 0))
            .offset());
  }

  @Test
  void unavailableRetainedPositionFailsReadinessAndClosesTheConsumer() throws Exception {
    var consumer = mockConsumer();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var tp = new org.apache.kafka.common.TopicPartition("source", 0);
    consumer.updateBeginningOffsets(java.util.Map.of(tp, 10L));
    consumer.updateEndOffsets(java.util.Map.of(tp, 20L));
    try (var pump = new KafkaPump(consumer, "source", 1, 3, failure::set)) {
      pump.start(java.util.Map.of(0, 9L));
      var rejected =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> pump.awaitReady(java.time.Duration.ofSeconds(3)));
      assertTrue(rejected.getCause().getMessage().startsWith("SOURCE_POSITION_UNAVAILABLE"));
    }
    assertTrue(consumer.closed());
    assertNotNull(failure.get());
  }

  private static RecordingConsumer mockConsumer() {
    var consumer = new RecordingConsumer();
    var tp = new org.apache.kafka.common.TopicPartition("source", 0);
    consumer.updateBeginningOffsets(java.util.Map.of(tp, 0L));
    consumer.updateEndOffsets(java.util.Map.of(tp, 1L));
    return consumer;
  }

  private static final class RecordingConsumer
      extends org.apache.kafka.clients.consumer.MockConsumer<String, byte[]> {
    final java.util.concurrent.atomic.AtomicReference<
            java.util.Map<
                org.apache.kafka.common.TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata>>
        acknowledged = new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.CountDownLatch mirrored = new java.util.concurrent.CountDownLatch(1);

    RecordingConsumer() {
      super("none");
    }

    @Override
    public synchronized void commitSync(
        java.util.Map<
                org.apache.kafka.common.TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata>
            offsets,
        java.time.Duration timeout) {
      super.commitSync(offsets, timeout);
      acknowledged.set(java.util.Map.copyOf(offsets));
      mirrored.countDown();
    }
  }
}
