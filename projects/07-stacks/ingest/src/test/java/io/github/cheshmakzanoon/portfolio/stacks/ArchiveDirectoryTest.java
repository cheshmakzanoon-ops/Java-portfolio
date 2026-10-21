package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveDirectoryTest {
  @TempDir Path root;

  private Path archive(String name, String content) throws IOException {
    Path file = root.resolve("input.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return file;
  }

  private SnapshotManifest read(Path file) throws IOException {
    return new SafeSourceReader(
            root,
            SafeSourceReader.Limits.defaults(),
            new ContentAddressedStore(root.resolve("objects")))
        .archive("tenant", "repo", "r1", file.getFileName(), () -> false);
  }

  private static int signature(byte[] bytes, int signature) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i <= bytes.length - 4; i++) if (buffer.getInt(i) == signature) return i;
    throw new AssertionError("ZIP fixture lacks signature");
  }

  @Test
  void directoryEntriesCannotHideExpandedPayloadsFromAcquisitionBudgets() throws Exception {
    Path file = archive("folder/", "x".repeat(100_000));
    assertThrows(IOException.class, () -> read(file));
    assertTrue(read(archive("folder/", "")).entries().isEmpty());
    assertEquals(1, read(archive("folder/Source.java", "class Source {}")).entries().size());
  }

  @Test
  void centralAndLocalNamesAndChecksumsMustDescribeTheSameStreamedMember() throws Exception {
    Path file = archive("First.java", "class First {}");
    byte[] valid = Files.readAllBytes(file);
    int central = signature(valid, 0x02014b50);
    for (int changed : List.of(central + 46, central + 16, central + 20, central + 24)) {
      byte[] forged = valid.clone();
      forged[changed] ^= 1;
      Files.write(file, forged);
      assertThrows(IOException.class, () -> read(file), "changed ZIP metadata must be rejected");
    }
    Files.write(file, valid);
    assertEquals("First.java", read(file).entries().getFirst().path());
  }

  @Test
  void inconsistentLocalHeadersAndEncryptedOrSpannedDirectoriesAreRejected() throws Exception {
    Path file = archive("First.java", "class First {}");
    byte[] valid = Files.readAllBytes(file);
    int central = signature(valid, 0x02014b50), end = signature(valid, 0x06054b50);
    for (int changed : List.of(6, 8, 26, central + 8, central + 34, end + 4, end + 6, end + 8)) {
      byte[] forged = valid.clone();
      forged[changed] ^= 1;
      Files.write(file, forged);
      assertThrows(IOException.class, () -> read(file));
    }
  }

  @Test
  void centralOffsetsAndContainerBoundsCannotBypassTheOrdinaryZipLimit() throws Exception {
    Path file = archive("First.java", "class First {}");
    byte[] valid = Files.readAllBytes(file);
    int central = signature(valid, 0x02014b50), end = signature(valid, 0x06054b50);
    for (int offset : List.of(central + 42, central + 20, central + 24, end + 12, end + 16)) {
      byte[] forged = valid.clone();
      ByteBuffer.wrap(forged).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, -1);
      Files.write(file, forged);
      assertThrows(IOException.class, () -> read(file));
    }
    for (int offset : List.of(end + 10, end + 8)) {
      byte[] forged = valid.clone();
      ByteBuffer.wrap(forged).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) -1);
      Files.write(file, forged);
      assertThrows(IOException.class, () -> read(file));
    }
    Files.write(file, valid);
    assertThrows(IOException.class, () -> ArchiveDirectory.read(file, 0, () -> false));
  }
}
