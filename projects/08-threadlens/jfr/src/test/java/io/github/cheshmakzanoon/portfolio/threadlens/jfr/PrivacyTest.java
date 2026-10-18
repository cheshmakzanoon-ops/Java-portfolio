package io.github.cheshmakzanoon.portfolio.threadlens.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import jdk.jfr.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyTest {
  @Name("threadlens.PrivateFixture")
  static class PrivateEvent extends Event {
    String message = "do-not-export-secret-48991";
    String property = "private-token";
  }

  @TempDir Path directory;

  @Test
  void unsupportedPrivateFieldsNeverBecomeNormalizedData() throws Exception {
    Path path = directory.resolve("private.jfr");
    try (var recording = new Recording()) {
      recording.enable(PrivateEvent.class);
      recording.start();
      new PrivateEvent().commit();
      recording.stop();
      recording.dump(path);
    }
    var imported = new RecordingImporter().read(path);
    assertEquals(0, imported.profile().eventCount());
    assertEquals(0, imported.profile().sampleCount());
    assertFalse(imported.toString().contains("do-not-export-secret"));
    assertFalse(imported.toString().contains("private-token"));
  }
}
