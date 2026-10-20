package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
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
