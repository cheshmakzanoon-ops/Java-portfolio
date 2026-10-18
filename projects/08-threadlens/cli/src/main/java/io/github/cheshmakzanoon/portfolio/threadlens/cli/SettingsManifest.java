package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.threadlens.jfr.EventCapabilities;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * Only declared JFR settings enter normalized reports, never arbitrary manifest fields or secrets.
 */
public final class SettingsManifest {
  private SettingsManifest() {}

  public static Map<String, String> read(Path path) throws IOException {
    if (Files.size(path) > 65536) throw new IOException("settings manifest exceeds64KiB");
    Map<String, String> settings =
        new ObjectMapper().readValue(path.toFile(), new TypeReference<Map<String, String>>() {});
    for (var entry : settings.entrySet()) {
      String[] key = entry.getKey().split("#", -1);
      String value = entry.getValue();
      if (key.length != 2
          || !EventCapabilities.REQUESTED.contains(key[0])
          || value == null
          || value.length() > 64) throw new IOException("unsupported settings manifest key/value");
      boolean valid =
          switch (key[1]) {
            case "enabled", "stackTrace" -> value.equals("true") || value.equals("false");
            case "period", "threshold" ->
                value.matches("[0-9]+(?:\\.[0-9]+)?\\s*(?:ns|us|ms|s|m|h|d)");
            case "throttle" -> value.matches("[0-9]+/(?:s|m|h)") || value.equals("off");
            default -> false;
          };
      if (!valid) throw new IOException("invalid declared JFR setting");
    }
    return settings;
  }
}
