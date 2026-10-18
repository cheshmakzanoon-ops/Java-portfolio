package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

public final class SafeSourceReader {
  public record Limits(long fileBytes, long totalBytes, int entries, int expansionRatio) {
    public Limits {
      if (fileBytes < 1
          || fileBytes > 1_048_576
          || totalBytes < 1
          || entries < 1
          || expansionRatio < 1) throw new IllegalArgumentException("limits");
    }

    public static Limits defaults() {
      return new Limits(1_048_576, 256L * 1024 * 1024, 100_000, 100);
    }
  }

  private final Path root;
  private final Limits limits;
  private final ContentAddressedStore store;

  public SafeSourceReader(Path configuredRoot, Limits limits, ContentAddressedStore store)
      throws IOException {
    root = configuredRoot.toRealPath();
    if (!Files.isDirectory(root)) throw new IOException("root is not directory");
    this.limits = limits;
    this.store = store;
  }

  public SnapshotManifest directory(
      String tenant,
      String repo,
      String revision,
      Path selected,
      boolean complete,
      Set<String> deleted,
      BooleanSupplier cancelled)
      throws IOException {
    Path base = resolve(selected);
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    long total = 0;
    try (var paths = Files.walk(base)) {
      var iterator = paths.iterator();
      int visited = 0;
      while (iterator.hasNext()) {
        Path p = iterator.next();
        if (++visited > limits.entries() + 1) throw new IOException("snapshot traversal quota");
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(p)) throw new IOException("symlinks prohibited");
        if (Files.isDirectory(p)) continue;
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("not a regular file");
        resolve(p);
        byte[] b = bounded(Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS), limits.fileBytes());
        total = Math.addExact(total, b.length);
        if (total > limits.totalBytes() || entries.size() >= limits.entries())
          throw new IOException("snapshot quota");
        entries.add(entry(root.relativize(p).toString().replace('\\', '/'), b));
      }
    }
    return new SnapshotManifest(tenant, repo, revision, complete, entries, deleted);
  }

  public SnapshotManifest archive(
      String tenant, String repo, String revision, Path selected, BooleanSupplier cancelled)
      throws IOException {
    Path archive = resolve(selected);
    long compressed = Files.size(archive);
    if (compressed > limits.totalBytes()) throw new IOException("archive quota");
    int expectedEntries = archiveEntryCount(archive, cancelled);
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    Set<String> names = new HashSet<>();
    long total = 0;
    try (ZipInputStream zip =
        new ZipInputStream(
            Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
      ZipEntry z;
      while ((z = zip.getNextEntry()) != null) {
        checkCancelled(cancelled);
        String name;
        try {
          name = SnapshotManifest.normalize(z.getName().replaceAll("/$", ""));
        } catch (IllegalArgumentException e) {
          throw new IOException("unsafe archive path", e);
        }
        if (!names.add(name) || names.size() > limits.entries())
          throw new IOException("archive entry quota or duplicate");
        if (z.isDirectory()) continue;
        byte[] b = bounded(zip, limits.fileBytes());
        total += b.length;
        if (total > limits.totalBytes()
            || total > Math.max(1, compressed) * limits.expansionRatio())
          throw new IOException("archive expansion quota");
        entries.add(entry(name, b));
      }
    }
    if (names.size() != expectedEntries)
      throw new IOException("ZIP local/directory count mismatch");
    return new SnapshotManifest(tenant, repo, revision, false, entries, Set.of());
  }

  private int archiveEntryCount(Path archive, BooleanSupplier cancelled) throws IOException {
    try (var channel = java.nio.channels.FileChannel.open(archive, StandardOpenOption.READ)) {
      long size = channel.size();
      int tailSize = (int) Math.min(size, 65557);
      if (tailSize < 22) throw new IOException("malformed ZIP container");
      java.nio.ByteBuffer tail =
          java.nio.ByteBuffer.allocate(tailSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      readAt(channel, tail, size - tailSize);
      int end = -1;
      for (int i = tailSize - 22; i >= 0; i--)
        if (tail.getInt(i) == 0x06054b50
            && i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) == tailSize) {
          end = i;
          break;
        }
      if (end < 0) throw new IOException("ZIP end record missing");
      int entries = Short.toUnsignedInt(tail.getShort(end + 10));
      long directoryBytes = Integer.toUnsignedLong(tail.getInt(end + 12)),
          directoryOffset = Integer.toUnsignedLong(tail.getInt(end + 16)),
          endOffset = size - tailSize + end;
      if (tail.getShort(end + 4) != 0
          || tail.getShort(end + 6) != 0
          || Short.toUnsignedInt(tail.getShort(end + 8)) != entries
          || entries == 65535
          || directoryBytes == 0xffffffffL
          || directoryOffset == 0xffffffffL)
        throw new IOException("spanned or ZIP64 archive unsupported");
      if (entries > limits.entries()
          || directoryBytes < 46L * entries
          || directoryOffset + directoryBytes != endOffset
          || (entries == 0 && (directoryOffset != 0 || directoryBytes != 0)))
        throw new IOException("ZIP directory bounds");
      long position = directoryOffset;
      java.nio.ByteBuffer header =
          java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < entries; i++) {
        checkCancelled(cancelled);
        if (position + 46 > endOffset) throw new IOException("ZIP directory truncated");
        header.clear();
        readAt(channel, header, position);
        if (header.getInt(0) != 0x02014b50
            || header.getShort(34) != 0
            || Integer.toUnsignedLong(header.getInt(42)) >= directoryOffset)
          throw new IOException("ZIP directory entry invalid");
        position +=
            46L
                + Short.toUnsignedInt(header.getShort(28))
                + Short.toUnsignedInt(header.getShort(30))
                + Short.toUnsignedInt(header.getShort(32));
        if (position > endOffset) throw new IOException("ZIP directory overflow");
      }
      if (position != endOffset) throw new IOException("ZIP directory count mismatch");
      return entries;
    }
  }

  private static void readAt(
      java.nio.channels.FileChannel channel, java.nio.ByteBuffer buffer, long position)
      throws IOException {
    while (buffer.hasRemaining()) {
      int count = channel.read(buffer, position);
      if (count <= 0) throw new EOFException("ZIP metadata truncated");
      position += count;
    }
  }

  private Path resolve(Path path) throws IOException {
    Path p = path.isAbsolute() ? path : root.resolve(path);
    if (!p.normalize().startsWith(root)) throw new IOException("path escape");
    Path current = root;
    for (Path part : root.relativize(p.normalize())) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("symlinks prohibited");
    }
    Path real = p.toRealPath();
    if (!real.startsWith(root)) throw new IOException("path escape");
    return real;
  }

  private SnapshotManifest.Entry entry(String path, byte[] bytes) throws IOException {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("invalid UTF-8", e);
    }
    List<String> symbols = new ArrayList<>();
    var matcher = java.util.regex.Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*").matcher(text);
    while (matcher.find() && symbols.size() < 256) {
      String s = matcher.group();
      if (s.length() <= 256 && !symbols.contains(s)) symbols.add(s);
    }
    String coordinates = "", version = "";
    boolean artifact = path.endsWith(".artifact");
    if (artifact) {
      String[] lines = text.split("\n", 3);
      if (lines.length < 3
          || !lines[0].startsWith("coordinates=")
          || !lines[1].startsWith("version="))
        throw new IOException("artifact metadata headers required");
      coordinates = lines[0].substring(12).strip();
      version = lines[1].substring(8).strip();
      if (!coordinates.matches("[A-Za-z0-9_.-]{1,120}:[A-Za-z0-9_.-]{1,120}")
          || version.isBlank()
          || version.length() > 128) throw new IOException("artifact coordinates/version invalid");
    }
    return new SnapshotManifest.Entry(
        path,
        store.put(bytes),
        path.endsWith(".java") ? "java" : "text",
        artifact ? "artifact" : path.endsWith(".java") ? "source" : "documentation",
        symbols,
        coordinates,
        version);
  }

  private static byte[] bounded(InputStream in, long max) throws IOException {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      long size = 0;
      while ((n = in.read(buffer)) != -1) {
        size += n;
        if (size > max) throw new IOException("file quota");
        b.write(buffer, 0, n);
      }
      return b.toByteArray();
    } finally {
      if (!(in instanceof ZipInputStream)) in.close();
    }
  }

  private static void checkCancelled(BooleanSupplier c) throws IOException {
    if (c.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("cancelled");
  }
}
