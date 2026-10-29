package io.github.cheshmakzanoon.portfolio.thepit;

import static io.github.cheshmakzanoon.portfolio.thepit.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalReaderTest {
  @TempDir Path dir;

  private static Command init(String id) {
    return new Command(id, "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 100);
  }

  private static Command order(String id) {
    return new Command(id, "alice", Type.NEW, id, Side.BUY, 10, 2, Tif.GTC, false, 0, 0);
  }

  private Path journal(String name, String run, Command... commands) throws IOException {
    Path path = dir.resolve(name);
    try (Journal journal = new Journal(path, run)) {
      long sequence = 0;
      for (Command command : commands) journal.appendAndForce(++sequence, command);
    }
    return path;
  }

  @Test
  void inspectionMatchesWriterRecoveryAndNeverModifiesTheFile() throws Exception {
    Path path = journal("ok", "run-7", init("a"), order("b"), order("c"));
    byte[] before = Files.readAllBytes(path);
    JournalReader.Scan scan = JournalReader.inspect(path);
    assertEquals("run-7", scan.runId());
    assertEquals(3, scan.entries().size());
    assertEquals(before.length, scan.validBytes());
    assertEquals(before.length, scan.fileBytes());
    assertEquals(0, scan.incompleteTailBytes());
    try (Journal writer = new Journal(path, "run-7")) {
      assertEquals(writer.entries(), scan.entries());
      // A live writer holds the exclusive lock; read-only inspection is still permitted.
      assertEquals(scan, JournalReader.inspect(path));
      assertThrows(
          UnsupportedOperationException.class,
          () -> scan.entries().add(new Journal.Entry(4, init("d"))));
    }
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void emptyAndMissingJournals() throws Exception {
    Path empty = dir.resolve("empty");
    Files.write(empty, new byte[0]);
    JournalReader.Scan scan = JournalReader.inspect(empty);
    assertNull(scan.runId());
    assertTrue(scan.entries().isEmpty());
    assertEquals(0, scan.fileBytes());
    assertThrows(IOException.class, () -> JournalReader.inspect(dir.resolve("absent")));
  }

  @Test
  void everyTornByteIsReportedAsATailWithoutBeingTruncated() throws Exception {
    Path two = journal("two", "run", init("a"), order("b"));
    byte[] bytes = Files.readAllBytes(two);
    int firstFrame = Journal.HEADER + ByteBuffer.wrap(bytes).getInt(16) + 4;
    for (int cut = 0; cut < bytes.length; cut++) {
      Path torn = dir.resolve("torn-" + cut);
      Files.write(torn, Arrays.copyOf(bytes, cut));
      JournalReader.Scan scan = JournalReader.inspect(torn);
      int complete = cut >= firstFrame ? 1 : 0;
      long valid = complete == 1 ? firstFrame : 0;
      assertEquals(complete, scan.entries().size(), "cut " + cut);
      assertEquals(valid, scan.validBytes(), "cut " + cut);
      assertEquals(cut - valid, scan.incompleteTailBytes(), "cut " + cut);
      assertEquals(cut, Files.size(torn), "inspection must not truncate");
      // Writer recovery makes the same decision, but it does truncate.
      try (Journal writer = new Journal(torn, "run")) {
        assertEquals(scan.entries(), writer.entries());
      }
      assertEquals(valid, Files.size(torn));
    }
  }

  @Test
  void completeCorruptionFailsWithTheWritersDiagnostics() throws Exception {
    Path source = journal("source", "run", init("a"));
    byte[] frame = Files.readAllBytes(source);
    for (int offset : new int[] {0, 4, 8, 16, 20, 24, frame.length - 1}) {
      byte[] corrupt = frame.clone();
      corrupt[offset] ^= 1;
      Path path = dir.resolve("bad-" + offset);
      Files.write(path, corrupt);
      IOException reader = assertThrows(IOException.class, () -> JournalReader.inspect(path));
      IOException writer = assertThrows(IOException.class, () -> new Journal(path, "run"));
      assertEquals(writer.getMessage(), reader.getMessage(), "offset " + offset);
      assertArrayEquals(corrupt, Files.readAllBytes(path), "corruption must be preserved");
    }
  }

  @Test
  void aChecksummedFrameWithAnInvalidCommandIsRejectedNotTruncated() throws Exception {
    Path source = journal("valid", "run", init("a"));
    byte[] frame = Files.readAllBytes(source);
    int length = ByteBuffer.wrap(frame).getInt(16);
    String payload = new String(frame, Journal.HEADER, length, StandardCharsets.UTF_8);
    // Well-formed JSON of the right types, but a negative opening balance breaks the invariant.
    byte[] body =
        payload
            .replace("\"initialCash\":1000", "\"initialCash\":-1")
            .getBytes(StandardCharsets.UTF_8);
    assertNotEquals(payload, new String(body, StandardCharsets.UTF_8));
    ByteBuffer forged = ByteBuffer.allocate(Journal.HEADER + body.length + 4);
    forged.put(frame, 0, 16).putInt(body.length);
    forged.putInt(checksum(forged.array(), 20));
    forged.put(body).putInt(checksum(body, body.length));
    Path path = dir.resolve("invalid-command");
    Files.write(path, forged.array());
    assertEquals(
        "invalid durable command",
        assertThrows(IOException.class, () -> JournalReader.inspect(path)).getMessage());
    assertEquals(
        "invalid durable command",
        assertThrows(IOException.class, () -> new Journal(path, "run")).getMessage());
    assertArrayEquals(forged.array(), Files.readAllBytes(path));
  }

  private static int checksum(byte[] bytes, int length) {
    java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
    crc.update(bytes, 0, length);
    return (int) crc.getValue();
  }

  @Test
  void aSequenceGapOrMixedRunIsRejected() throws Exception {
    byte[] one = Files.readAllBytes(journal("one", "alpha", init("a"), order("b")));
    byte[] other = Files.readAllBytes(journal("other", "beta", init("a"), order("b")));
    int firstOne = Journal.HEADER + ByteBuffer.wrap(one).getInt(16) + 4;
    int firstOther = Journal.HEADER + ByteBuffer.wrap(other).getInt(16) + 4;

    byte[] mixed = new byte[firstOne + (other.length - firstOther)];
    System.arraycopy(one, 0, mixed, 0, firstOne);
    System.arraycopy(other, firstOther, mixed, firstOne, other.length - firstOther);
    Path mixedPath = dir.resolve("mixed");
    Files.write(mixedPath, mixed);
    assertEquals(
        "invalid payload run ID",
        assertThrows(IOException.class, () -> JournalReader.inspect(mixedPath)).getMessage());

    // Repeating the first frame reuses sequence 1 where 2 is required.
    byte[] repeated = new byte[firstOne * 2];
    System.arraycopy(one, 0, repeated, 0, firstOne);
    System.arraycopy(one, 0, repeated, firstOne, firstOne);
    Path repeatedPath = dir.resolve("repeated");
    Files.write(repeatedPath, repeated);
    assertEquals(
        "journal sequence gap",
        assertThrows(IOException.class, () -> JournalReader.inspect(repeatedPath)).getMessage());
  }
}
