package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IngestionSafetyTest {
  @TempDir Path dir;

  ContentAddressedStore cas() throws IOException {
    return new ContentAddressedStore(dir.resolve("cas"));
  }

  SafeSourceReader reader(SafeSourceReader.Limits limits) throws IOException {
    Files.createDirectories(dir.resolve("source"));
    return new SafeSourceReader(dir.resolve("source"), limits, cas());
  }

  @Test
  void completionReceiptsBindRegisteredManifestAndRejectCorruptOrOversizedMetadata()
      throws Exception {
    var jobs = new IngestionJob(dir.resolve("jobs"));
    var manifest = new SnapshotManifest("t", "r", "v1", false, List.of(), Set.of());
    var job = jobs.register("key", manifest);
    String generation = UUID.randomUUID().toString();
    assertFalse(jobs.completed(job.id()));
    assertThrows(IOException.class, () -> jobs.complete(job.id(), "0".repeat(64), generation));
    assertThrows(IOException.class, () -> jobs.complete(job.id(), job.manifestHash(), "invalid"));
    jobs.complete(job.id(), job.manifestHash(), generation);
    assertTrue(jobs.completed(job.id()));
    Path receipt = dir.resolve("jobs").resolve(job.id() + ".done");
    String original = Files.readString(receipt);
    jobs.complete(job.id(), job.manifestHash(), UUID.randomUUID().toString());
    assertEquals(original, Files.readString(receipt));
    for (int field = 0; field < 5; field++) {
      String[] parts = original.split("\\n");
      parts[field] = "invalid";
      Files.writeString(receipt, String.join("\n", parts));
      assertThrows(IOException.class, () -> jobs.completed(job.id()), "corrupt field " + field);
    }
    Files.writeString(receipt, "truncated");
    assertThrows(IOException.class, () -> jobs.completed(job.id()));
    Files.writeString(receipt, "x".repeat(513));
    assertThrows(IOException.class, () -> jobs.completed(job.id()));
    Files.writeString(receipt, original);
    assertTrue(jobs.completed(job.id()));
  }

  @Test
  void durablePublicationUsesOwnerOnlyPosixPermissionsFromCreation() throws Exception {
    Path output = dir.resolve("private/key");
    DurableFiles.atomicWrite(output, "first secret".getBytes(StandardCharsets.UTF_8));
    var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
    assertEquals(permissions, Files.getPosixFilePermissions(output));
    Files.setPosixFilePermissions(
        output, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    DurableFiles.atomicWrite(output, "replacement secret".getBytes(StandardCharsets.UTF_8));
    assertEquals(permissions, Files.getPosixFilePermissions(output));
    assertEquals("replacement secret", Files.readString(output));
    try (var files = Files.list(output.getParent())) {
      assertEquals(List.of(output), files.toList());
    }
  }

  @Test
  void selectedUtf8FileKeepsItsCanonicalFilename() throws Exception {
    var reader = reader(SafeSourceReader.Limits.defaults());
    Files.writeString(dir.resolve("source/Selected.java"), "class Selected {} ");
    var m =
        reader.directory("t", "r", "one", Path.of("Selected.java"), false, Set.of(), () -> false);
    assertEquals(1, m.entries().size());
    assertEquals("Selected.java", m.entries().getFirst().path());
    assertEquals("class Selected {} ", cas().text(m.entries().getFirst().sha256()));
  }

  @Test
  void malformedZipContainersCannotBecomeSuccessfulEmptySnapshots() throws Exception {
    var reader = reader(new SafeSourceReader.Limits(10000, 20000, 2, 100));
    Path file = dir.resolve("source/input.zip");
    zip(file, "A.java", "class A {}");
    byte[] valid = Files.readAllBytes(file);
    int end = valid.length - 22;
    int central =
        java.nio.ByteBuffer.wrap(valid).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(end + 16);
    List<byte[]> malformed = new ArrayList<>();
    malformed.add(new byte[0]);
    malformed.add(new byte[32]);
    malformed.add(Arrays.copyOf(valid, valid.length - 1));
    for (int[] change :
        List.of(
            new int[] {end + 4, 1},
            new int[] {end + 6, 1},
            new int[] {end + 8, 2},
            new int[] {end + 20, 1},
            new int[] {central, 0},
            new int[] {central + 34, 1})) {
      byte[] bad = valid.clone();
      bad[change[0]] = (byte) change[1];
      malformed.add(bad);
    }
    for (int[] change :
        List.of(
            new int[] {end + 12, 0}, new int[] {end + 16, -1}, new int[] {central + 42, central})) {
      byte[] bad = valid.clone();
      java.nio.ByteBuffer.wrap(bad)
          .order(java.nio.ByteOrder.LITTLE_ENDIAN)
          .putInt(change[0], change[1]);
      malformed.add(bad);
    }
    byte[] names = valid.clone();
    java.nio.ByteBuffer.wrap(names)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putShort(central + 28, (short) 65535);
    malformed.add(names);
    for (int count : List.of(3, 65535)) {
      byte[] bad = valid.clone();
      var fields = java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      fields.putShort(end + 8, (short) count);
      fields.putShort(end + 10, (short) count);
      malformed.add(bad);
    }
    // A structurally bounded directory advertises two entries but the local stream has only one.
    int directoryBytes = end - central;
    byte[] mismatch = new byte[valid.length + directoryBytes];
    System.arraycopy(valid, 0, mismatch, 0, end);
    System.arraycopy(valid, central, mismatch, end, directoryBytes);
    System.arraycopy(valid, end, mismatch, end + directoryBytes, 22);
    var fields = java.nio.ByteBuffer.wrap(mismatch).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    fields.putShort(end + directoryBytes + 8, (short) 2);
    fields.putShort(end + directoryBytes + 10, (short) 2);
    fields.putInt(end + directoryBytes + 12, directoryBytes * 2);
    malformed.add(mismatch);
    for (byte[] bad : malformed) {
      Files.write(file, bad);
      assertThrows(
          IOException.class,
          () -> reader.archive("t", "r", "one", Path.of("input.zip"), () -> false));
    }
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.setComment("bounded empty archive");
    }
    assertTrue(
        reader.archive("t", "r", "empty", Path.of("input.zip"), () -> false).entries().isEmpty());
    zip(file, "A.java", "class A {}");
    assertThrows(
        IOException.class, () -> reader.archive("t", "r", "one", Path.of("input.zip"), () -> true));
  }

  @Test
  void manifestMetadataCannotAllocateBeyondDeclaredByteBudget() {
    String hash = Digests.sha256("source");
    for (int field = 0; field < 4; field++) {
      final int selected = field;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SnapshotManifest.Entry(
                  "A.java",
                  hash,
                  selected == 0 ? "l".repeat(33) : "java",
                  selected == 1 ? "k".repeat(33) : "source",
                  List.of("symbol"),
                  selected == 2 ? "c".repeat(257) : "",
                  selected == 3 ? "v".repeat(129) : ""));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotManifest.Entry("A", hash, "java", "source", List.of(""), "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotManifest.Entry(
                "A", hash, "java", "source", List.of("x".repeat(257)), "", ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotManifest.Entry(
                "A", hash, "java", "source", Collections.nCopies(257, "symbol"), "", ""));
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (int i = 0; i < 1000; i++)
      entries.add(
          new SnapshotManifest.Entry(
              "src/A" + i,
              hash,
              "java",
              "source",
              Collections.nCopies(256, "x".repeat(256)),
              "",
              ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotManifest("t", "r", "rev", false, entries, Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotManifest(
                "t", "r", "rev", false, Collections.nCopies(100001, entries.getFirst()), Set.of()));
  }

  @Test
  void contentDecoderRejectsMalformedBytesAndOversizedObjects() throws Exception {
    var store = cas();
    String exact = store.put(new byte[1048576]);
    assertEquals(1048576, store.read(exact).length);
    assertEquals(1048576, store.size(exact));
    String bad = store.put(new byte[] {(byte) 0xc3, 0x28});
    assertThrows(IOException.class, () -> store.text(bad));
    String big = Digests.sha256("large");
    Files.write(dir.resolve("cas").resolve(big), new byte[1048577]);
    assertThrows(IOException.class, () -> store.read(big));
  }

  @Test
  void artifactMetadataIsValidatedAndContentAddressed() throws Exception {
    var reader = reader(SafeSourceReader.Limits.defaults());
    Path file = dir.resolve("source/build.artifact");
    Files.writeString(
        file, "coordinates=io.demo:search\nversion=2.1\nAssociated build artifact documentation.");
    var entry =
        reader
            .directory("t", "r", "rev", Path.of("."), false, Set.of(), () -> false)
            .entries()
            .getFirst();
    assertEquals("io.demo:search", entry.coordinates());
    assertEquals("2.1", entry.version());
    assertEquals("artifact", entry.kind());
    assertEquals(Digests.sha256(Files.readAllBytes(file)), entry.sha256());
    for (String invalid :
        List.of(
            "missing headers",
            "coordinates=bad\nversion=2\ndoc",
            "coordinates=io.demo:search\nversion=\ndoc")) {
      Files.writeString(file, invalid);
      assertThrows(
          IOException.class,
          () -> reader.directory("t", "r", "rev", Path.of("."), false, Set.of(), () -> false));
    }
  }

  @Test
  void directoryTraversalIsBoundedBeforeCollectingOrSorting() throws Exception {
    var reader = reader(new SafeSourceReader.Limits(16, 32, 2, 100));
    for (int i = 0; i < 10; i++) Files.createDirectory(dir.resolve("source/empty" + i));
    IOException failure =
        assertThrows(
            IOException.class,
            () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
    assertTrue(failure.getMessage().contains("traversal quota"));
  }

  @Test
  void canonicalPathsRejectTraversalAndWindowsAndAmbiguity() {
    for (String p : List.of("../a", "/a", "a/../b", "a/./b", "a//b", "C:x", "a\\b", "", "a\0b"))
      assertThrows(IllegalArgumentException.class, () -> SnapshotManifest.normalize(p), p);
    assertEquals("src/é.java", SnapshotManifest.normalize("src/é.java"));
  }

  @Test
  void strictUtf8AndBoundedFilesAndCancellation() throws Exception {
    var reader = reader(new SafeSourceReader.Limits(16, 32, 2, 100));
    Path file = dir.resolve("source/A.java");
    Files.write(file, new byte[] {(byte) 0xc3, 0x28});
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
    Files.writeString(file, "a".repeat(17));
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
    Files.writeString(file, "valid");
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> true));
  }

  @Test
  void configuredRootsAndSymlinksCannotEscape() throws Exception {
    var reader = reader(SafeSourceReader.Limits.defaults());
    Files.writeString(dir.resolve("outside"), "secret");
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("../"), false, Set.of(), () -> false));
    Files.createSymbolicLink(dir.resolve("source/link"), dir.resolve("outside"));
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
    assertThrows(
        IOException.class, () -> reader.archive("t", "r", "1", Path.of("link"), () -> false));
  }

  void zip(Path file, String name, String text) throws IOException {
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(text.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
  }

  @Test
  void archivesRejectPathEscapeExpansionAndEntryQuotas() throws Exception {
    var reader = reader(new SafeSourceReader.Limits(10000, 20000, 2, 2));
    Path z = dir.resolve("source/input.zip");
    zip(z, "../escape", "x");
    assertThrows(
        IOException.class, () -> reader.archive("t", "r", "1", Path.of("input.zip"), () -> false));
    zip(z, "A.java", "a".repeat(9000));
    assertThrows(
        IOException.class, () -> reader.archive("t", "r", "1", Path.of("input.zip"), () -> false));
    zip(z, "A.java", "public class A {}");
    var m = reader.archive("t", "r", "1", Path.of("input.zip"), () -> false);
    assertEquals(1, m.entries().size());
    assertFalse(m.complete());
    assertEquals("public class A {}", cas().text(m.entries().getFirst().sha256()));
  }

  @Test
  void documentAndAggregateQuotasFailExplicitly() throws Exception {
    var reader = reader(new SafeSourceReader.Limits(16, 20, 2, 100));
    Files.writeString(dir.resolve("source/a"), "a".repeat(11));
    Files.writeString(dir.resolve("source/b"), "b".repeat(11));
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
    Files.writeString(dir.resolve("source/a"), "a");
    Files.writeString(dir.resolve("source/b"), "b");
    Files.writeString(dir.resolve("source/c"), "c");
    assertThrows(
        IOException.class,
        () -> reader.directory("t", "r", "1", Path.of("."), false, Set.of(), () -> false));
  }

  @Test
  void contentAddressingVerifiesExistingObjectsAndDigestPaths() throws Exception {
    var c = cas();
    String hash = c.put("α UTF8".getBytes(StandardCharsets.UTF_8));
    assertEquals(hash, c.put("α UTF8".getBytes(StandardCharsets.UTF_8)));
    assertEquals("α UTF8", c.text(hash));
    assertEquals("α UTF8".getBytes(StandardCharsets.UTF_8).length, c.size(hash));
    Files.writeString(dir.resolve("cas").resolve(hash), "tampered");
    assertThrows(IOException.class, () -> c.read(hash));
    assertThrows(IOException.class, () -> c.read("../x"));
    assertThrows(IOException.class, () -> c.put("α UTF8".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void immutableManifestRoundtripIdempotencyAndConflicts() throws Exception {
    var e =
        new SnapshotManifest.Entry(
            "src/A.java", Digests.sha256("a"), "java", "source", List.of("fooBar"), "g:a", "1");
    var m = new SnapshotManifest("t", "r", "rev", true, List.of(e), Set.of("old.java"));
    assertArrayEquals(m.bytes(), SnapshotManifest.read(m.bytes()).bytes());
    var jobs = new IngestionJob(dir.resolve("jobs"));
    var first = jobs.register("key", m);
    assertEquals(first, jobs.register("key", m));
    assertEquals(m, jobs.get(first.id()).manifest());
    assertThrows(
        IngestionJob.Conflict.class,
        () ->
            jobs.register(
                "key",
                new SnapshotManifest("t", "r", "rev2", true, List.of(e), Set.of("old.java"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotManifest("t", "r", "x", false, List.of(e, e), Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotManifest("t", "r", "x", false, List.of(e), Set.of(e.path())));
    assertThrows(IllegalArgumentException.class, () -> jobs.register("", m));
  }

  @Test
  void corruptMetadataAndCheckpointsAreNotTrusted() throws Exception {
    var cp = new CheckpointStore(dir.resolve("cp"));
    String hash = Digests.sha256("manifest");
    assertTrue(cp.read(hash).isEmpty());
    cp.save(hash, 7);
    assertEquals(7, cp.read(hash).orElseThrow());
    Files.writeString(dir.resolve("cp").resolve(hash), "bad");
    assertThrows(IOException.class, () -> cp.read(hash));
    assertThrows(IllegalArgumentException.class, () -> cp.save(hash, -1));
    assertThrows(IOException.class, () -> SnapshotManifest.read(new byte[] {1, 2}));
    byte[] bad = new SnapshotManifest("t", "r", "x", false, List.of(), Set.of()).bytes();
    bad[3] = 2;
    assertThrows(IOException.class, () -> SnapshotManifest.read(bad));
  }
}
