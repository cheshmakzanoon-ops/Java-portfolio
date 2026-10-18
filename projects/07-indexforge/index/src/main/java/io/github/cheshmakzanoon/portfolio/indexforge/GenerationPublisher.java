package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;

public final class GenerationPublisher implements AutoCloseable {
  private static final class Handle {
    final String id;
    final Directory directory;
    final DirectoryReader reader;
    int leases;
    boolean retired;

    Handle(String id, Directory d, DirectoryReader r) {
      this.id = id;
      directory = d;
      reader = r;
    }
  }

  private final Path root;
  private final Map<String, Handle> handles = new HashMap<>();
  private String current;
  private boolean closed;
  private boolean publicationUncertain;

  public GenerationPublisher(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root.resolve("generations"));
    Files.createDirectories(root.resolve("quarantine"));
    Files.createDirectories(root.resolve("staging"));
    recover();
    if (current == null) {
      try (var generations = Files.list(root.resolve("generations"))) {
        if (generations.findAny().isPresent())
          throw new IOException("no valid generation pointer; preserve files for recovery");
      }
    }
    pruneFiles();
  }

  private void recover() throws IOException {
    for (String pointer : List.of("CURRENT", "PREVIOUS")) {
      Path p = root.resolve(pointer);
      if (!Files.exists(p)) continue;
      try {
        String id = readPointer(p);
        Handle h = open(id);
        handles.put(id, h);
        current = id;
        if (pointer.equals("PREVIOUS"))
          DurableFiles.atomicWrite(root.resolve("CURRENT"), pointer(id));
        break;
      } catch (IOException | IllegalArgumentException ignored) {
        Files.move(
            p,
            root.resolve("quarantine").resolve(pointer + "-" + UUID.randomUUID()),
            StandardCopyOption.ATOMIC_MOVE);
      }
    }
    try (var staged = Files.list(root.resolve("staging"))) {
      for (Path p : staged.toList())
        Files.move(
            p,
            root.resolve("quarantine").resolve(p.getFileName() + "-incomplete"),
            StandardCopyOption.ATOMIC_MOVE);
    }
  }

  private byte[] pointer(String id) throws IOException {
    String body = id + "\n" + checksumText(root.resolve("generations").resolve(id));
    return (body + "\n" + Digests.sha256(body)).getBytes(StandardCharsets.UTF_8);
  }

  private String readPointer(Path path) throws IOException {
    if (Files.size(path) > 512) throw new IOException("pointer quota");
    String[] p = Files.readString(path).split("\n");
    if (p.length != 3
        || !p[0].matches("[a-f0-9-]{36}")
        || !Digests.sha256(p[0] + "\n" + p[1]).equals(p[2]))
      throw new IOException("corrupt pointer");
    Path dir = root.resolve("generations").resolve(p[0]);
    if (!p[1].equals(checksumText(dir))) throw new IOException("checksum mismatch");
    return p[0];
  }

  private String checksumText(Path directory) throws IOException {
    Path path = directory.resolve("generation.sha256");
    if (Files.size(path) != 64) throw new IOException("checksum metadata bounds");
    String checksum = Files.readString(path);
    if (!checksum.matches("[a-f0-9]{64}")) throw new IOException("checksum encoding");
    return checksum;
  }

  private Handle open(String id) throws IOException {
    Path dir = root.resolve("generations").resolve(id);
    if (!GenerationBuilder.checksum(dir).equals(checksumText(dir)))
      throw new IOException("generation checksum mismatch");
    if (Files.size(dir.resolve("manifest.bin")) > 64L * 1024 * 1024
        || Files.size(dir.resolve("snapshot.bin")) > 64L * 1024 * 1024)
      throw new IOException("generation metadata byte quota");
    SnapshotManifest manifest =
        SnapshotManifest.read(Files.readAllBytes(dir.resolve("manifest.bin")));
    Directory fs = FSDirectory.open(dir);
    DirectoryReader reader = null;
    try {
      reader = DirectoryReader.open(fs);
      Map<String, String> metadata = reader.getIndexCommit().getUserData();
      if ((metadata.containsKey("job")
              && !metadata.get("job").isEmpty()
              && !metadata.get("job").matches("[a-f0-9]{64}"))
          || !manifest.digest().equals(metadata.get("manifest"))
          || !id.equals(metadata.get("generation"))
          || !Integer.toString(reader.numDocs()).equals(metadata.get("count"))
          || !Integer.toString(SnapshotManifest.SCHEMA).equals(metadata.get("schema"))) {
        throw new IOException("commit metadata mismatch");
      }
      if (!Digests.sha256(Files.readAllBytes(dir.resolve("snapshot.bin")))
          .equals(metadata.get("snapshot"))) {
        throw new IOException("snapshot metadata mismatch");
      }
      List<SourceDocument> sources = new ArrayList<>();
      long sourceBytes = 0;
      var stored = reader.storedFields();
      for (int i = 0; i < reader.maxDoc(); i++) {
        var d = stored.document(i);
        sourceBytes += d.get("content").getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes > GenerationBuilder.MAX_SOURCE_BYTES)
          throw new IOException("complete generation source byte quota");
        sources.add(DocumentMapper.source(d));
        if (!Digests.sha256(d.get("content")).equals(d.get("sha256"))) {
          throw new IOException("document digest mismatch");
        }
      }
      if (!Digests.sha256(GenerationBuilder.fullSnapshot(sources))
          .equals(metadata.get("snapshot"))) {
        throw new IOException("complete snapshot mismatch");
      }
      return new Handle(id, fs, reader);
    } catch (IOException | RuntimeException e) {
      if (reader != null)
        try {
          reader.close();
        } catch (IOException close) {
          e.addSuppressed(close);
        }
      try {
        fs.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      if (e instanceof IOException io) throw io;
      throw new IOException("invalid generation metadata", e);
    }
  }

  public synchronized void publish(GenerationBuilder.Built built, GenerationBuilder.Fault fault)
      throws IOException {
    ensureOpen();
    if (publicationUncertain)
      throw new IOException("publication outcome requires restart recovery");
    Path destination = root.resolve("generations").resolve(built.id());
    Files.move(built.path(), destination, StandardCopyOption.ATOMIC_MOVE);
    DurableFiles.syncDirectory(destination.getParent());
    fault.at(GenerationBuilder.Boundary.AFTER_RENAME);
    Handle next = open(built.id());
    String old = current;
    boolean installed = false;
    Throwable primary = null;
    try {
      if (old != null) DurableFiles.atomicWrite(root.resolve("PREVIOUS"), pointer(old));
      fault.at(GenerationBuilder.Boundary.BEFORE_POINTER);
      DurableFiles.atomicWrite(root.resolve("CURRENT"), pointer(built.id()));
      handles.put(built.id(), next);
      current = built.id();
      installed = true;
      fault.at(GenerationBuilder.Boundary.AFTER_POINTER);
      if (old != null) fault.at(GenerationBuilder.Boundary.RETIRE);
    } catch (IOException | RuntimeException | Error failure) {
      primary = failure;
      // atomic rename can succeed before its directory fsync reports failure. Reconcile the
      // actual pointer before allowing a subsequent writer to supersede an unknown outcome.
      if (!installed) {
        try {
          if (built.id().equals(readPointer(root.resolve("CURRENT")))) {
            handles.put(built.id(), next);
            current = built.id();
            installed = true;
          }
        } catch (IOException uncertain) {
          failure.addSuppressed(uncertain);
          // A readable old pointer is safe; an unreadable pointer must block all later writes.
          if (Files.exists(root.resolve("CURRENT"))) publicationUncertain = true;
        }
      }
      throw failure;
    } finally {
      try {
        if (!installed) {
          next.reader.close();
          next.directory.close();
        } else {
          if (old != null) {
            Handle previous = handles.get(old);
            if (previous != null) {
              previous.retired = true;
              retire(previous);
            }
          }
          pruneFiles();
        }
      } catch (IOException cleanup) {
        if (primary != null) primary.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  public synchronized GenerationLease acquire() {
    return acquire(null);
  }

  public synchronized GenerationLease acquire(String requested) {
    ensureOpen();
    String id = requested == null ? current : requested;
    Handle h = handles.get(id);
    if (h == null || h.retired) throw new RetiredGeneration();
    h.leases++;
    return new GenerationLease(id, h.reader, () -> release(h));
  }

  private synchronized void release(Handle h) {
    h.leases--;
    try {
      retire(h);
      pruneFiles();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void retire(Handle h) throws IOException {
    if (h.retired && h.leases == 0) {
      h.reader.close();
      h.directory.close();
      handles.remove(
          h.id); /* Retain previous committed files as restart fallback. Reader retirement is immediate. */
    }
  }

  private void pruneFiles() throws IOException {
    if (current == null) return;
    String previous = null;
    Path pointer = root.resolve("PREVIOUS");
    if (Files.exists(pointer)) {
      try {
        previous = readPointer(pointer);
      } catch (IOException ignored) {
      }
    }
    try (var directories = Files.list(root.resolve("generations"))) {
      for (Path path : directories.filter(Files::isDirectory).toList()) {
        String id = path.getFileName().toString();
        Handle handle = handles.get(id);
        if (!id.equals(current)
            && !id.equals(previous)
            && (handle == null || (handle.retired && handle.leases == 0))) {
          try (var files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
          }
        }
      }
    }
  }

  public synchronized List<SourceDocument> sources() {
    if (current == null) return List.of();
    try (GenerationLease lease = acquire()) {
      List<SourceDocument> result = new ArrayList<>();
      var stored = lease.reader().storedFields();
      for (int i = 0; i < lease.reader().maxDoc(); i++)
        result.add(DocumentMapper.source(stored.document(i)));
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public record CommittedJob(String id, String manifestHash, String generation) {}

  public synchronized CommittedJob committedJob() throws IOException {
    if (publicationUncertain)
      throw new IOException("publication outcome requires restart recovery");
    if (current == null) return null;
    Map<String, String> metadata = handles.get(current).reader.getIndexCommit().getUserData();
    String job = metadata.get("job");
    return job == null || job.isEmpty()
        ? null
        : new CommittedJob(job, metadata.get("manifest"), current);
  }

  public synchronized String currentId() {
    return current;
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("publisher closed");
  }

  @Override
  public synchronized void close() throws IOException {
    for (Handle h : handles.values()) {
      if (h.leases != 0) throw new IOException("active leases");
      h.reader.close();
      h.directory.close();
    }
    handles.clear();
    closed = true;
  }

  public static final class RetiredGeneration extends IllegalStateException {
    public RetiredGeneration() {
      super("generation unavailable");
    }
  }
}
