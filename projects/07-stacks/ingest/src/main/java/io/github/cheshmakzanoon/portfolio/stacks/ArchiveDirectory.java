package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;

/** Bounded ordinary-ZIP directory identity, checked again against each streamed member. */
final class ArchiveDirectory {
  private record Entry(long crc, long compressed, long size, int method) {}

  private final Map<String, Entry> entries;

  private ArchiveDirectory(Map<String, Entry> entries) {
    this.entries = Map.copyOf(entries);
  }

  int size() {
    return entries.size();
  }

  void verify(ZipEntry member) throws IOException {
    Entry expected = entries.get(member.getName());
    if (expected == null
        || expected.crc() != member.getCrc()
        || expected.compressed() != member.getCompressedSize()
        || expected.size() != member.getSize()
        || expected.method() != member.getMethod())
      throw new IOException("ZIP local/directory identity mismatch");
  }

  static ArchiveDirectory read(Path archive, int maximum, BooleanSupplier cancelled)
      throws IOException {
    try (var channel = FileChannel.open(archive, StandardOpenOption.READ)) {
      long size = channel.size();
      int tailSize = (int) Math.min(size, 65557);
      if (tailSize < 22) throw new IOException("malformed ZIP container");
      ByteBuffer tail = ByteBuffer.allocate(tailSize).order(ByteOrder.LITTLE_ENDIAN);
      readAt(channel, tail, size - tailSize);
      int end = -1;
      for (int i = tailSize - 22; i >= 0; i--)
        if (tail.getInt(i) == 0x06054b50
            && i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) == tailSize) {
          end = i;
          break;
        }
      if (end < 0) throw new IOException("ZIP end record missing");
      int count = Short.toUnsignedInt(tail.getShort(end + 10));
      long directoryBytes = Integer.toUnsignedLong(tail.getInt(end + 12));
      long directoryOffset = Integer.toUnsignedLong(tail.getInt(end + 16));
      long endOffset = size - tailSize + end;
      if (tail.getShort(end + 4) != 0
          || tail.getShort(end + 6) != 0
          || Short.toUnsignedInt(tail.getShort(end + 8)) != count
          || count == 65535
          || directoryBytes == 0xffffffffL
          || directoryOffset == 0xffffffffL)
        throw new IOException("spanned or ZIP64 archive unsupported");
      if (count > maximum
          || directoryBytes < 46L * count
          || directoryOffset + directoryBytes != endOffset
          || count == 0 && (directoryOffset != 0 || directoryBytes != 0))
        throw new IOException("ZIP directory bounds");
      Map<String, Entry> entries = new HashMap<>();
      Set<Long> localOffsets = new HashSet<>();
      long position = directoryOffset, metadataBytes = 0;
      ByteBuffer header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < count; i++) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new IOException("cancelled");
        if (position + 46 > endOffset) throw new IOException("ZIP directory truncated");
        header.clear();
        readAt(channel, header, position);
        int flags = Short.toUnsignedInt(header.getShort(8));
        int method = Short.toUnsignedInt(header.getShort(10));
        int nameLength = Short.toUnsignedInt(header.getShort(28));
        int extraLength = Short.toUnsignedInt(header.getShort(30));
        int commentLength = Short.toUnsignedInt(header.getShort(32));
        long offset = Integer.toUnsignedLong(header.getInt(42));
        long compressed = Integer.toUnsignedLong(header.getInt(20));
        long expanded = Integer.toUnsignedLong(header.getInt(24));
        long next = position + 46L + nameLength + extraLength + commentLength;
        if ((metadataBytes += 128L + 3L * nameLength) > 64L * 1024 * 1024)
          throw new IOException("ZIP directory metadata quota");
        if (header.getInt(0) != 0x02014b50
            || header.getShort(34) != 0
            || (flags & 1) != 0
            || offset >= directoryOffset
            || compressed == 0xffffffffL
            || expanded == 0xffffffffL
            || next > endOffset
            || !localOffsets.add(offset)) throw new IOException("ZIP directory entry invalid");
        String name = name(channel, position + 46, nameLength);
        try {
          SnapshotManifest.normalize(name.replaceAll("/$", ""));
        } catch (IllegalArgumentException invalid) {
          throw new IOException("unsafe archive path", invalid);
        }
        ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        if (offset + 30 > directoryOffset) throw new IOException("ZIP local header truncated");
        readAt(channel, local, offset);
        int localName = Short.toUnsignedInt(local.getShort(26));
        int localExtra = Short.toUnsignedInt(local.getShort(28));
        if (local.getInt(0) != 0x04034b50
            || local.getShort(6) != (short) flags
            || local.getShort(8) != (short) method
            || offset + 30L + localName + localExtra + compressed > directoryOffset
            || !name.equals(name(channel, offset + 30, localName)))
          throw new IOException("ZIP local/directory header mismatch");
        if (name.endsWith("/") && expanded != 0)
          throw new IOException("ZIP directory contains payload");
        if (entries.putIfAbsent(
                name,
                new Entry(Integer.toUnsignedLong(header.getInt(16)), compressed, expanded, method))
            != null) throw new IOException("duplicate ZIP directory name");
        position = next;
      }
      if (position != endOffset) throw new IOException("ZIP directory count mismatch");
      return new ArchiveDirectory(entries);
    }
  }

  private static String name(FileChannel channel, long offset, int length) throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(length);
    readAt(channel, bytes, offset);
    bytes.flip();
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(bytes)
        .toString();
  }

  private static void readAt(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    while (buffer.hasRemaining()) {
      int count = channel.read(buffer, position);
      if (count <= 0) throw new EOFException("ZIP metadata truncated");
      position += count;
    }
  }
}
