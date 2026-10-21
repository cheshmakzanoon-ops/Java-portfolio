package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class IngestionJob {
  public record Registered(String id, String manifestHash, SnapshotManifest manifest) {}

  private final Path root;

  public IngestionJob(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
  }

  public synchronized Registered register(String key, SnapshotManifest manifest)
      throws IOException {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new IllegalArgumentException("idempotency key");
    String id = Digests.sha256(manifest.tenantId() + "\n" + manifest.repositoryId() + "\n" + key);
    Path p = root.resolve(id);
    String hash = manifest.digest();
    if (Files.exists(p)) {
      SnapshotManifest old = load(p);
      if (!old.digest().equals(hash)) throw new Conflict();
      return new Registered(id, hash, old);
    }
    DurableFiles.atomicWrite(p, manifest.bytes());
    return new Registered(id, hash, manifest);
  }

  public Registered get(String id) throws IOException {
    if (!id.matches("[a-f0-9]{64}")) throw new IOException("job not found");
    SnapshotManifest m = load(root.resolve(id));
    return new Registered(id, m.digest(), m);
  }

  /** Completion is an immutable, checksummed receipt, not a presence marker. */
  public synchronized boolean completed(String id) throws IOException {
    Registered job = get(id);
    Path receipt = root.resolve(id + ".done");
    if (!Files.exists(receipt)) return false;
    if (Files.size(receipt) > 512) throw new IOException("completion receipt quota");
    String[] lines = Files.readString(receipt).split("\\n", -1);
    if (lines.length != 5
        || !lines[0].equals("1")
        || !lines[1].equals(id)
        || !lines[2].equals(job.manifestHash())
        || !lines[3].matches("[a-f0-9-]{36}")
        || !Digests.sha256(String.join("\n", Arrays.copyOf(lines, 4))).equals(lines[4]))
      throw new IOException("corrupt completion receipt");
    return true;
  }

  public synchronized void complete(String id, String manifestHash, String generation)
      throws IOException {
    Registered job = get(id);
    if (!job.manifestHash().equals(manifestHash) || !generation.matches("[a-f0-9-]{36}"))
      throw new IOException("committed job identity mismatch");
    if (completed(id)) return;
    String body = "1\n" + id + "\n" + manifestHash + "\n" + generation;
    DurableFiles.atomicWrite(
        root.resolve(id + ".done"),
        (body + "\n" + Digests.sha256(body)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private SnapshotManifest load(Path path) throws IOException {
    if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("manifest metadata byte quota");
    return SnapshotManifest.read(Files.readAllBytes(path));
  }

  public static final class Conflict extends IllegalArgumentException {
    public Conflict() {
      super("idempotency conflict");
    }
  }
}
