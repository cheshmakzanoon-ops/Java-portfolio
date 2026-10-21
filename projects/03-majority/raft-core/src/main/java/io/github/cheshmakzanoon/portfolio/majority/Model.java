package io.github.cheshmakzanoon.portfolio.majority;

import java.io.*;
import java.security.*;
import java.util.*;

/** Immutable protocol values. Binary arrays are copied at the public boundary. */
public final class Model {
  private Model() {}

  public static final String CLUSTER = "majority-local-v1";
  public static final int MAX_KEY = 256,
      MAX_VALUE = 4096,
      MAX_CLIENTS = 1024,
      MAX_DATA = 16 * 1024 * 1024,
      MAX_PENDING = 256,
      MAX_KEYS = 16384,
      MAX_SNAPSHOT = 24 * 1024 * 1024;

  public record Key(byte[] bytes) implements Comparable<Key> {
    public Key {
      bytes = bytes.clone();
      if (bytes.length > MAX_KEY) throw new IllegalArgumentException("KEY_LIMIT");
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public int compareTo(Key other) {
      return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && Arrays.equals(bytes, k.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(bytes);
    }
  }

  public enum Kind {
    PUT,
    DELETE,
    CAS
  }

  public record Command(
      String client, long sequence, Kind kind, Key key, byte[] value, byte[] expected) {
    public Command {
      Objects.requireNonNull(client);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(key);
      if (kind != Kind.DELETE && value == null)
        throw new IllegalArgumentException("VALUE_REQUIRED");
      value = copy(value);
      expected = copy(expected);
      if (!validClient(client)
          || sequence < 1
          || length(value) > MAX_VALUE
          || length(expected) > MAX_VALUE) throw new IllegalArgumentException("REQUEST_LIMIT");
    }

    @Override
    public byte[] value() {
      return copy(value);
    }

    @Override
    public byte[] expected() {
      return copy(expected);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Command c
          && sequence == c.sequence
          && client.equals(c.client)
          && kind == c.kind
          && key.equals(c.key)
          && Arrays.equals(value, c.value)
          && Arrays.equals(expected, c.expected);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          client, sequence, kind, key, Arrays.hashCode(value), Arrays.hashCode(expected));
    }

    public byte[] digest() {
      try {
        return MessageDigest.getInstance("SHA-256").digest(Codec.command(this));
      } catch (NoSuchAlgorithmException e) {
        throw new AssertionError(e);
      }
    }
  }

  public record Result(String code, byte[] value, int leader) {
    public Result {
      value = copy(value);
    }

    @Override
    public byte[] value() {
      return copy(value);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Result r
          && code.equals(r.code)
          && leader == r.leader
          && Arrays.equals(value, r.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(code, Arrays.hashCode(value), leader);
    }
  }

  public record Session(long sequence, byte[] digest, Result result) {
    public Session {
      Objects.requireNonNull(digest);
      Objects.requireNonNull(result);
      if (sequence < 1
          || digest.length != 32
          || !(result.code().equals("OK") || result.code().equals("CAS_MISMATCH")))
        throw new IllegalArgumentException("INVALID_SESSION");
      digest = digest.clone();
    }

    @Override
    public byte[] digest() {
      return digest.clone();
    }
  }

  public record Entry(long index, long term, Command command) {}

  public record Snapshot(
      long index,
      long term,
      String cluster,
      NavigableMap<Key, byte[]> data,
      Map<String, Session> sessions) {
    public Snapshot {
      if (index < 0 || term < 0 || (index == 0) != (term == 0) || !CLUSTER.equals(cluster))
        throw new IllegalArgumentException("INVALID_SNAPSHOT_COORDINATES");
      if (data.size() > MAX_KEYS || sessions.size() > MAX_CLIENTS)
        throw new IllegalArgumentException("SNAPSHOT_RESOURCE_LIMIT");
      var d = new TreeMap<Key, byte[]>();
      long retained = 0;
      for (var entry : data.entrySet()) {
        byte[] value = Objects.requireNonNull(entry.getValue());
        retained += entry.getKey().bytes().length + value.length;
        if (value.length > MAX_VALUE || retained > MAX_DATA)
          throw new IllegalArgumentException("SNAPSHOT_RESOURCE_LIMIT");
        d.put(entry.getKey(), value.clone());
      }
      for (String client : sessions.keySet())
        if (!validClient(client)) throw new IllegalArgumentException("INVALID_SESSION_CLIENT");
      data = Collections.unmodifiableNavigableMap(d);
      sessions = Map.copyOf(sessions);
    }

    @Override
    public NavigableMap<Key, byte[]> data() {
      var copy = new TreeMap<Key, byte[]>();
      data.forEach((k, v) -> copy.put(k, v.clone()));
      return Collections.unmodifiableNavigableMap(copy);
    }

    public static Snapshot empty() {
      return new Snapshot(0, 0, CLUSTER, new TreeMap<>(), Map.of());
    }
  }

  public record Durable(long term, int vote, long commit, Snapshot snapshot, List<Entry> log) {
    public Durable {
      log = List.copyOf(log);
      if (term < snapshot.term() || vote < -1 || vote > 2 || commit < snapshot.index())
        throw new IllegalArgumentException("INVALID_STATE");
      long i = snapshot.index();
      long previousTerm = snapshot.term();
      for (Entry e : log) {
        if (i == Long.MAX_VALUE
            || e.index() != ++i
            || e.term() > term
            || e.term() < 1
            || e.term() < previousTerm) throw new IllegalArgumentException("NONCONTIGUOUS_LOG");
        previousTerm = e.term();
      }
      if (commit > i) throw new IllegalArgumentException("COMMIT_BEYOND_LOG");
    }

    public static Durable empty() {
      return new Durable(0, -1, 0, Snapshot.empty(), List.of());
    }
  }

  public sealed interface Rpc
      permits VoteRequest,
          VoteResponse,
          AppendRequest,
          AppendResponse,
          SnapshotRequest,
          SnapshotResponse {
    long term();

    long request();
  }

  public record VoteRequest(long term, long request, int candidate, long lastIndex, long lastTerm)
      implements Rpc {}

  public record VoteResponse(long term, long request, boolean granted) implements Rpc {}

  public record AppendRequest(
      long term,
      long request,
      int leader,
      long previousIndex,
      long previousTerm,
      List<Entry> entries,
      long commit,
      long read)
      implements Rpc {
    public AppendRequest {
      entries = List.copyOf(entries);
    }
  }

  public record AppendResponse(long term, long request, boolean success, long match, long read)
      implements Rpc {}

  public record SnapshotRequest(
      long term,
      long request,
      int leader,
      long index,
      long snapshotTerm,
      int offset,
      int total,
      byte[] chunk)
      implements Rpc {
    public SnapshotRequest {
      chunk = chunk.clone();
    }

    @Override
    public byte[] chunk() {
      return chunk.clone();
    }
  }

  public record SnapshotResponse(long term, long request, long index, int nextOffset, boolean done)
      implements Rpc {}

  private static boolean validClient(String client) {
    return client != null && !client.isBlank() && client.length() <= 128;
  }

  static byte[] copy(byte[] b) {
    return b == null ? null : b.clone();
  }

  static int length(byte[] b) {
    return b == null ? 0 : b.length;
  }

  /** Strict versioned serialization shared with adapters; never Java object deserialization. */
  public static final class Codec {
    private Codec() {}

    public interface Writer {
      void write(DataOutputStream out) throws IOException;
    }

    public static byte[] encode(Writer writer) {
      try {
        var b = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(b)) {
          writer.write(out);
        }
        return b.toByteArray();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    public static void bytes(DataOutputStream out, byte[] b) throws IOException {
      out.writeInt(b == null ? -1 : b.length);
      if (b != null) out.write(b);
    }

    public static byte[] bytes(DataInputStream in, int max) throws IOException {
      int n = in.readInt();
      if (n == -1) return null;
      if (n < 0 || n > max || n > in.available()) throw new IOException("INVALID_LENGTH");
      return in.readNBytes(n);
    }

    public static byte[] command(Command c) {
      return encode(out -> writeCommand(out, c));
    }

    public static void writeCommand(DataOutputStream out, Command c) throws IOException {
      out.writeBoolean(c != null);
      if (c == null) return;
      out.writeUTF(c.client());
      out.writeLong(c.sequence());
      out.writeByte(c.kind().ordinal());
      bytes(out, c.key().bytes());
      bytes(out, c.value());
      bytes(out, c.expected());
    }

    public static Command readCommand(DataInputStream in) throws IOException {
      if (!in.readBoolean()) return null;
      String c = in.readUTF();
      long seq = in.readLong();
      int kind = in.readUnsignedByte();
      if (kind >= Kind.values().length) throw new IOException("KIND");
      return new Command(
          c,
          seq,
          Kind.values()[kind],
          new Key(Objects.requireNonNull(bytes(in, MAX_KEY))),
          bytes(in, MAX_VALUE),
          bytes(in, MAX_VALUE));
    }

    public static void entry(DataOutputStream out, Entry e) throws IOException {
      out.writeLong(e.index());
      out.writeLong(e.term());
      writeCommand(out, e.command());
    }

    public static Entry entry(DataInputStream in) throws IOException {
      return new Entry(in.readLong(), in.readLong(), readCommand(in));
    }

    public static byte[] snapshot(Snapshot s) {
      byte[] body =
          encode(
              out -> {
                out.writeInt(1);
                out.writeLong(s.index());
                out.writeLong(s.term());
                out.writeUTF(s.cluster());
                out.writeInt(3);
                out.writeInt(0);
                out.writeInt(1);
                out.writeInt(2);
                out.writeInt(s.data().size());
                for (var e : s.data().entrySet()) {
                  bytes(out, e.getKey().bytes());
                  bytes(out, e.getValue());
                }
                out.writeInt(s.sessions().size());
                for (var e : new TreeMap<>(s.sessions()).entrySet()) {
                  out.writeUTF(e.getKey());
                  out.writeLong(e.getValue().sequence());
                  bytes(out, e.getValue().digest());
                  result(out, e.getValue().result());
                }
              });
      var crc = new java.util.zip.CRC32C();
      crc.update(body);
      return java.nio.ByteBuffer.allocate(body.length + 4)
          .put(body)
          .putInt((int) crc.getValue())
          .array();
    }

    public static Snapshot snapshot(byte[] b) throws IOException {
      if (b.length < 4 || b.length > MAX_SNAPSHOT) throw new IOException("SNAPSHOT_LENGTH");
      byte[] body = Arrays.copyOf(b, b.length - 4);
      var crc = new java.util.zip.CRC32C();
      crc.update(body);
      if ((int) crc.getValue() != java.nio.ByteBuffer.wrap(b, b.length - 4, 4).getInt())
        throw new IOException("SNAPSHOT_CHECKSUM");
      b = body;
      try (var in = new DataInputStream(new ByteArrayInputStream(b))) {
        if (in.readInt() != 1) throw new IOException("SNAPSHOT_VERSION");
        long index = in.readLong(), term = in.readLong();
        String cluster = in.readUTF();
        if (!CLUSTER.equals(cluster)) throw new IOException("CLUSTER");
        if (in.readInt() != 3 || in.readInt() != 0 || in.readInt() != 1 || in.readInt() != 2)
          throw new IOException("MEMBERSHIP");
        int n = count(in, MAX_KEYS);
        var data = new TreeMap<Key, byte[]>();
        int total = 0;
        for (int i = 0; i < n; i++) {
          var k = new Key(Objects.requireNonNull(bytes(in, MAX_KEY)));
          var v = Objects.requireNonNull(bytes(in, MAX_VALUE));
          total += k.bytes().length + v.length;
          if (total > MAX_DATA || data.put(k, v) != null) throw new IOException("DATA_LIMIT");
        }
        int m = count(in, MAX_CLIENTS);
        var sessions = new HashMap<String, Session>();
        for (int i = 0; i < m; i++) {
          String client = in.readUTF();
          Session session =
              new Session(in.readLong(), Objects.requireNonNull(bytes(in, 32)), result(in));
          if (sessions.putIfAbsent(client, session) != null)
            throw new IOException("DUPLICATE_SESSION_CLIENT");
        }
        if (in.available() != 0) throw new IOException("TRAILING");
        return new Snapshot(index, term, cluster, data, sessions);
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IOException("INVALID_SNAPSHOT", e);
      }
    }

    public static void result(DataOutputStream out, Result r) throws IOException {
      out.writeUTF(r.code());
      bytes(out, r.value());
      out.writeInt(r.leader());
    }

    public static Result result(DataInputStream in) throws IOException {
      return new Result(in.readUTF(), bytes(in, MAX_VALUE), in.readInt());
    }

    public static byte[] durable(Durable d) {
      return encode(
          out -> {
            out.writeInt(1);
            out.writeLong(d.term());
            out.writeInt(d.vote());
            out.writeLong(d.commit());
            bytes(out, snapshot(d.snapshot()));
            out.writeInt(d.log().size());
            for (Entry e : d.log()) entry(out, e);
          });
    }

    public static Durable durable(byte[] b) throws IOException {
      try (var in = new DataInputStream(new ByteArrayInputStream(b))) {
        if (in.readInt() != 1) throw new IOException("WAL_VERSION");
        long term = in.readLong();
        int vote = in.readInt();
        long commit = in.readLong();
        Snapshot s = snapshot(Objects.requireNonNull(bytes(in, MAX_SNAPSHOT)));
        int n = count(in, 100000);
        var log = new ArrayList<Entry>();
        for (int i = 0; i < n; i++) log.add(entry(in));
        if (in.available() != 0) throw new IOException("TRAILING");
        return new Durable(term, vote, commit, s, log);
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new IOException("INVALID_DURABLE_STATE", e);
      }
    }

    public static int count(DataInputStream in, int max) throws IOException {
      int n = in.readInt();
      if (n < 0 || n > max) throw new IOException("COUNT_LIMIT");
      return n;
    }
  }
}
