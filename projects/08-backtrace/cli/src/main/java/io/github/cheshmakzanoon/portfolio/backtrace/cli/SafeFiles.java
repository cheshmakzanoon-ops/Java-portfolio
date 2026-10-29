package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local-file rules shared by the commands that read finished reports and write derived artifacts:
 * inputs are regular unlinked files, outputs are regular unlinked files that cannot alias an input,
 * and each artifact is staged beside its destination and published by one atomic replacement.
 */
final class SafeFiles {
  private final String noun;
  private final String stagingPrefix;

  SafeFiles(String noun) {
    this.noun = noun;
    this.stagingPrefix = ".backtrace-" + noun + "-";
  }

  Path requireInput(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException(noun + " input must be a regular unlinked report file");
    return path.toRealPath();
  }

  Path requireOutput(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null || absolute.getFileName() == null)
      throw new IOException(noun + " output requires a file name");
    Path target = parent.toRealPath().resolve(absolute.getFileName());
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
      throw new IOException(noun + " output must be a regular unlinked file");
    return target;
  }

  void protect(Path output, Path... inputs) throws IOException {
    for (Path input : inputs)
      if (aliases(output, input))
        throw new IOException(noun + " output must not replace an input report");
  }

  static boolean aliases(Path first, Path second) throws IOException {
    return first.equals(second)
        || Files.exists(first, LinkOption.NOFOLLOW_LINKS)
            && Files.exists(second, LinkOption.NOFOLLOW_LINKS)
            && Files.isSameFile(first, second);
  }

  StagedOutput stage(Path destination) throws IOException {
    return new StagedOutput(destination, stagingPrefix);
  }

  static final class StagedOutput implements AutoCloseable {
    private final Path destination;
    final Path temporary;

    private StagedOutput(Path destination, String prefix) throws IOException {
      this.destination = destination;
      temporary = Files.createTempFile(destination.getParent(), prefix, ".tmp");
    }

    void publish() throws IOException {
      // Same-directory staging allows an atomic individual replacement. A set of outputs is not a
      // filesystem transaction: a later move failure may leave one newly published artifact.
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() throws IOException {
      Files.deleteIfExists(temporary);
    }
  }
}
