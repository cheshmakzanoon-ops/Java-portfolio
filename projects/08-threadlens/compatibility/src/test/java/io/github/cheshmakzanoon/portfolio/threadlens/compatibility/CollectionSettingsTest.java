package io.github.cheshmakzanoon.portfolio.threadlens.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.jfr.*;
import io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CollectionSettingsTest {
  @TempDir Path directory;

  @Test
  void parkClassAndDurationRemainAvailableWithoutProducerStacks() throws Exception {
    assertEquals(EventCapabilities.CONFIGURATION_VERSION, WorkloadMain.COLLECTION_CONFIGURATION);
    Path file = directory.resolve("park.jfr");
    Object blocker = new ParkFixture();
    try (var recording = new Recording()) {
      recording.setSettings(EventCapabilities.settings(EventCapabilities.discover()));
      recording.start();
      for (int i = 0; i < 20; i++) LockSupport.parkNanos(blocker, 5_000_000L);
      recording.stop();
      recording.dump(file);
      assertEquals("false", recording.getSettings().get("jdk.ThreadPark#stackTrace"));
    }
    int found = 0;
    try (var input = new RecordingFile(file)) {
      while (input.hasMoreEvents()) {
        var event = input.readEvent();
        if (event.getEventType().getName().equals("jdk.ThreadPark")
            && event.getClass("parkedClass") != null
            && event.getClass("parkedClass").getName().equals(ParkFixture.class.getName())) {
          found++;
          assertTrue(event.getDuration().toNanos() >= 2_000_000);
          assertNull(event.getStackTrace());
          var normalized = new EventNormalizer().normalize(event).orElseThrow();
          assertEquals(ParkFixture.class.getName(), normalized.subject());
          assertTrue(normalized.value() > 0);
          assertTrue(normalized.stack().isEmpty());
        }
      }
    }
    assertTrue(found > 0);
  }

  static final class ParkFixture {}
}
