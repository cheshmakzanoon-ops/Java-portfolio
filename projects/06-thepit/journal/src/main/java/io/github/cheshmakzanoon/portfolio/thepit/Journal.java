package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;

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

  @FunctionalInterface
  interface Channels {
    FileChannel open(Path path, OpenOption... options) throws IOException;
  }

  private final Channels channels;
  private final FileChannel channel;
  private final FileLock lock;
  private final String runId;
  private final Path path;
  private final Fault fault;
  private final List<Entry> entries = new ArrayList<>();

  private enum State {
    OPEN,
    FAILED,
    CLOSED
  }

  private State state = State.OPEN;
  private Throwable failure;
  private boolean snapshotUsed;
  private Snapshot restoredSnapshot;

  public Journal(Path path, String runId) throws IOException {
    this(path, runId, b -> {});
  }

  public Journal(Path path, String runId, Fault fault) throws IOException {
    this(path, runId, fault, FileChannel::open);
  }

  Journal(Path path, String runId, Fault fault, Channels channels) throws IOException {
    if (!identifier(runId)) throw new IllegalArgumentException("invalid run ID");
    this.runId = runId;
    this.channels = Objects.requireNonNull(channels, "channels");
    this.path = path;
    this.fault = Objects.requireNonNull(fault, "fault");
    Files.createDirectories(path.toAbsolutePath().getParent());
    channel =
        channels.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      IOException failure = new IOException("writer already locked", e);
      closeAfterFailure(channel, failure);
      throw failure;
    } catch (IOException | RuntimeException | Error failure) {
      closeAfterFailure(channel, failure);
      throw failure;
    }
    if (acquired == null) {
      IOException failure = new IOException("writer already locked");
      closeAfterFailure(channel, failure);
      throw failure;
    }
    lock = acquired;
    try {
      recover();
      validateSnapshot();
    } catch (Throwable t) {
      closeAfterFailure(this, t);
      throw t;
    }
  }

  static void closeAfterFailure(AutoCloseable owned, Throwable primary) {
    try {
      owned.close();
    } catch (Throwable cleanup) {
      if (cleanup != primary) primary.addSuppressed(cleanup);
    }
  }

  private void requireOpen() throws IOException {
    if (state != State.OPEN)
      throw new IOException(
          state == State.FAILED ? "journal recovery required" : "journal closed", failure);
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

  public synchronized void appendAndForce(long sequence, Command command) throws IOException {
    requireOpen();
    Objects.requireNonNull(command, "command").validate();
    if (entries.size() >= MAX_ENTRIES) throw new IOException("journal run cap exceeded");
    if (sequence != entries.size() + 1L) throw new IllegalArgumentException("sequence gap");
    byte[] payload = JSON.writeValueAsBytes(new Payload(runId, command));
    if (payload.length > MAX_PAYLOAD) throw new IOException("payload too large");
    ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length + 4);
    b.putInt(MAGIC).putInt(VERSION).putLong(sequence).putInt(payload.length);
    b.putInt(crc(b.array(), 20));
    b.put(payload).putInt(crc(payload, payload.length));
    b.flip();
    try {
      fault.at("BEFORE_APPEND");
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
    } catch (IOException | RuntimeException | Error admittedFailure) {
      state = State.FAILED;
      failure = admittedFailure;
      throw admittedFailure;
    }
  }

  public synchronized List<Entry> entries() {
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

  public synchronized void snapshot(Checkpoint checkpoint) throws IOException {
    requireOpen();
    if (checkpoint != null) {
      new Book().restore(checkpoint);
      if (!matchesPrefix(entries.size(), checkpoint))
        throw new IllegalArgumentException("checkpoint does not match journal prefix");
    }
    Snapshot snapshot =
        new Snapshot(VERSION, entries.size(), prefixDigest(entries.size()), checkpoint);
    Path temporary = path.resolveSibling(path.getFileName() + ".snapshot.tmp"),
        target = snapshotPath();
    String payload = JSON.writeValueAsString(snapshot);
    byte[] bytes = JSON.writeValueAsBytes(new SnapshotEnvelope(payload, sha(payload)));
    if (bytes.length > MAX_SNAPSHOT)
      throw new IOException("snapshot exceeds 16MiB; full journal retained");
    try (FileChannel out =
        channels.open(
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
        channels.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
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
      if (snapshotUsed && s.checkpoint() != null) {
        snapshotUsed = matchesPrefix(s.lastSequence(), s.checkpoint());
      }
      if (snapshotUsed) restoredSnapshot = s;
    } catch (IOException | RuntimeException e) {
      snapshotUsed = false;
    }
  }

  private boolean matchesPrefix(long count, Checkpoint checkpoint) {
    Map<String, Result> results = checkpoint.view().results();
    if (checkpoint.view().sequence() != count || results.size() != count) return false;
    // Verify the checkpoint's ledger against initialization and recorded fills, without
    // re-executing price-time matching for the prefix that the checkpoint replaces.
    Map<String, long[]> balances = new HashMap<>();
    try {
      for (int index = 0; index < count; index++) {
        Entry entry = entries.get(index);
        Command command = entry.command();
        Result result = results.get(command.key());
        if (result == null
            || result.sequence() != entry.sequence()
            || !command.hash().equals(result.commandHash())) return false;
        if (result.code().equals("INITIALIZED")) {
          if (command.type() != Type.INIT
              || balances.putIfAbsent(
                      command.accountId(),
                      new long[] {command.initialCash(), command.initialLots()})
                  != null) return false;
        }
        for (Execution execution : result.executions()) {
          if ((command.type() != Type.NEW && command.type() != Type.REPLACE)
              || execution.sequence() != entry.sequence()
              || !command.accountId().equals(execution.takerAccount())
              || !command.orderId().equals(execution.takerOrder())
              || execution.priceTicks() <= 0
              || execution.lots() <= 0
              || execution.makerAccount().equals(execution.takerAccount())) return false;
          long cost = Math.multiplyExact(execution.priceTicks(), execution.lots());
          long[] buyer =
              balances.get(
                  command.side() == Side.BUY ? command.accountId() : execution.makerAccount());
          long[] seller =
              balances.get(
                  command.side() == Side.SELL ? command.accountId() : execution.makerAccount());
          if (buyer == null || seller == null) return false;
          buyer[0] = Math.subtractExact(buyer[0], cost);
          buyer[1] = Math.addExact(buyer[1], execution.lots());
          seller[0] = Math.addExact(seller[0], cost);
          seller[1] = Math.subtractExact(seller[1], execution.lots());
          if (buyer[0] < 0 || seller[1] < 0) return false;
        }
      }
    } catch (RuntimeException invalidCheckpoint) {
      return false;
    }
    if (!balances.keySet().equals(checkpoint.view().accounts().keySet())) return false;
    for (var entry : balances.entrySet()) {
      Balance balance = checkpoint.view().accounts().get(entry.getKey());
      if (balance.cash() != entry.getValue()[0] || balance.lots() != entry.getValue()[1])
        return false;
    }
    return true;
  }

  public Checkpoint recoveredCheckpoint() {
    return restoredSnapshot == null ? null : restoredSnapshot.checkpoint();
  }

  public boolean validSnapshot() {
    return snapshotUsed;
  }

  public synchronized void discardSnapshot() {
    restoredSnapshot = null;
    snapshotUsed = false;
  }

  public synchronized void close() throws IOException {
    if (state == State.CLOSED) return;
    state = State.CLOSED;
    try (FileChannel owned = channel;
        java.io.Closeable release =
            () -> {
              if (lock.isValid()) lock.release();
            }) {
      fault.at("BEFORE_CLOSE");
    }
  }
}
