package io.github.cheshmakzanoon.portfolio.pulsestream;

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
}
