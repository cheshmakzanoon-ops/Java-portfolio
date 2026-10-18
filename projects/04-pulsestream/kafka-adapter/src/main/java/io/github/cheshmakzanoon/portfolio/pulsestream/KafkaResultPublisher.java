package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

/** At-least-once outbox sink; downstream identity includes the durable revision. */
public final class KafkaResultPublisher implements Consumer<Engine.Result>, AutoCloseable {
  private final KafkaProducer<String, String> producer;
  private final String topic;
  private final String run;

  public KafkaResultPublisher(String bootstrap, String topic, String run) {
    this.topic = topic;
    this.run = run;
    producer =
        new KafkaProducer<>(
            Map.of(
                "bootstrap.servers",
                bootstrap,
                "key.serializer",
                StringSerializer.class,
                "value.serializer",
                StringSerializer.class,
                "acks",
                "all",
                "enable.idempotence",
                true,
                "delivery.timeout.ms",
                15000,
                "request.timeout.ms",
                10000,
                "max.block.ms",
                10000));
  }

  @Override
  public void accept(Engine.Result r) {
    String identity = run + ":" + r.window().id() + ":" + r.revision();
    var a = r.aggregate();
    String json =
        "{\"schemaVersion\":1,\"runId\":\""
            + run
            + "\",\"windowId\":\""
            + r.window().id()
            + "\",\"revision\":"
            + r.revision()
            + ",\"count\":"
            + a.count()
            + ",\"sum\":"
            + a.sum()
            + ",\"minimum\":"
            + a.minimum()
            + ",\"maximum\":"
            + a.maximum()
            + ",\"final\":"
            + r.finalized()
            + "}";
    try {
      producer.send(new ProducerRecord<>(topic, identity, json)).get(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OUTBOX_INTERRUPTED", e);
    } catch (Exception e) {
      throw new IllegalStateException("OUTBOX_DELIVERY_FAILED", e);
    }
  }

  @Override
  public void close() {
    producer.close(Duration.ofSeconds(10));
  }
}
