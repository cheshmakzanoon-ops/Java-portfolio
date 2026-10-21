package io.github.cheshmakzanoon.portfolio.latearrival;

import java.io.*;
import java.sql.SQLException;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Same-version snapshot encoding; only the concrete retained state graph may be restored. */
final class SnapshotCodec {
  static final long MAX_BYTES = 536_870_912;
  static final long MAX_ARRAY = 10_000_000;
  private static final Set<Class<?>> CLASSES =
      Set.of(
          Engine.State.class,
          Engine.Limits.class,
          Engine.Key.class,
          Engine.Pane.class,
          Engine.Window.class,
          Engine.Aggregate.class,
          Engine.Result.class,
          Engine.Partition.class,
          Object.class,
          String.class,
          Long.class,
          Integer.class,
          Number.class,
          java.util.HashMap.class,
          java.util.HashSet.class,
          java.util.TreeMap.class,
          java.util.ArrayList.class,
          java.util.Map.Entry.class);

  private SnapshotCodec() {}

  static byte[] encode(Serializable object) throws SQLException {
    try (var bytes = new ByteArrayOutputStream();
        var gzip = new GZIPOutputStream(bytes);
        var out = new ObjectOutputStream(gzip)) {
      out.writeObject(object);
      out.flush();
      gzip.finish();
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw new SQLException("STATE_ENCODING", failure);
    }
  }

  static <T> T decode(byte[] data, Class<T> type) throws SQLException {
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data));
        var bounded = new ByteBudget(gzip, MAX_BYTES);
        var in = new ObjectInputStream(bounded)) {
      in.setObjectInputFilter(SnapshotCodec::filter);
      var value = in.readObject();
      if (!type.isInstance(value)) throw new InvalidObjectException("UNEXPECTED_SNAPSHOT_ROOT");
      return type.cast(value);
    } catch (IOException | ClassNotFoundException | ClassCastException failure) {
      throw new SQLException("STATE_DECODING", failure);
    }
  }

  static ObjectInputFilter.Status filter(ObjectInputFilter.FilterInfo info) {
    if (info.depth() > 40
        || info.references() > 10_000_000
        || info.streamBytes() > MAX_BYTES
        || info.arrayLength() > MAX_ARRAY) return ObjectInputFilter.Status.REJECTED;
    Class<?> type = info.serialClass();
    if (type == null) return ObjectInputFilter.Status.UNDECIDED;
    while (type.isArray()) type = type.getComponentType();
    return type.isPrimitive() || CLASSES.contains(type)
        ? ObjectInputFilter.Status.ALLOWED
        : ObjectInputFilter.Status.REJECTED;
  }

  /** Counts decompressed bytes even where Java serialization does not invoke its object filter. */
  static final class ByteBudget extends FilterInputStream {
    private long remaining;

    ByteBudget(InputStream stream, long limit) {
      super(stream);
      remaining = limit;
    }

    @Override
    public long skip(long count) throws IOException {
      long skipped = 0;
      var buffer = new byte[8192];
      while (skipped < count) {
        int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
        if (read < 0) break;
        skipped += read;
      }
      return skipped;
    }

    @Override
    public int read() throws IOException {
      int result = in.read();
      if (result >= 0 && --remaining < 0) throw new IOException("SNAPSHOT_BYTE_BOUND");
      return result;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int result = in.read(buffer, offset, (int) Math.min(length, remaining + 1));
      if (result > 0 && (remaining -= result) < 0) throw new IOException("SNAPSHOT_BYTE_BOUND");
      return result;
    }
  }
}
