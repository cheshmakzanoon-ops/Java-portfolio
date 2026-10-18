package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.OptionalInt;

public final class CheckpointStore {
  private final Path root;

  public CheckpointStore(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
  }

  public void save(String manifest, int completed) throws IOException {
    if (completed < 0) throw new IllegalArgumentException("checkpoint");
    String payload = manifest + "\n" + completed;
    DurableFiles.atomicWrite(
        root.resolve(manifest),
        (payload + "\n" + Digests.sha256(payload)).getBytes(StandardCharsets.UTF_8));
  }

  public OptionalInt read(String manifest) throws IOException {
    Path p = root.resolve(manifest);
    if (!Files.exists(p)) return OptionalInt.empty();
    if (Files.size(p) > 1024) throw new IOException("checkpoint quota");
    String[] parts = Files.readString(p).split("\n");
    if (parts.length != 3
        || !parts[0].equals(manifest)
        || !Digests.sha256(parts[0] + "\n" + parts[1]).equals(parts[2]))
      throw new IOException("corrupt checkpoint");
    try {
      int completed = Integer.parseInt(parts[1]);
      if (completed < 0 || completed > 100_000) throw new IOException("checkpoint bounds");
      return OptionalInt.of(completed);
    } catch (NumberFormatException e) {
      throw new IOException("corrupt checkpoint", e);
    }
  }
}
