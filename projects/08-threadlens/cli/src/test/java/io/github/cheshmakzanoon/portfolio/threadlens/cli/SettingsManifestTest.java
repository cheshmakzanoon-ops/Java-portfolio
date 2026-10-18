package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsManifestTest {
  @TempDir Path directory;

  @Test
  void arbitraryManifestFieldsCannotLeakSecrets() throws Exception {
    Path p = directory.resolve("settings.json");
    Files.writeString(p, "{\"API_KEY\":\"fixture-secret\"}");
    assertThrows(java.io.IOException.class, () -> SettingsManifest.read(p));
    Files.writeString(
        p, "{\"jdk.ExecutionSample#period\":\"20 ms\",\"jdk.ExecutionSample#enabled\":\"true\"}");
    assertEquals("20 ms", SettingsManifest.read(p).get("jdk.ExecutionSample#period"));
    Files.writeString(p, "{\"jdk.ExecutionSample#period\":\"secret\"}");
    assertThrows(java.io.IOException.class, () -> SettingsManifest.read(p));
  }
}
