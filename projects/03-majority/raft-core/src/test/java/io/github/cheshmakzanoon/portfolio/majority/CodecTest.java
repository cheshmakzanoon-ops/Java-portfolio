package io.github.cheshmakzanoon.portfolio.majority;

import static io.github.cheshmakzanoon.portfolio.majority.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class CodecTest {
  @Test
  void snapshotAndDurableRoundTripPreserveOrderedDataAndSessions() throws Exception {
    Registers registers = new Registers(Snapshot.empty());
    Command command =
        new Command("c", 1, Kind.PUT, new Key(new byte[] {(byte) 255}), new byte[] {1, 2}, null);
    registers.apply(command);
    Snapshot snapshot = registers.snapshot(1, 1);
    byte[] bytes = Codec.snapshot(snapshot);
    assertArrayEquals(bytes, Codec.snapshot(Codec.snapshot(bytes)));
    Durable d =
        new Durable(
            2,
            0,
            1,
            snapshot,
            List.of(
                new Entry(
                    2,
                    2,
                    new Command(
                        "c", 2, Kind.DELETE, new Key(new byte[] {(byte) 255}), null, null))));
    assertArrayEquals(Codec.durable(d), Codec.durable(Codec.durable(Codec.durable(d))));
    assertEquals(-1, new Key(new byte[] {0}).compareTo(new Key(new byte[] {1})));
    byte[] exposed = snapshot.data().firstEntry().getValue();
    exposed[0] = 9;
    assertEquals(1, snapshot.data().firstEntry().getValue()[0]);
    assertEquals(command.key(), new Key(new byte[] {(byte) 255}));
    assertEquals(command.key().hashCode(), new Key(new byte[] {(byte) 255}).hashCode());
    assertFalse(command.key().equals("wrong"));
  }

  @Test
  void malformedLengthsVersionsChecksumsAndCoordinatesFailClosed() throws Exception {
    byte[] good = Codec.snapshot(Snapshot.empty());
    byte[] broken = good.clone();
    broken[broken.length - 1] ^= 1;
    assertThrows(IOException.class, () -> Codec.snapshot(broken));
    assertThrows(IOException.class, () -> Codec.snapshot(new byte[0]));
    byte[] version = good.clone();
    version[3] = 2;
    recheck(version);
    assertThrows(IOException.class, () -> Codec.snapshot(version));
    byte[] wrongCluster = good.clone();
    wrongCluster[25] ^= 1;
    recheck(wrongCluster);
    assertThrows(IOException.class, () -> Codec.snapshot(wrongCluster));
    assertThrows(
        IOException.class,
        () ->
            Codec.bytes(
                new DataInputStream(
                    new ByteArrayInputStream(
                        ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array())),
                1024));
    assertNull(
        Codec.bytes(
            new DataInputStream(
                new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(-1).array())),
            1024));
    assertThrows(
        IllegalArgumentException.class, () -> new Durable(0, -1, 1, Snapshot.empty(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Durable(1, -1, 0, Snapshot.empty(), List.of(new Entry(2, 1, null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command("", 1, Kind.PUT, new Key(new byte[0]), new byte[0], null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command("c", 1, Kind.PUT, new Key(new byte[0]), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Command("c", 1, Kind.PUT, new Key(new byte[0]), new byte[4097], null));
    byte[] durable = Codec.durable(Durable.empty());
    durable[3] = 2;
    assertThrows(IOException.class, () -> Codec.durable(durable));
  }

  private static void recheck(byte[] bytes) {
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, bytes.length - 4);
    ByteBuffer.wrap(bytes, bytes.length - 4, 4).putInt((int) crc.getValue());
  }

  @Test
  void checksumValidSnapshotsRejectDuplicateAndInvalidRetryReceipts() throws Exception {
    byte[] digest = new byte[32];
    Result committed = new Result("OK", new byte[] {7}, -1);
    assertThrows(
        IOException.class,
        () ->
            Codec.snapshot(
                snapshotWithSessions(1, 1, List.of("client", "client"), 1, digest, committed)));
    for (String client : List.of("", " ", "x".repeat(129)))
      assertThrows(
          IOException.class,
          () -> Codec.snapshot(snapshotWithSessions(1, 1, List.of(client), 1, digest, committed)));
    for (long sequence : new long[] {0, -1})
      assertThrows(
          IOException.class,
          () ->
              Codec.snapshot(
                  snapshotWithSessions(1, 1, List.of("client"), sequence, digest, committed)));
    for (byte[] invalid : List.of(new byte[0], new byte[31]))
      assertThrows(
          IOException.class,
          () ->
              Codec.snapshot(snapshotWithSessions(1, 1, List.of("client"), 1, invalid, committed)));
    assertThrows(
        IOException.class,
        () ->
            Codec.snapshot(
                snapshotWithSessions(
                    1, 1, List.of("client"), 1, digest, new Result("NOT_LEADER", null, 2))));

    Snapshot valid =
        Codec.snapshot(snapshotWithSessions(1, 1, List.of("client"), 1, digest, committed));
    assertEquals(1, valid.sessions().size());
    assertEquals(committed, valid.sessions().get("client").result());
    assertArrayEquals(digest, valid.sessions().get("client").digest());
  }

  @Test
  void checksumValidSnapshotsAndWalRejectImpossibleCoordinates() throws Exception {
    for (long[] coordinates :
        List.of(new long[] {-1, 1}, new long[] {1, -1}, new long[] {0, 1}, new long[] {1, 0}))
      assertThrows(
          IOException.class,
          () ->
              Codec.snapshot(
                  snapshotWithSessions(
                      coordinates[0],
                      coordinates[1],
                      List.of(),
                      1,
                      new byte[32],
                      new Result("OK", null, -1))));
    Snapshot snapshot = new Snapshot(2, 3, CLUSTER, new TreeMap<>(), Map.of());
    assertThrows(IllegalArgumentException.class, () -> new Durable(2, -1, 2, snapshot, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Durable(3, 3, 2, snapshot, List.of()));
    byte[] invalidVote = Codec.durable(Durable.empty());
    ByteBuffer.wrap(invalidVote).putInt(12, -2);
    assertThrows(IOException.class, () -> Codec.durable(invalidVote));
    Snapshot lastIndex = new Snapshot(Long.MAX_VALUE, 1, CLUSTER, new TreeMap<>(), Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Durable(
                1, -1, Long.MAX_VALUE, lastIndex, List.of(new Entry(Long.MIN_VALUE, 1, null))));
  }

  private static byte[] snapshotWithSessions(
      long index, long term, List<String> clients, long sequence, byte[] digest, Result result) {
    byte[] body =
        Codec.encode(
            out -> {
              out.writeInt(1);
              out.writeLong(index);
              out.writeLong(term);
              out.writeUTF(CLUSTER);
              out.writeInt(3);
              out.writeInt(0);
              out.writeInt(1);
              out.writeInt(2);
              out.writeInt(0);
              out.writeInt(clients.size());
              for (String client : clients) {
                out.writeUTF(client);
                out.writeLong(sequence);
                Codec.bytes(out, digest);
                Codec.result(out, result);
              }
            });
    byte[] snapshot = Arrays.copyOf(body, body.length + 4);
    recheck(snapshot);
    return snapshot;
  }

  @Test
  void exactCapacityAndDeleteReleaseStorage() {
    Registers kv = new Registers(Snapshot.empty());
    for (int i = 0; i < MAX_CLIENTS; i++)
      assertEquals(
          "OK",
          kv.apply(new Command("c" + i, 1, Kind.DELETE, new Key(new byte[] {1}), null, null))
              .code());
    assertEquals(
        "RESOURCE_EXHAUSTED",
        kv.apply(new Command("overflow", 1, Kind.DELETE, new Key(new byte[] {1}), null, null))
            .code());
    Registers data = new Registers(Snapshot.empty());
    byte[] value = new byte[MAX_VALUE];
    long seq = 0;
    for (int i = 0; i < 4096; i++) {
      var r =
          data.apply(
              new Command(
                  "one",
                  ++seq,
                  Kind.PUT,
                  new Key(ByteBuffer.allocate(4).putInt(i).array()),
                  value,
                  null));
      if (!r.code().equals("OK")) {
        assertEquals("RESOURCE_EXHAUSTED", r.code());
        return;
      }
    }
    fail("retained bytes cap was not enforced");
  }
}
