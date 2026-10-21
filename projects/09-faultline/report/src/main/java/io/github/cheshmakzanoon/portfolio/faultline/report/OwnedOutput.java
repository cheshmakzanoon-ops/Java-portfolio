package io.github.cheshmakzanoon.portfolio.faultline.report;

import java.io.IOException;
import java.nio.file.*;

/** Replaces the directory entry, never truncating a preexisting linked target. */
final class OwnedOutput {
  @FunctionalInterface
  interface Writer {
    void write(Path temporary) throws IOException;
  }

  private OwnedOutput() {}

  static void write(Path path, Writer writer) throws IOException {
    Path destination = path.toAbsolutePath();
    Path parent = destination.getParent();
    Files.createDirectories(parent);
    if (Files.isSymbolicLink(destination)) throw new IOException("symbolic report output rejected");
    Path temporary = Files.createTempFile(parent, ".faultline-report-", ".tmp");
    try {
      writer.write(temporary);
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unavailable) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
