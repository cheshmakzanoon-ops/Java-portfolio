package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** A deadline or interrupted parent cannot leave its experiment JVM running. */
final class ChildJvm {
  private ChildJvm() {}

  static void await(Process process, long timeoutSeconds, String description)
      throws IOException, InterruptedException {
    if (timeoutSeconds < 1 || timeoutSeconds > 7200)
      throw new IllegalArgumentException("child timeout bounds");
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
        throw new IOException(description + ": deadline exceeded");
      if (process.exitValue() != 0)
        throw new IOException(description + ": exit " + process.exitValue());
    } catch (InterruptedException interrupted) {
      terminate(process, interrupted);
      Thread.currentThread().interrupt();
      throw interrupted;
    } catch (IOException failure) {
      terminate(process, failure);
      throw failure;
    }
  }

  private static void terminate(Process process, Throwable primary) {
    if (!process.isAlive()) return;
    process.destroyForcibly();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS))
        primary.addSuppressed(new IOException("owned child did not terminate"));
    } catch (InterruptedException interrupted) {
      primary.addSuppressed(interrupted);
      Thread.currentThread().interrupt();
    }
  }
}
