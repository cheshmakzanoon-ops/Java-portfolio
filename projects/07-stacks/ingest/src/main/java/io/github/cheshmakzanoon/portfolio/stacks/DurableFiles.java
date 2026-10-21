package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;

public final class DurableFiles {
  private DurableFiles() {}

  public static void atomicWrite(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.getParent());
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
    try {
      try (FileChannel c =
          FileChannel.open(
              tmp,
              java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                  ? new java.nio.file.attribute.FileAttribute<?>[] {
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                  }
                  : new java.nio.file.attribute.FileAttribute<?>[0])) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) c.write(b);
        c.force(true);
      }
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      syncDirectory(path.getParent());
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static void syncDirectory(Path p) throws IOException {
    try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ)) {
      c.force(true);
    }
  }
}
