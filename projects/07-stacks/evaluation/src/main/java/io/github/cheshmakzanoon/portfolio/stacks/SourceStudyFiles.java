package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded non-overwriting phase records. No Properties escape coercion or duplicate keys. */
final class SourceStudyFiles {
  private SourceStudyFiles() {}

  static void write(Path path, Map<String, String> values) throws IOException {
    var lines = new ArrayList<String>();
    for (var entry : new TreeMap<>(values).entrySet()) {
      if (!entry.getKey().matches("[A-Za-z0-9_.-]{1,128}")
          || entry.getValue() == null
          || entry.getValue().indexOf('\n') >= 0
          || entry.getValue().indexOf('\r') >= 0)
        throw new IOException("source phase identity syntax");
      lines.add(entry.getKey() + "=" + entry.getValue());
    }
    String text = String.join("\n", lines) + "\n";
    if (values.isEmpty()
        || values.size() > 128
        || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
      throw new IOException("source phase identity quota");
    Files.writeString(path, text, StandardOpenOption.CREATE_NEW);
  }

  static Map<String, String> read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384)
      throw new IOException("source phase identity regular file/quota");
    Map<String, String> values = new TreeMap<>();
    for (String line : Files.readAllLines(path)) {
      int equal = line.indexOf('=');
      if (equal < 1
          || !line.substring(0, equal).matches("[A-Za-z0-9_.-]{1,128}")
          || values.putIfAbsent(line.substring(0, equal), line.substring(equal + 1)) != null)
        throw new IOException("source phase duplicate/invalid key");
    }
    if (values.isEmpty() || values.size() > 128) throw new IOException("source phase key quota");
    return Collections.unmodifiableMap(values);
  }

  static void check(Map<String, String> values, String key, String expected) throws IOException {
    if (!Objects.equals(values.get(key), expected))
      throw new IOException("source phase identity mismatch: " + key);
  }

  static String hash(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("source phase input must be regular file");
    return Digests.sha256(path);
  }

  static Map<String, String> manifest(Path directory) throws IOException {
    Map<String, String> hashes = new TreeMap<>();
    try (var stream = Files.newDirectoryStream(directory)) {
      for (Path path : stream) {
        if (hashes.size() >= 128) throw new IOException("source phase artifact count quota");
        if (path.getFileName().toString().equals("identity.properties")) continue;
        if (!path.getFileName().toString().matches("[A-Za-z0-9_.-]{1,128}"))
          throw new IOException("source phase artifact name");
        hashes.put(path.getFileName().toString(), hash(path));
      }
    }
    return Collections.unmodifiableMap(hashes);
  }

  static void paths(Path path, Map<String, List<String>> rows) throws IOException {
    var lines = new ArrayList<String>();
    for (var row : new TreeMap<>(rows).entrySet()) {
      if (row.getKey().isBlank()
          || row.getKey().length() > 128
          || row.getKey().indexOf('\t') >= 0
          || row.getKey().indexOf('\r') >= 0
          || row.getKey().indexOf('\n') >= 0
          || row.getValue().size() > 20
          || row.getValue().stream().distinct().count() != row.getValue().size())
        throw new IOException("source phase paths identity/bounds");
      for (String value : row.getValue()) {
        try {
          if (value.indexOf('|') >= 0
              || value.indexOf('\t') >= 0
              || value.indexOf('\n') >= 0
              || value.indexOf('\r') >= 0
              || !SnapshotManifest.normalize(value).equals(value))
            throw new IOException("source phase path cannot be represented");
        } catch (IllegalArgumentException invalid) {
          throw new IOException("source phase path normalization", invalid);
        }
      }
      lines.add(row.getKey() + "\t" + String.join("|", row.getValue()));
    }
    String text = String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2_097_152)
      throw new IOException("source phase paths quota");
    Files.writeString(path, text, StandardOpenOption.CREATE_NEW);
  }

  static Map<String, List<String>> paths(Path path, Set<String> expectedIds) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2_097_152)
      throw new IOException("source phase paths quota");
    Map<String, List<String>> result = new TreeMap<>();
    for (String line : Files.readAllLines(path)) {
      var fields = line.split("\t", -1);
      if (fields.length != 2 || !expectedIds.contains(fields[0]) || result.containsKey(fields[0]))
        throw new IOException("source phase paths identity");
      var paths = fields[1].isEmpty() ? List.<String>of() : List.of(fields[1].split("\\|", -1));
      if (paths.size() > 20 || paths.stream().distinct().count() != paths.size())
        throw new IOException("source phase paths bounds");
      try {
        for (String value : paths)
          if (!SnapshotManifest.normalize(value).equals(value))
            throw new IOException("source phase path normalization");
      } catch (IllegalArgumentException invalid) {
        throw new IOException("source phase path normalization", invalid);
      }
      result.put(fields[0], paths);
    }
    if (!result.keySet().equals(expectedIds))
      throw new IOException("source phase missing query paths");
    return Collections.unmodifiableMap(result);
  }
}
