package io.github.cheshmakzanoon.portfolio.faultline.input;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bounds the ZIP index before ZipFile allocates its central-directory structures. */
final class ArchiveDirectory {
  static final long MAX_METADATA_BYTES = 16L * 1024 * 1024;

  @FunctionalInterface
  interface Deadline {
    void check() throws InputFailure;
  }

  private ArchiveDirectory() {}

  static void validate(Path path, int remainingEntries, Deadline deadline) throws IOException {
    try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ)) {
      long length = file.size();
      if (length < 22) throw invalid("missing end record");
      int tailLength = (int) Math.min(length, 65557);
      ByteBuffer tail = read(file, length - tailLength, tailLength, deadline);
      int end = -1;
      for (int i = tailLength - 22; i >= 0; i--) {
        deadline.check();
        if (tail.getInt(i) == 0x06054b50 && i + 22 + u16(tail, i + 20) == tailLength) {
          end = i;
          break;
        }
      }
      if (end < 0) throw invalid("missing or truncated end record");
      long endOffset = length - tailLength + end;
      if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0)
        throw invalid("split archives are unsupported");
      long count = u16(tail, end + 10);
      long size = u32(tail, end + 12);
      long offset = u32(tail, end + 16);
      long indexEnd = endOffset;
      if (count == 0xffff || size == 0xffffffffL || offset == 0xffffffffL) {
        if (endOffset < 20) throw invalid("missing ZIP64 locator");
        ByteBuffer locator = read(file, endOffset - 20, 20, deadline);
        if (locator.getInt(0) != 0x07064b50 || locator.getInt(4) != 0 || locator.getInt(16) != 1)
          throw invalid("invalid ZIP64 locator");
        long zip64Offset = locator.getLong(8);
        if (zip64Offset < 0 || zip64Offset > endOffset - 20 - 56)
          throw invalid("invalid ZIP64 end offset");
        ByteBuffer zip64 = read(file, zip64Offset, 56, deadline);
        long recordSize = zip64.getLong(4);
        if (zip64.getInt(0) != 0x06064b50
            || recordSize < 44
            || recordSize > MAX_METADATA_BYTES
            || zip64Offset + 12 + recordSize != endOffset - 20
            || zip64.getInt(16) != 0
            || zip64.getInt(20) != 0
            || zip64.getLong(24) != zip64.getLong(32)) throw invalid("invalid ZIP64 end record");
        count = zip64.getLong(32);
        size = zip64.getLong(40);
        offset = zip64.getLong(48);
        indexEnd = zip64Offset;
      } else if (u16(tail, end + 8) != count) {
        throw invalid("inconsistent entry count");
      }
      if (count < 0 || count > remainingEntries) throw new InputFailure("entry count exceeded");
      if (size < 0 || size > MAX_METADATA_BYTES)
        throw invalid("central-directory byte limit exceeded");
      if (offset < 0 || offset > indexEnd || size != indexEnd - offset)
        throw invalid("central-directory bounds disagree with end record");
      long cursor = offset;
      for (long entry = 0; entry < count; entry++) {
        deadline.check();
        if (indexEnd - cursor < 46) throw invalid("truncated central-directory entry");
        ByteBuffer header = read(file, cursor, 46, deadline);
        if (header.getInt(0) != 0x02014b50) throw invalid("invalid central-directory entry");
        int name = u16(header, 28);
        int extra = u16(header, 30);
        int comment = u16(header, 32);
        long bytes = 46L + name + extra + comment;
        if (name == 0 || name > 16384 || bytes > indexEnd - cursor)
          throw invalid("invalid central-directory name or lengths");
        if (u16(header, 34) != 0) throw invalid("split archives are unsupported");
        if ((u16(header, 8) & 1) != 0) throw invalid("encrypted archives are unsupported");
        int unixMode = (int) (u32(header, 38) >>> 16);
        if ((u16(header, 4) >>> 8) == 3 && (unixMode & 0170000) == 0120000)
          throw invalid("symbolic archive entry rejected");
        long local = u32(header, 42);
        // ZIP64 local offsets live in the extra field and are checked by ZipFile.
        if (local != 0xffffffffL && (local >= offset || offset - local < 30))
          throw invalid("invalid local-header offset");
        cursor += bytes;
      }
      if (cursor != indexEnd) throw invalid("central-directory count or size mismatch");
    }
  }

  private static ByteBuffer read(FileChannel file, long offset, int length, Deadline deadline)
      throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    while (bytes.hasRemaining()) {
      deadline.check();
      int read = file.read(bytes, offset + bytes.position());
      if (read < 0) throw invalid("truncated archive metadata");
    }
    return bytes.flip();
  }

  private static int u16(ByteBuffer bytes, int offset) {
    return Short.toUnsignedInt(bytes.getShort(offset));
  }

  private static long u32(ByteBuffer bytes, int offset) {
    return Integer.toUnsignedLong(bytes.getInt(offset));
  }

  private static InputFailure invalid(String reason) {
    return new InputFailure("corrupt or unsupported archive: " + reason);
  }
}
