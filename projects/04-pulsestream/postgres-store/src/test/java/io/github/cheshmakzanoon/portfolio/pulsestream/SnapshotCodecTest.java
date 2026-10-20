package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {
  @Test
  void legacyBytesRestoreTheCompleteRetainedGraphAndResultPayload() throws Exception {
    var state = new Engine.State("run", new Engine.Limits(1, 10, 100, 100, 1000), 7);
    var key = new Engine.Key("tenant", "sensor");
    var pane = new Engine.Pane(key, 0);
    var window = new Engine.Window(key, "TUMBLING", 0);
    var aggregate = new Engine.Aggregate(2, 35, 10, 25);
    state.panes.put(pane, aggregate);
    state.admitted.add(key);
    state.pending.add(window);
    state.results.put(window, new Engine.Result(window, aggregate, 2, false));
    state.offsets.put(0, 9L);
    state.watermark = 60_000;
    state.duplicates = 3;
    var restored = SnapshotCodec.decode(legacy(state), Engine.State.class);
    assertEquals(state.runId, restored.runId);
    assertEquals(state.limits, restored.limits);
    assertEquals(state.panes, restored.panes);
    assertEquals(state.results, restored.results);
    assertEquals(state.pending, restored.pending);
    assertEquals(state.admitted, restored.admitted);
    assertEquals(state.offsets, restored.offsets);
    assertEquals(state.partitions, restored.partitions);
    assertEquals(60_000, restored.watermark);
    assertEquals(3, restored.duplicates);
    var result = state.results.get(window);
    assertEquals(result, SnapshotCodec.decode(SnapshotCodec.encode(result), Engine.Result.class));
  }

  @Test
  void rejectsUnrelatedJavaClassesAndWrongPayloadTypes() throws Exception {
    assertThrows(SQLException.class, () -> SnapshotCodec.decode(legacy(null), Engine.State.class));
    assertEquals(
        "STATE_DECODING",
        assertThrows(
                SQLException.class, () -> SnapshotCodec.decode(legacy(new Date()), Object.class))
            .getMessage());
    assertThrows(
        SQLException.class, () -> SnapshotCodec.decode(legacy("wrong"), Engine.State.class));
    assertThrows(
        SQLException.class, () -> SnapshotCodec.decode(new byte[] {1, 2, 3}, Engine.State.class));
  }

  @Test
  void limitsAreInclusiveAndApplyBeforeArrayAllocation() {
    record Info(
        long depth, long references, long streamBytes, long arrayLength, Class<?> serialClass)
        implements ObjectInputFilter.FilterInfo {}
    assertEquals(
        ObjectInputFilter.Status.UNDECIDED,
        SnapshotCodec.filter(
            new Info(40, 10_000_000, SnapshotCodec.MAX_BYTES, SnapshotCodec.MAX_ARRAY, null)));
    assertEquals(
        ObjectInputFilter.Status.ALLOWED, SnapshotCodec.filter(new Info(1, 1, 1, 2, long[].class)));
    assertEquals(
        ObjectInputFilter.Status.ALLOWED,
        SnapshotCodec.filter(new Info(1, 1, 1, 2, Map.Entry[].class)));
    for (Info info :
        List.of(
            new Info(41, 0, 0, 0, null),
            new Info(0, 10_000_001, 0, 0, null),
            new Info(0, 0, SnapshotCodec.MAX_BYTES + 1, 0, null),
            new Info(0, 0, 0, SnapshotCodec.MAX_ARRAY + 1, null),
            new Info(0, 0, 0, 0, Date.class)))
      assertEquals(ObjectInputFilter.Status.REJECTED, SnapshotCodec.filter(info));
  }

  @Test
  void byteBudgetCountsSingleBulkAndSkippedBytesAndAllowsExactEof() throws Exception {
    try (var bytes =
        new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3)) {
      assertEquals(1, bytes.read());
      var buffer = new byte[2];
      assertEquals(2, bytes.read(buffer));
      assertArrayEquals(new byte[] {2, 3}, buffer);
      assertEquals(-1, bytes.read());
    }
    try (var bytes = new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[] {1, 2}), 1)) {
      assertEquals(1, bytes.read());
      assertThrows(IOException.class, bytes::read);
    }
    try (var bytes =
        new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2)) {
      assertThrows(IOException.class, () -> bytes.read(new byte[3]));
    }
    try (var bytes =
        new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2)) {
      assertThrows(IOException.class, () -> bytes.skipNBytes(3));
    }
  }

  @Test
  void byteBudgetAccountsForZeroBytesAndSuccessfulMultiChunkSkips() throws Exception {
    try (var bytes = new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[2]), 1)) {
      assertEquals(0, bytes.read());
      assertThrows(IOException.class, bytes::read);
    }
    try (var bytes = new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[1]), 1)) {
      assertEquals(1, bytes.read(new byte[1]));
      assertEquals(-1, bytes.read(new byte[1]));
    }
    try (var bytes =
        new SnapshotCodec.ByteBudget(new ByteArrayInputStream(new byte[10000]), 10000)) {
      assertEquals(0, bytes.skip(0));
      assertEquals(0, bytes.skip(-1));
      assertEquals(9000, bytes.skip(9000));
      assertEquals(1000, bytes.skip(9000));
      assertEquals(0, bytes.skip(1));
      assertEquals(-1, bytes.read());
    }
  }

  @Test
  void encodingFailureRetainsTheIoCause() {
    class Broken implements Serializable {
      private void writeObject(ObjectOutputStream out) throws IOException {
        throw new IOException("broken");
      }
    }
    var failure = assertThrows(SQLException.class, () -> SnapshotCodec.encode(new Broken()));
    assertEquals("STATE_ENCODING", failure.getMessage());
    assertEquals("broken", failure.getCause().getMessage());
  }

  private static byte[] legacy(Serializable value) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes);
        var out = new ObjectOutputStream(gzip)) {
      out.writeObject(value);
    }
    return bytes.toByteArray();
  }
}
