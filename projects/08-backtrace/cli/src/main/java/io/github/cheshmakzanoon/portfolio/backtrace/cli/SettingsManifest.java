package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.backtrace.jfr.EventCapabilities;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * Only declared JFR settings enter normalized reports, never arbitrary manifest fields or secrets.
 */
public final class SettingsManifest {
  private SettingsManifest() {}

  public static Map<String, String> read(Path path) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(path)) {
      bytes = input.readNBytes(65537);
    }
    if (bytes.length > 65536) throw new IOException("settings manifest exceeds 64 KiB");
    var factory =
        com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    var document =
        new ObjectMapper(factory)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(bytes);
    if (document == null || !document.isObject() || document.isEmpty() || document.size() > 128)
      throw new IOException("settings manifest must declare producer settings");
    Map<String, String> settings = new java.util.TreeMap<>();
    for (var entry : document.properties()) {
      if (!entry.getValue().isTextual())
        throw new IOException("declared JFR setting values must be strings");
      settings.put(entry.getKey(), entry.getValue().textValue());
    }
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
    return Map.copyOf(settings);
  }
}
