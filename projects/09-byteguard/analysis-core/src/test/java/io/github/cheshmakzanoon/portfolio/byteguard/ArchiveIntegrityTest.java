package io.github.cheshmakzanoon.portfolio.byteguard;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

class ArchiveIntegrityTest {
  @TempDir Path temp;

  private Path archive(String name, Map<String, byte[]> entries) throws Exception {
    Path path = temp.resolve(name);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
    return path;
  }

  private InputManifest read(Path path) throws Exception {
    return new BoundedJarReader(InputLimits.defaults(), 25).read(List.of(path), List.of());
  }

  @Test
  void endRecordAndCentralDirectoryAreRequiredEvenWhenLocalEntriesAreReadable() throws Exception {
    Path normal = archive("normal.jar", Map.of("A.class", EngineTest.simple("A", Opcodes.V25)));
    byte[] complete = Files.readAllBytes(normal);
    for (byte[] truncated :
        List.of(new byte[] {80, 75, 5, 6}, Arrays.copyOf(complete, complete.length - 22))) {
      Path broken = temp.resolve("truncated.jar");
      Files.write(broken, truncated);
      assertThrows(InputFailure.class, () -> read(broken));
    }
    assertEquals(Set.of("A.class"), read(normal).classes().keySet());
    assertTrue(read(archive("empty.zip", Map.of())).classes().isEmpty());
  }

  @Test
  void checksumCountsAndMetadataBoundsFailClosed() throws Exception {
    Path normal = archive("normal.zip", Map.of("A.class", EngineTest.simple("A", Opcodes.V25)));
    byte[] complete = Files.readAllBytes(normal);
    ByteBuffer index = ByteBuffer.wrap(complete).order(ByteOrder.LITTLE_ENDIAN);
    int end = complete.length - 22;
    int central = index.getInt(end + 16);
    byte[] badCrc = complete.clone();
    badCrc[central + 16] ^= 1;
    Path broken = temp.resolve("bad-crc.zip");
    Files.write(broken, badCrc);
    assertTrue(
        assertThrows(InputFailure.class, () -> read(broken)).getMessage().contains("checksum"));
    byte[] badCount = complete.clone();
    ByteBuffer.wrap(badCount)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(end + 8, (short) 2)
        .putShort(end + 10, (short) 2);
    Files.write(broken, badCount);
    assertThrows(InputFailure.class, () -> read(broken));
    byte[] oversized = complete.clone();
    ByteBuffer.wrap(oversized)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(end + 12, 16 * 1024 * 1024 + 1);
    Files.write(broken, oversized);
    assertTrue(
        assertThrows(InputFailure.class, () -> read(broken)).getMessage().contains("byte limit"));
  }

  @Test
  void directoryPayloadAndUnixSymlinksCannotBypassExpandedByteAccounting() throws Exception {
    Path payload = archive("directory.zip", Map.of("ignored/", new byte[8192]));
    assertThrows(InputFailure.class, () -> read(payload));
    Path link =
        archive(
            "link.zip",
            Map.of("link", "../outside".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    byte[] bytes = Files.readAllBytes(link);
    ByteBuffer index = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int central = index.getInt(bytes.length - 6);
    index.putShort(central + 4, (short) ((3 << 8) | 20));
    index.putInt(central + 38, 0120777 << 16);
    Files.write(link, bytes);
    assertTrue(
        assertThrows(InputFailure.class, () -> read(link)).getMessage().contains("symbolic"));
  }

  @Test
  void boundedZip64IndexRemainsSupported() throws Exception {
    Path path = archive("zip64.zip", Map.of("A.class", EngineTest.simple("A", Opcodes.V25)));
    byte[] normal = Files.readAllBytes(path);
    int end = normal.length - 22;
    ByteBuffer source = ByteBuffer.wrap(normal).order(ByteOrder.LITTLE_ENDIAN);
    ByteBuffer result = ByteBuffer.allocate(normal.length + 76).order(ByteOrder.LITTLE_ENDIAN);
    result.put(normal, 0, end);
    result.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45);
    result.putInt(0).putInt(0).putLong(1).putLong(1);
    result.putLong(Integer.toUnsignedLong(source.getInt(end + 12)));
    result.putLong(Integer.toUnsignedLong(source.getInt(end + 16)));
    result.putInt(0x07064b50).putInt(0).putLong(end).putInt(1);
    result.put(normal, end, 22);
    result.putShort(result.capacity() - 14, (short) 0xffff);
    result.putShort(result.capacity() - 12, (short) 0xffff);
    result.putInt(result.capacity() - 10, -1).putInt(result.capacity() - 6, -1);
    Files.write(path, result.array());
    try (ZipFile jdk = new ZipFile(path.toFile())) {
      assertEquals(1, jdk.size(), "independent JDK ZIP64 control");
    }
    assertEquals(Set.of("A.class"), read(path).classes().keySet());
  }

  @Test
  void caseInsensitiveManifestSelectionRejectsAmbiguity() throws Exception {
    byte[] base = EngineTest.simple("A", Opcodes.V17);
    byte[] current = EngineTest.simple("A", Opcodes.V25);
    byte[] manifest =
        "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var entries = new TreeMap<String, byte[]>();
    entries.put("meta-inf/manifest.mf", manifest);
    entries.put("A.class", base);
    entries.put("META-INF/versions/25/A.class", current);
    assertArrayEquals(current, read(archive("case.jar", entries)).classes().get("A.class").bytes());
    entries.put("META-INF/MANIFEST.MF", manifest);
    assertThrows(InputFailure.class, () -> read(archive("ambiguous.jar", entries)));
  }

  @Test
  void explicitNamedPipeIsRejectedBeforeAnyBlockingOpen() throws Exception {
    Path fifo = temp.resolve("input.class");
    Process create = new ProcessBuilder("mkfifo", fifo.toString()).start();
    assertEquals(0, create.waitFor());
    assertTimeoutPreemptively(
        Duration.ofSeconds(1), () -> assertThrows(InputFailure.class, () -> read(fifo)));
  }
}
