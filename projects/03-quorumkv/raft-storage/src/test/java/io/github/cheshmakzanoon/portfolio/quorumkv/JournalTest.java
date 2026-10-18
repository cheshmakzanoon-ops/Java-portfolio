package io.github.cheshmakzanoon.portfolio.quorumkv;

import static io.github.cheshmakzanoon.portfolio.quorumkv.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
  @TempDir Path temp;

  private Durable written() {
    return new Durable(
        2,
        1,
        1,
        Snapshot.empty(),
        List.of(
            new Entry(
                1, 1, new Command("c", 1, Kind.PUT, new Key(new byte[] {1}), new byte[] {2}, null)),
            new Entry(2, 2, null)));
  }

  @Test
  void restartRestoresOnlyPersistedCommitAndDirectoryLock() throws Exception {
    try (Journal j = new Journal(temp)) {
      j.persist(written());
      assertThrows(IOException.class, () -> new Journal(temp));
    }
    try (Journal j = new Journal(temp)) {
      assertEquals(1, j.recovered().commit());
      Raft r = new Raft(0, 1, j.recovered());
      assertEquals(1, r.applied());
      assertArrayEquals(new byte[] {2}, r.view().data().get(new Key(new byte[] {1})));
    }
  }

  @Test
  void suffixReplacementIsOneCrashSafeTransaction() throws Exception {
    Durable replace =
        new Durable(
            3, 2, 1, Snapshot.empty(), List.of(written().log().getFirst(), new Entry(2, 3, null)));
    try (Journal j = new Journal(temp)) {
      j.persist(written());
      j.persist(replace);
    }
    try (Journal j = new Journal(temp)) {
      assertEquals(3, j.recovered().log().get(1).term());
      assertEquals(2, j.recovered().vote());
    }
  }

  @Test
  void incompleteFinalRecordDiscardedButCompleteCorruptionFailsClosed() throws Exception {
    try (Journal j = new Journal(temp)) {
      j.persist(written());
    }
    Path wal = temp.resolve("wal-0");
    long size = Files.size(wal);
    byte[] bytes = Files.readAllBytes(wal);
    Files.write(wal, Arrays.copyOf(bytes, bytes.length - 3));
    try (Journal j = new Journal(temp)) {
      assertEquals(0, j.recovered().commit());
    }
    assertTrue(Files.size(wal) < size);
    Files.write(wal, bytes);
    bytes[bytes.length - 1] ^= 1;
    Files.write(wal, bytes);
    IOException e = assertThrows(IOException.class, () -> new Journal(temp));
    assertTrue(e.getMessage().contains("CHECKSUM"));
  }

  @Test
  void interiorCorruptionNeverSkipped() throws Exception {
    try (Journal j = new Journal(temp)) {
      j.persist(written());
    }
    byte[] bytes = Files.readAllBytes(temp.resolve("wal-0"));
    bytes[40] ^= 1;
    Files.write(temp.resolve("wal-0"), bytes);
    assertThrows(IOException.class, () -> new Journal(temp));
  }

  @Test
  void everySnapshotPublicationCrashBoundaryKeepsAcknowledgedState() throws Exception {
    for (Journal.Boundary boundary : Journal.Boundary.values()) {
      Path dir = temp.resolve(boundary.name());
      try (Journal j = new Journal(dir)) {
        j.persist(written());
      }
      Registers registers = new Registers(Snapshot.empty());
      registers.apply(written().log().getFirst().command());
      Durable compact =
          new Durable(2, 1, 1, registers.snapshot(1, 1), List.of(new Entry(2, 2, null)));
      try (Journal j =
          new Journal(
              dir,
              b -> {
                if (b == boundary) throw new IOException("injected " + b);
              })) {
        if (boundary == Journal.Boundary.WAL_WRITE || boundary == Journal.Boundary.WAL_FLUSH)
          j.persist(compact);
        else assertThrows(IOException.class, () -> j.persist(compact));
      }
      try (Journal recovered = new Journal(dir)) {
        assertEquals(1, recovered.recovered().commit(), boundary.name());
        assertArrayEquals(
            new byte[] {2},
            new Raft(0, 1, recovered.recovered()).view().data().get(new Key(new byte[] {1})),
            boundary.name());
      }
    }
  }

  @Test
  void snapshotChecksumAndIncompatibleManifestFailClosed() throws Exception {
    try (Journal ignored = new Journal(temp)) {}
    Files.writeString(temp.resolve("MANIFEST"), "QKV9 0 0\n");
    assertThrows(IOException.class, () -> new Journal(temp));
    Files.writeString(temp.resolve("MANIFEST"), "QKV1 0 0\n");
    byte[] snapshot = Files.readAllBytes(temp.resolve("snapshot-0"));
    snapshot[4] ^= 1;
    Files.write(temp.resolve("snapshot-0"), snapshot);
    assertThrows(IOException.class, () -> new Journal(temp));
  }

  @Test
  void ordinaryWriteAndFlushFaultsNeverLosePriorAcknowledgedPrefix() throws Exception {
    for (var boundary : List.of(Journal.Boundary.WAL_WRITE, Journal.Boundary.WAL_FLUSH)) {
      Path dir = temp.resolve("ordinary-" + boundary);
      try (Journal journal = new Journal(dir)) {
        journal.persist(written());
      }
      Durable replacement =
          new Durable(
              3,
              2,
              1,
              Snapshot.empty(),
              List.of(written().log().getFirst(), new Entry(2, 3, null)));
      try (Journal journal =
          new Journal(
              dir,
              b -> {
                if (b == boundary) throw new IOException("crash " + b);
              })) {
        assertThrows(IOException.class, () -> journal.persist(replacement));
      }
      try (Journal recovered = new Journal(dir)) {
        assertEquals(1, recovered.recovered().commit());
        assertArrayEquals(
            Codec.command(written().log().getFirst().command()),
            Codec.command(recovered.recovered().log().getFirst().command()));
        assertEquals(3, recovered.recovered().term());
      }
    }
  }

  @Test
  void everyPartialSuffixTransactionTailRecoversPreviousCompleteGeneration() throws Exception {
    Path base = temp.resolve("base");
    long oldSize;
    byte[] complete;
    try (Journal journal = new Journal(base)) {
      journal.persist(written());
      oldSize = Files.size(base.resolve("wal-0"));
      journal.persist(
          new Durable(
              3,
              2,
              1,
              Snapshot.empty(),
              List.of(written().log().getFirst(), new Entry(2, 3, null))));
      complete = Files.readAllBytes(base.resolve("wal-0"));
    }
    for (int n = (int) oldSize; n < complete.length; n++) {
      Path dir = temp.resolve("tail-" + n);
      Files.createDirectories(dir);
      Files.copy(base.resolve("MANIFEST"), dir.resolve("MANIFEST"));
      Files.copy(base.resolve("snapshot-0"), dir.resolve("snapshot-0"));
      Files.write(dir.resolve("wal-0"), Arrays.copyOf(complete, n));
      try (Journal journal = new Journal(dir)) {
        assertEquals(2, journal.recovered().term(), "tail byte " + n);
        assertEquals(1, journal.recovered().commit());
        assertEquals(2, journal.recovered().log().get(1).term());
      }
    }
  }
}
