package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** One owning application process per data directory, including reader-only CLI invocations. */
public final class DataDirectoryLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  public DataDirectoryLock(Path root) throws IOException {
    channel =
        FileChannel.open(
            root.resolve("OWNER.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("data directory already owned");
    } catch (IOException | OverlappingFileLockException failure) {
      channel.close();
      throw new IOException("data directory already owned or lock unavailable", failure);
    }
  }

  @Override
  public void close() throws IOException {
    if (lock.isValid()) lock.release();
    channel.close();
  }
}
