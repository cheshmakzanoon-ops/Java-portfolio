package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Append-only transaction WAL. A suffix replacement is one checksummed transaction. */
public final class Journal implements AutoCloseable {
  public enum Boundary {
    SNAPSHOT_FLUSH,
    SNAPSHOT_RENAME,
    GENERATION_FLUSH,
    MANIFEST_FLUSH,
    MANIFEST_RENAME,
    DIRECTORY_FLUSH,
    WAL_WRITE,
    WAL_FLUSH
  }

  @FunctionalInterface
  public interface Fault {
    void at(Boundary boundary) throws IOException;
  }

  private static final int MAGIC = 0x514b5631, HEADER = 33, MAX_RECORD = 64 * 1024 * 1024;
  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Fault fault;
  private FileChannel wal;
  private long generation;
  private Durable state;

  public Journal(Path directory) throws IOException {
    this(directory, b -> {});
  }

  public Journal(Path directory, Fault fault) throws IOException {
    this.directory = directory;
    this.fault = fault;
    Files.createDirectories(directory);
    lockChannel =
        FileChannel.open(
            directory.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      lockChannel.close();
      throw new IOException("DIRECTORY_LOCKED", e);
    }
    if (lock == null) {
      lockChannel.close();
      throw new IOException("DIRECTORY_LOCKED");
    }
    try {
      Path manifest = directory.resolve("MANIFEST");
      if (!Files.exists(manifest)) {
        if (Files.exists(directory.resolve("wal-0")))
          throw new IOException("UNPUBLISHED_INITIAL_STATE: inspect directory before recovery");
        generation = 0;
        state = Durable.empty();
        publish(state);
      } else {
        String[] fields = Files.readString(manifest).trim().split(" ");
        if (fields.length != 3 || !fields[0].equals("QKV1"))
          throw new IOException("INCOMPATIBLE_MANIFEST: expected QKV1");
        generation = Long.parseLong(fields[1]);
        Path snapshot = directory.resolve("snapshot-" + fields[2]);
        byte[] body = checkedFile(snapshot);
        Snapshot s = Codec.snapshot(body);
        state = Durable.empty();
        wal =
            FileChannel.open(
                directory.resolve("wal-" + generation),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        recover();
        if (state.snapshot().index() != s.index()
            || state.snapshot().term() != s.term()
            || !Arrays.equals(Codec.snapshot(state.snapshot()), body))
          throw new IOException("MANIFEST_SNAPSHOT_MISMATCH");
      }
    } catch (IOException | RuntimeException e) {
      if (wal != null) wal.close();
      lock.release();
      lockChannel.close();
      throw e;
    }
  }

  public Durable recovered() {
    return state;
  }

  public synchronized void persist(Durable next) throws IOException {
    if (next.term() < state.term() || next.commit() < state.commit())
      throw new IOException("DURABILITY_REGRESSION");
    if (next.snapshot().index() != state.snapshot().index() || wal.size() > 8L * 1024 * 1024) {
      publish(next);
      return;
    }
    byte[] delta = delta(state, next);
    ByteBuffer record = record((byte) 2, next, delta);
    wal.position(wal.size());
    write(wal, record);
    fault.at(Boundary.WAL_WRITE);
    wal.force(true);
    fault.at(Boundary.WAL_FLUSH);
    state = next;
  }

  private void publish(Durable next) throws IOException {
    long previousSnapshot = state == null ? 0 : state.snapshot().index();
    long nextGeneration = wal == null ? generation : generation + 1;
    String snapshotName = "snapshot-" + next.snapshot().index();
    Path temp = directory.resolve(snapshotName + ".tmp"),
        published = directory.resolve(snapshotName);
    byte[] snapshot = Codec.snapshot(next.snapshot());
    writeChecked(temp, snapshot);
    if (!Arrays.equals(snapshot, checkedFile(temp))) throw new IOException("SNAPSHOT_VERIFY");
    fault.at(Boundary.SNAPSHOT_FLUSH);
    Files.move(
        temp, published, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    fault.at(Boundary.SNAPSHOT_RENAME);
    Path walTemp = directory.resolve("wal-" + nextGeneration + ".tmp");
    try (FileChannel fresh =
        FileChannel.open(
            walTemp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      write(fresh, record((byte) 1, next, Codec.durable(next)));
      fresh.force(true);
    }
    fault.at(Boundary.GENERATION_FLUSH);
    Files.move(
        walTemp,
        directory.resolve("wal-" + nextGeneration),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    syncDirectory();
    Path manifestTemp = directory.resolve("MANIFEST.tmp");
    try (FileChannel f =
        FileChannel.open(
            manifestTemp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      write(
          f,
          ByteBuffer.wrap(
              ("QKV1 " + nextGeneration + " " + next.snapshot().index() + "\n")
                  .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
      f.force(true);
    }
    fault.at(Boundary.MANIFEST_FLUSH);
    Files.move(
        manifestTemp,
        directory.resolve("MANIFEST"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    fault.at(Boundary.MANIFEST_RENAME);
    syncDirectory();
    fault.at(Boundary.DIRECTORY_FLUSH);
    if (wal != null) wal.close();
    generation = nextGeneration;
    wal =
        FileChannel.open(
            directory.resolve("wal-" + generation),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
    state = next;
    // Keep the immediately previous generation and snapshots. Never retire before manifest
    // durability.
    try (var paths = Files.list(directory)) {
      for (Path p : paths.toList()) {
        String n = p.getFileName().toString();
        if (n.matches("wal-[0-9]+") && Long.parseLong(n.substring(4)) < generation - 1)
          Files.delete(p);
        if (n.matches("snapshot-[0-9]+") && Long.parseLong(n.substring(9)) < previousSnapshot)
          Files.delete(p);
      }
    }
  }

  private void recover() throws IOException {
    long position = 0, size = wal.size();
    boolean first = true;
    while (position < size) {
      long remaining = size - position;
      if (remaining < HEADER) {
        wal.truncate(position);
        wal.force(true);
        break;
      }
      ByteBuffer header = ByteBuffer.allocate(HEADER);
      read(wal, header, position);
      header.flip();
      if (header.getInt() != MAGIC || header.getInt() != 1)
        throw new IOException("INCOMPATIBLE_WAL at " + position);
      int length = header.getInt();
      byte type = header.get();
      long index = header.getLong(), term = header.getLong();
      int payload = header.getInt();
      if (length < HEADER + 4 || length > MAX_RECORD || payload != length - HEADER - 4)
        throw new IOException("WAL_LENGTH_CORRUPTION at " + position);
      if (remaining < length) {
        wal.truncate(position);
        wal.force(true);
        break;
      }
      ByteBuffer record = ByteBuffer.allocate(length);
      read(wal, record, position);
      byte[] bytes = record.array();
      int expected = ByteBuffer.wrap(bytes, length - 4, 4).getInt();
      if (crc(Arrays.copyOf(bytes, length - 4)) != expected)
        throw new IOException("WAL_CHECKSUM_CORRUPTION at " + position);
      byte[] body = Arrays.copyOfRange(bytes, HEADER, length - 4);
      if (type == 1 && first) state = Codec.durable(body);
      else if (type == 2 && !first) state = applyDelta(state, body);
      else throw new IOException("WAL_RECORD_ORDER");
      if (last(state) != index || state.term() != term)
        throw new IOException("WAL_COORDINATE_MISMATCH");
      first = false;
      position += length;
    }
    if (first) throw new IOException("MISSING_INITIAL_WAL_RECORD");
  }

  private static byte[] delta(Durable old, Durable next) {
    return encodeDelta(old, next);
  }

  private static byte[] encodeDelta(Durable old, Durable next) {
    int prefix = 0;
    while (prefix < old.log().size() && prefix < next.log().size()) {
      Entry a = old.log().get(prefix), b = next.log().get(prefix);
      if (a.index() != b.index() || a.term() != b.term()) break;
      prefix++;
    }
    int shared = prefix;
    return Codec.encode(
        out -> {
          out.writeLong(next.term());
          out.writeInt(next.vote());
          out.writeLong(next.commit());
          out.writeInt(shared);
          out.writeInt(next.log().size() - shared);
          for (int i = shared; i < next.log().size(); i++) Codec.entry(out, next.log().get(i));
        });
  }

  private static Durable applyDelta(Durable old, byte[] bytes) throws IOException {
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      long term = in.readLong();
      int vote = in.readInt();
      long commit = in.readLong();
      int shared = Codec.count(in, old.log().size());
      int additions = Codec.count(in, 4096);
      var log = new ArrayList<>(old.log().subList(0, shared));
      for (int i = 0; i < additions; i++) log.add(Codec.entry(in));
      if (in.available() != 0) throw new IOException("WAL_TRAILING");
      if (term < old.term() || commit < old.commit()) throw new IOException("WAL_REGRESSION");
      return new Durable(term, vote, commit, old.snapshot(), log);
    }
  }

  private static long last(Durable d) {
    return d.snapshot().index() + d.log().size();
  }

  private static ByteBuffer record(byte type, Durable d, byte[] payload) {
    int length = HEADER + payload.length + 4;
    if (length > MAX_RECORD) throw new IllegalStateException("WAL_RESOURCE_EXHAUSTED");
    ByteBuffer b = ByteBuffer.allocate(length);
    b.putInt(MAGIC)
        .putInt(1)
        .putInt(length)
        .put(type)
        .putLong(last(d))
        .putLong(d.term())
        .putInt(payload.length)
        .put(payload);
    b.putInt(crc(Arrays.copyOf(b.array(), length - 4))).flip();
    return b;
  }

  private static int crc(byte[] bytes) {
    var crc = new CRC32C();
    crc.update(bytes);
    return (int) crc.getValue();
  }

  private static void writeChecked(Path path, byte[] body) throws IOException {
    try (FileChannel f =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.allocate(body.length + 4).put(body).putInt(crc(body));
      b.flip();
      write(f, b);
      f.force(true);
    }
  }

  private static byte[] checkedFile(Path path) throws IOException {
    long size = Files.size(path);
    if (size < 4 || size > MAX_RECORD) throw new IOException("SNAPSHOT_LENGTH");
    byte[] all = Files.readAllBytes(path), body = Arrays.copyOf(all, all.length - 4);
    if (crc(body) != ByteBuffer.wrap(all, all.length - 4, 4).getInt())
      throw new IOException("SNAPSHOT_CHECKSUM");
    return body;
  }

  private void syncDirectory() throws IOException {
    try (FileChannel d = FileChannel.open(directory, StandardOpenOption.READ)) {
      d.force(true);
    }
  }

  private static void write(FileChannel f, ByteBuffer b) throws IOException {
    while (b.hasRemaining()) f.write(b);
  }

  private static void read(FileChannel f, ByteBuffer b, long offset) throws IOException {
    while (b.hasRemaining()) {
      int n = f.read(b, offset + b.position());
      if (n < 0) throw new EOFException();
    }
  }

  @Override
  public void close() throws IOException {
    if (wal != null) wal.close();
    lock.release();
    lockChannel.close();
  }
}
