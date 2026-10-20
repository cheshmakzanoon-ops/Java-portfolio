package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Complete records are immutable; only an incomplete final frame is truncated on recovery. */
public final class Journal implements AutoCloseable {
  public static final int HEADER = 24,
      MAX_PAYLOAD = 65536,
      MAX_SNAPSHOT = 16 * 1024 * 1024,
      MAX_ENTRIES = 1_100_000;
  private static final int MAGIC = 0x4d434f52, VERSION = 1;
  private static final ObjectMapper JSON = CommandJson.create();

  public interface Fault {
    void at(String boundary) throws IOException;
  }

  public record Entry(long sequence, Command command) {}

  public record Payload(String runId, Command command) {}

  public record Snapshot(
      int version, long lastSequence, String prefixDigest, Checkpoint checkpoint) {}

  public record SnapshotEnvelope(String payload, String checksum) {}

  private final FileChannel channel;
  private final FileLock lock;
  private final String runId;
  private final Path path;
  private final Fault fault;
  private final List<Entry> entries = new ArrayList<>();
  private boolean snapshotUsed;
  private Snapshot restoredSnapshot;

  public Journal(Path path, String runId) throws IOException {
    this(path, runId, b -> {});
  }

  public Journal(Path path, String runId, Fault fault) throws IOException {
    if (!identifier(runId)) throw new IllegalArgumentException("invalid run ID");
    this.runId = runId;
    this.path = path;
    this.fault = fault;
    Files.createDirectories(path.toAbsolutePath().getParent());
    channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      channel.close();
      throw new IOException("writer already locked", e);
    }
    if (acquired == null) {
      channel.close();
      throw new IOException("writer already locked");
    }
    lock = acquired;
    try {
      recover();
      validateSnapshot();
    } catch (Throwable t) {
      close();
      throw t;
    }
  }

  private static int crc(byte[] bytes, int length) {
    CRC32C c = new CRC32C();
    c.update(bytes, 0, length);
    return (int) c.getValue();
  }

  private void recover() throws IOException {
    long position = 0, size = channel.size();
    while (position < size) {
      if (entries.size() >= MAX_ENTRIES) throw new IOException("journal run cap exceeded");
      long start = position;
      if (size - position < HEADER) {
        truncate(start);
        break;
      }
      ByteBuffer h = ByteBuffer.allocate(HEADER);
      readFully(h, position);
      byte[] hb = h.array();
      h.flip();
      int magic = h.getInt(), version = h.getInt();
      long sequence = h.getLong();
      int length = h.getInt(), checksum = h.getInt();
      if (magic != MAGIC
          || version != VERSION
          || checksum != crc(hb, 20)
          || length < 1
          || length > MAX_PAYLOAD) throw new IOException("corrupt frame header at " + start);
      if (sequence != entries.size() + 1L) throw new IOException("journal sequence gap");
      if (size - position < HEADER + (long) length + 4) {
        truncate(start);
        break;
      }
      ByteBuffer data = ByteBuffer.allocate(length + 4);
      readFully(data, position + HEADER);
      data.flip();
      byte[] payload = new byte[length];
      data.get(payload);
      if (data.getInt() != crc(payload, length))
        throw new IOException("corrupt payload at " + start);
      Payload p = JSON.readValue(payload, Payload.class);
      if (p == null || !runId.equals(p.runId())) throw new IOException("invalid payload run ID");
      try {
        p.command().validate();
      } catch (RuntimeException e) {
        throw new IOException("invalid durable command", e);
      }
      entries.add(new Entry(sequence, p.command()));
      position += HEADER + (long) length + 4;
    }
    channel.position(channel.size());
  }

  private void truncate(long size) throws IOException {
    channel.truncate(size);
    channel.force(true);
  }

  private void readFully(ByteBuffer b, long pos) throws IOException {
    while (b.hasRemaining()) {
      int n = channel.read(b, pos + b.position());
      if (n < 0) throw new IOException("unexpected EOF");
    }
  }

  public void appendAndForce(long sequence, Command command) throws IOException {
    Objects.requireNonNull(command, "command").validate();
    if (entries.size() >= MAX_ENTRIES) throw new IOException("journal run cap exceeded");
    if (sequence != entries.size() + 1L) throw new IllegalArgumentException("sequence gap");
    fault.at("BEFORE_APPEND");
    byte[] payload = JSON.writeValueAsBytes(new Payload(runId, command));
    if (payload.length > MAX_PAYLOAD) throw new IOException("payload too large");
    ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length + 4);
    b.putInt(MAGIC).putInt(VERSION).putLong(sequence).putInt(payload.length);
    b.putInt(crc(b.array(), 20));
    b.put(payload).putInt(crc(payload, payload.length));
    b.flip();
    // The first write is intentionally the header: deterministic process-crash fault boundary.
    int oldLimit = b.limit();
    b.limit(HEADER);
    while (b.hasRemaining()) channel.write(b);
    fault.at("DURING_FRAME");
    b.limit(oldLimit);
    while (b.hasRemaining()) channel.write(b);
    fault.at("BEFORE_FORCE");
    channel.force(true);
    entries.add(new Entry(sequence, command));
    fault.at("AFTER_FORCE");
  }

  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  private String prefixDigest(long count) {
    StringBuilder s = new StringBuilder(runId);
    for (int i = 0; i < count; i++) {
      Entry e = entries.get(i);
      s.append('|').append(e.sequence()).append(':').append(e.command().hash());
    }
    return sha(s.toString());
  }

  public void snapshot() throws IOException {
    snapshot(null);
  }

  public void snapshot(Checkpoint checkpoint) throws IOException {
    Snapshot snapshot =
        new Snapshot(VERSION, entries.size(), prefixDigest(entries.size()), checkpoint);
    Path temporary = path.resolveSibling(path.getFileName() + ".snapshot.tmp"),
        target = snapshotPath();
    String payload = JSON.writeValueAsString(snapshot);
    byte[] bytes = JSON.writeValueAsBytes(new SnapshotEnvelope(payload, sha(payload)));
    if (bytes.length > MAX_SNAPSHOT)
      throw new IOException("snapshot exceeds 16MiB; full journal retained");
    try (FileChannel out =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.wrap(bytes);
      while (b.hasRemaining()) out.write(b);
      out.force(true);
    }
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.deleteIfExists(temporary);
      throw new IOException("atomic snapshot publication unavailable; journal retained", e);
    }
    try (FileChannel directory =
        FileChannel.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
      directory.force(true);
    }
  }

  private Path snapshotPath() {
    return path.resolveSibling(path.getFileName() + ".snapshot");
  }

  private void validateSnapshot() {
    if (!Files.exists(snapshotPath())) return;
    try {
      if (Files.size(snapshotPath()) > MAX_SNAPSHOT) return;
    } catch (IOException e) {
      return;
    }
    try {
      SnapshotEnvelope envelope =
          JSON.readValue(Files.readAllBytes(snapshotPath()), SnapshotEnvelope.class);
      if (!sha(envelope.payload()).equals(envelope.checksum())) return;
      Snapshot s = JSON.readValue(envelope.payload(), Snapshot.class);
      snapshotUsed =
          s.version() == VERSION
              && s.lastSequence() >= 0
              && s.lastSequence() <= entries.size()
              && s.prefixDigest().equals(prefixDigest(s.lastSequence()))
              && (s.checkpoint() == null || s.checkpoint().view().sequence() == s.lastSequence());
      if (snapshotUsed) restoredSnapshot = s;
    } catch (IOException | RuntimeException e) {
      snapshotUsed = false;
    }
  }

  public Checkpoint recoveredCheckpoint() {
    return restoredSnapshot == null ? null : restoredSnapshot.checkpoint();
  }

  public boolean validSnapshot() {
    return snapshotUsed;
  }

  public void close() throws IOException {
    try {
      if (lock.isValid()) lock.release();
    } finally {
      channel.close();
    }
  }
}
