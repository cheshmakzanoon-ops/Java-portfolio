package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsManifestTest {
  @Test
  void duplicateAndEmptySettingsCannotMasqueradeAsAProducerDeclaration() throws Exception {
    Path path = directory.resolve("ambiguous.json");
    for (String json :
        java.util.List.of(
            "{}",
            "null",
            "{\"jdk.ExecutionSample#enabled\":\"true\",\"jdk.ExecutionSample#enabled\":\"false\"}")) {
      Files.writeString(path, json);
      assertThrows(java.io.IOException.class, () -> SettingsManifest.read(path));
    }
  }

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

  @Test
  void typedScalarsAndTrailingDocumentsCannotChangeAProducerDeclaration() throws Exception {
    Path path = directory.resolve("strict.json");
    for (String json :
        java.util.List.of(
            "{\"jdk.ExecutionSample#enabled\":true}",
            "{\"jdk.ExecutionSample#enabled\":false}",
            "{\"jdk.ExecutionSample#period\":20}",
            "{\"jdk.ExecutionSample#period\":20.5}",
            "{\"jdk.ExecutionSample#enabled\":null}",
            "{\"jdk.ExecutionSample#enabled\":[]}",
            "{\"jdk.ExecutionSample#enabled\":{}}",
            "[]",
            "\"settings\"",
            "{\"jdk.ExecutionSample#enabled\":\"true\"}{}",
            "{\"jdk.ExecutionSample#enabled\":\"true\"}null")) {
      Files.writeString(path, json);
      assertThrows(java.io.IOException.class, () -> SettingsManifest.read(path), json);
    }
    Files.writeString(path, "{\"jdk.ExecutionSample#enabled\":\"true\"}\n\t ");
    assertEquals(
        java.util.Map.of("jdk.ExecutionSample#enabled", "true"), SettingsManifest.read(path));
  }

  @Test
  void allDeclaredSettingKindsKeepTheirExactStringValues() throws Exception {
    Path path = directory.resolve("accepted.json");
    Files.writeString(
        path,
        """
        {
          "jdk.ExecutionSample#enabled": "true",
          "jdk.ExecutionSample#stackTrace": "false",
          "jdk.ExecutionSample#period": "20 ms",
          "jdk.JavaMonitorEnter#threshold": "1.5 ms",
          "jdk.ObjectAllocationSample#throttle": "50/s"
        }
        """);
    var settings = SettingsManifest.read(path);
    assertEquals(5, settings.size());
    assertEquals("1.5 ms", settings.get("jdk.JavaMonitorEnter#threshold"));
    assertEquals("50/s", settings.get("jdk.ObjectAllocationSample#throttle"));
    assertThrows(UnsupportedOperationException.class, () -> settings.clear());
  }
}
