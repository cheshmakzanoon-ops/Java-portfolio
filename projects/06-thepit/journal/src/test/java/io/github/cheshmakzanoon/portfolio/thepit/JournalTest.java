package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
  @TempDir Path dir;
  private final Command command =
      new Command("init", "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 100);

  @Test
  void everyTornByteAndCompleteCorruption() throws Exception {
    Path source = dir.resolve("source");
    try (Journal j = new Journal(source, "run")) {
      j.appendAndForce(1, command);
    }
    byte[] frame = Files.readAllBytes(source);
    for (int cut = 0; cut < frame.length; cut++) {
      Path p = dir.resolve("cut-" + cut);
      Files.write(p, java.util.Arrays.copyOf(frame, cut));
      try (Journal j = new Journal(p, "run")) {
        assertTrue(j.entries().isEmpty(), "cut " + cut);
        assertEquals(0, Files.size(p));
      }
    }
    for (int offset : new int[] {0, 4, 8, 16, 20, 24, frame.length - 1}) {
      byte[] corrupt = frame.clone();
      corrupt[offset] ^= 1;
      Path p = dir.resolve("bad-" + offset);
      Files.write(p, corrupt);
      assertThrows(IOException.class, () -> new Journal(p, "run"), "offset " + offset);
    }
  }

  @Test
  void invalidCommandsAreRejectedBeforeWritingAndDoNotPoisonTheJournal() throws Exception {
    Path path = dir.resolve("invalid-append");
    Command invalid =
        new Command("init", "alice", Type.INIT, null, null, 0, 0, null, false, -1, 100);
    try (Journal journal = new Journal(path, "run")) {
      assertThrows(IllegalArgumentException.class, () -> journal.appendAndForce(1, invalid));
      assertEquals(0, Files.size(path));
      assertTrue(journal.entries().isEmpty());
      journal.appendAndForce(1, command);
    }
    try (Journal recovered = new Journal(path, "run")) {
      assertEquals(command, recovered.entries().getFirst().command());
    }
  }

  @Test
  void checksumValidFramesStillRejectInvalidCommandTypes() throws Exception {
    Path source = dir.resolve("typed-command");
    Command order =
        new Command("new", "alice", Type.NEW, "order", Side.BUY, 10, 2, Tif.GTC, false, 0, 0);
    try (Journal journal = new Journal(source, "run")) {
      journal.appendAndForce(1, order);
    }
    byte[] frame = Files.readAllBytes(source);
    int length = ByteBuffer.wrap(frame).getInt(16);
    String payload = new String(frame, Journal.HEADER, length, StandardCharsets.UTF_8);
    int index = 0;
    for (String invalid :
        java.util.List.of(
            "null",
            payload.replace("\"NEW\"", "1"),
            payload.replace("\"BUY\"", "0"),
            payload.replace("\"GTC\"", "0"),
            payload.replace("\"clientCommandId\":\"new\"", "\"clientCommandId\":123"),
            payload.replace("\"accountId\":\"alice\"", "\"accountId\":true"),
            payload.replace("\"orderId\":\"order\"", "\"orderId\":12.5"),
            payload.replace("\"priceTicks\":10", "\"priceTicks\":\"10\""),
            payload.replace("\"postOnly\":false", "\"postOnly\":\"false\""),
            payload.replace("\"quantityLots\":2", "\"quantityLots\":null"))) {
      byte[] body = invalid.getBytes(StandardCharsets.UTF_8);
      ByteBuffer forged = ByteBuffer.allocate(Journal.HEADER + body.length + 4);
      forged.put(frame, 0, 16).putInt(body.length);
      forged.putInt(checksum(forged.array(), 20));
      forged.put(body).putInt(checksum(body, body.length));
      Path path = dir.resolve("numeric-enum-" + index++);
      Files.write(path, forged.array());
      assertThrows(IOException.class, () -> new Journal(path, "run"));
      assertArrayEquals(
          forged.array(), Files.readAllBytes(path), "corruption must not be truncated");
    }
  }

  private static int checksum(byte[] bytes, int length) {
    CRC32C checksum = new CRC32C();
    checksum.update(bytes, 0, length);
    return (int) checksum.getValue();
  }

  @Test
  void everyAdmittedStorageFaultPoisonsFurtherWritesUntilRecovery() throws Exception {
    for (String boundary :
        java.util.List.of("BEFORE_APPEND", "DURING_FRAME", "BEFORE_FORCE", "AFTER_FORCE")) {
      Path path = dir.resolve("poison-" + boundary);
      IOException injected = new IOException("injected " + boundary);
      try (Journal journal =
          new Journal(
              path,
              "run",
              current -> {
                if (current.equals(boundary)) throw injected;
              })) {
        assertSame(
            injected, assertThrows(IOException.class, () -> journal.appendAndForce(1, command)));
        byte[] failedBytes = Files.readAllBytes(path);
        IOException refused =
            assertThrows(IOException.class, () -> journal.appendAndForce(1, command));
        assertSame(injected, refused.getCause());
        assertThrows(IOException.class, journal::snapshot);
        assertArrayEquals(failedBytes, Files.readAllBytes(path));
      }
      try (Journal recovered = new Journal(path, "run")) {
        int expected = boundary.equals("BEFORE_APPEND") || boundary.equals("DURING_FRAME") ? 0 : 1;
        assertEquals(expected, recovered.entries().size());
        recovered.appendAndForce(
            expected + 1,
            new Command("next", "alice", Type.INIT, null, null, 0, 0, null, false, 0, 0));
      }
    }
  }

  @Test
  void closeFailureCannotHideRecoveryFailureOrRetainTheWriterLock() throws Exception {
    Path path = dir.resolve("failed-recovery");
    Files.write(path, new byte[Journal.HEADER]);
    IOException cleanup = new IOException("close fault");
    IOException recovery =
        assertThrows(
            IOException.class,
            () ->
                new Journal(
                    path,
                    "run",
                    boundary -> {
                      if (boundary.equals("BEFORE_CLOSE")) throw cleanup;
                    }));
    assertTrue(recovery.getMessage().contains("corrupt frame header"));
    assertArrayEquals(new Throwable[] {cleanup}, recovery.getSuppressed());
    Files.delete(path);
    try (Journal reopened = new Journal(path, "run")) {
      reopened.appendAndForce(1, command);
    }
    Journal closed =
        new Journal(
            path,
            "run",
            boundary -> {
              if (boundary.equals("BEFORE_CLOSE")) throw cleanup;
            });
    assertSame(cleanup, assertThrows(IOException.class, closed::close));
    closed.close();
    assertThrows(IOException.class, () -> closed.appendAndForce(2, command));
    try (Journal reopened = new Journal(path, "run")) {
      assertEquals(1, reopened.entries().size());
    }
  }

  @Test
  void malformedInputAndSequencePreconditionsDoNotPoisonAnOpenJournal() throws Exception {
    Path path = dir.resolve("preconditions");
    try (Journal journal = new Journal(path, "run")) {
      assertThrows(IllegalArgumentException.class, () -> journal.appendAndForce(2, command));
      Command oversized =
          new Command(
              "large",
              "alice",
              Type.INIT,
              "x".repeat(Journal.MAX_PAYLOAD),
              null,
              0,
              0,
              null,
              false,
              0,
              0);
      assertThrows(IOException.class, () -> journal.appendAndForce(1, oversized));
      assertEquals(0, Files.size(path));
      journal.appendAndForce(1, command);
    }
  }

  @Test
  void simultaneousDirectAppendsCannotInterleaveFrames() throws Exception {
    Path path = dir.resolve("concurrent");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (Journal journal =
            new Journal(
                path,
                "run",
                boundary -> {
                  if (boundary.equals("BEFORE_APPEND")) {
                    entered.countDown();
                    try {
                      if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IOException("test deadline");
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      throw new IOException(interrupted);
                    }
                  }
                });
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                journal.appendAndForce(1, command);
                return true;
              });
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var second =
          executor.submit(
              () -> {
                journal.appendAndForce(1, command);
                return true;
              });
      release.countDown();
      assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> second.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertInstanceOf(IllegalArgumentException.class, failure.getCause());
      assertEquals(1, journal.entries().size());
    } finally {
      release.countDown();
    }
    try (Journal recovered = new Journal(path, "run")) {
      assertEquals(1, recovered.entries().size());
    }
  }

  @Test
  void checkpointsBindResultsAndBalancesToTheJournalWithoutReplayingMatching() throws Exception {
    var json = CommandJson.create();
    for (Side takerSide : Side.values()) {
      Path path = dir.resolve("ledger-" + takerSide);
      Book book = new Book();
      java.util.List<Command> commands =
          java.util.List.of(
              new Command("init", "buyer", Type.INIT, null, null, 0, 0, null, false, 1000, 0),
              new Command("init", "seller", Type.INIT, null, null, 0, 0, null, false, 0, 10),
              new Command(
                  "maker",
                  takerSide == Side.BUY ? "seller" : "buyer",
                  Type.NEW,
                  "m",
                  takerSide == Side.BUY ? Side.SELL : Side.BUY,
                  10,
                  5,
                  Tif.GTC,
                  false,
                  0,
                  0),
              new Command(
                  "taker",
                  takerSide == Side.BUY ? "buyer" : "seller",
                  Type.NEW,
                  "t",
                  takerSide,
                  10,
                  2,
                  Tif.GTC,
                  false,
                  0,
                  0));
      try (Journal journal = new Journal(path, "run")) {
        for (int index = 0; index < commands.size(); index++) {
          journal.appendAndForce(index + 1, commands.get(index));
          book.apply(index + 1, commands.get(index));
        }
        journal.snapshot(book.checkpoint());
        Book different = new Book();
        different.apply(1, command);
        assertThrows(
            IllegalArgumentException.class, () -> journal.snapshot(different.checkpoint()));
      }
      try (Journal recovered = new Journal(path, "run")) {
        assertTrue(recovered.validSnapshot());
        assertEquals(book.checkpoint(), recovered.recoveredCheckpoint());
      }
      Path snapshotPath = path.resolveSibling(path.getFileName() + ".snapshot");
      Journal.SnapshotEnvelope envelope =
          json.readValue(Files.readAllBytes(snapshotPath), Journal.SnapshotEnvelope.class);
      Journal.Snapshot snapshot = json.readValue(envelope.payload(), Journal.Snapshot.class);
      for (String invalid :
          java.util.List.of(
              envelope.payload().replace("\"cash\":980", "\"cash\":1980"),
              envelope.payload().replace("\"lots\":2", "\"lots\":3"),
              envelope
                  .payload()
                  .replace(
                      "\"commandHash\":\"" + book.view().results().get("buyer:init").commandHash(),
                      "\"commandHash\":\"" + "0".repeat(64)),
              envelope.payload().replace("\"INITIALIZED\"", "\"ACCOUNT_EXISTS\""))) {
        assertNotEquals(envelope.payload(), invalid, "fixture must change checkpoint bytes");
        Files.write(
            snapshotPath,
            json.writeValueAsBytes(new Journal.SnapshotEnvelope(invalid, sha(invalid))));
        try (Journal recovered = new Journal(path, "run")) {
          assertFalse(recovered.validSnapshot());
          assertNull(recovered.recoveredCheckpoint());
          assertEquals(4, recovered.entries().size());
        }
      }
      assertEquals(4, snapshot.lastSequence());
    }
  }

  @Test
  void independentGoldenFramesAndObservedForcesCoverDurabilityAndPartialIo() throws Exception {
    Path path = dir.resolve("golden");
    var observed = new java.util.HashMap<Path, ObservedChannel>();
    Journal.Channels channels =
        (file, options) -> {
          var channel = new ObservedChannel(java.nio.channels.FileChannel.open(file, options));
          channel.chunk = 3;
          observed.put(file.toAbsolutePath(), channel);
          return channel;
        };
    try (Journal journal = new Journal(path, "run", ignored -> {}, channels)) {
      journal.appendAndForce(1, command);
      assertEquals(
          1, observed.get(path.toAbsolutePath()).forces, "complete journal frame must be forced");
      byte[] bytes = Files.readAllBytes(path);
      assertEquals(
          checksum(bytes, 20), ByteBuffer.wrap(bytes).getInt(20), "independent header CRC");
      int length = ByteBuffer.wrap(bytes).getInt(16);
      byte[] payload = java.util.Arrays.copyOfRange(bytes, Journal.HEADER, Journal.HEADER + length);
      assertEquals(
          checksum(payload, length),
          ByteBuffer.wrap(bytes).getInt(Journal.HEADER + length),
          "independent payload CRC");
      Book book = new Book();
      book.apply(1, command);
      journal.snapshot(book.checkpoint());
      assertEquals(1, observed.get(dir.resolve("golden.snapshot.tmp").toAbsolutePath()).forces);
      assertEquals(1, observed.get(dir.toAbsolutePath()).forces);
      var json = CommandJson.create();
      var envelope =
          json.readValue(
              Files.readAllBytes(dir.resolve("golden.snapshot")), Journal.SnapshotEnvelope.class);
      var snapshot = json.readValue(envelope.payload(), Journal.Snapshot.class);
      assertEquals(sha("run|1:" + command.hash()), snapshot.prefixDigest());
      View view = book.view();
      Checkpoint invalidHolds =
          new Checkpoint(
              new View(
                  1,
                  java.util.Map.of("alice", new Balance(1000, 100, 1, 0)),
                  view.orders(),
                  view.executions(),
                  view.results()),
              java.util.Set.of());
      assertThrows(IllegalArgumentException.class, () -> journal.snapshot(invalidHolds));
    }
    assertEquals(1, observed.get(path.toAbsolutePath()).releases);
    assertEquals(1, observed.get(path.toAbsolutePath()).closes);
    try (Journal recovered = new Journal(path, "run", ignored -> {}, channels)) {
      assertEquals(
          command, recovered.entries().getFirst().command(), "partial reads preserve framing");
    }
    byte[] body =
        "{\"runId\":\"run\",\"command\":{\"clientCommandId\":\"init\",\"accountId\":\"alice\",\"type\":\"INIT\",\"priceTicks\":0,\"quantityLots\":0,\"postOnly\":false,\"initialCash\":1000,\"initialLots\":100}}"
            .getBytes(StandardCharsets.UTF_8);
    ByteBuffer external = ByteBuffer.allocate(Journal.HEADER + body.length + 4);
    external.putInt(0x4d434f52).putInt(1).putLong(1).putInt(body.length);
    external.putInt(checksum(external.array(), 20)).put(body).putInt(checksum(body, body.length));
    Path independent = dir.resolve("external-frame");
    Files.write(independent, external.array());
    try (Journal recovered = new Journal(independent, "run")) {
      assertEquals(command, recovered.entries().getFirst().command());
    }
    Files.write(path, new byte[] {1}, StandardOpenOption.APPEND);
    try (Journal recovered = new Journal(path, "run", ignored -> {}, channels)) {
      assertEquals(1, recovered.entries().size());
      assertEquals(
          1, observed.get(path.toAbsolutePath()).forces, "torn-tail repair must be forced");
    }
  }

  @Test
  void channelAcquisitionForceAndCleanupFaultsPreserveOwnershipAndPrimaryErrors() throws Exception {
    Path path = dir.resolve("io-faults");
    for (boolean noLock : new boolean[] {false, true}) {
      var channel =
          new ObservedChannel(
              java.nio.channels.FileChannel.open(
                  path,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE));
      channel.noLock = noLock;
      channel.lockFailure = noLock ? null : new IOException("tryLock fault");
      assertThrows(
          IOException.class,
          () -> new Journal(path, "run", ignored -> {}, (file, options) -> channel));
      assertEquals(1, channel.closes);
    }
    var channel =
        new ObservedChannel(
            java.nio.channels.FileChannel.open(
                path, StandardOpenOption.READ, StandardOpenOption.WRITE));
    IOException force = new IOException("force acknowledgement lost");
    channel.forceFailure = force;
    try (Journal journal = new Journal(path, "run", ignored -> {}, (file, options) -> channel)) {
      assertSame(force, assertThrows(IOException.class, () -> journal.appendAndForce(1, command)));
      assertSame(
          force,
          assertThrows(IOException.class, () -> journal.appendAndForce(1, command)).getCause());
    }
    var closing =
        new ObservedChannel(
            java.nio.channels.FileChannel.open(
                path, StandardOpenOption.READ, StandardOpenOption.WRITE));
    closing.releaseFailure = new IOException("release acknowledgement lost");
    closing.closeFailure = new IOException("close acknowledgement lost");
    Journal journal = new Journal(path, "run", ignored -> {}, (file, options) -> closing);
    IOException failure = assertThrows(IOException.class, journal::close);
    assertSame(closing.releaseFailure, failure);
    assertArrayEquals(new Throwable[] {closing.closeFailure}, failure.getSuppressed());
    try (Journal recovered = new Journal(path, "run")) {
      assertEquals(1, recovered.entries().size());
    }
  }

  @Test
  void snapshotsLockAndRunIsolation() throws Exception {
    Path p = dir.resolve("journal");
    try (Journal j = new Journal(p, "run")) {
      assertThrows(IOException.class, () -> new Journal(p, "run"));
      j.appendAndForce(1, command);
      j.snapshot();
    }
    try (Journal j = new Journal(p, "run")) {
      assertTrue(j.validSnapshot());
      assertEquals(1, j.entries().size());
    }
    Files.writeString(dir.resolve("journal.snapshot"), "corrupt");
    try (Journal j = new Journal(p, "run")) {
      assertFalse(j.validSnapshot());
      assertEquals(1, j.entries().size());
    }
    assertThrows(IOException.class, () -> new Journal(p, "another-run"));
    byte[] frame = Files.readAllBytes(p);
    frame[frame.length - 1] ^= 1;
    Files.write(p, frame);
    assertThrows(IOException.class, () -> new Journal(p, "run"));
  }
}
