package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
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
