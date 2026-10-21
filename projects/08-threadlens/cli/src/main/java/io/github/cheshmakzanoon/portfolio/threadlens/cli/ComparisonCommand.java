package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import io.github.cheshmakzanoon.portfolio.threadlens.report.ReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Owns local comparison inputs and staged output; no publication begins before both renders pass.
 */
final class ComparisonCommand {
  private ComparisonCommand() {}

  static void run(Path baseline, Path candidate, Path html, Path json) throws IOException {
    Path before = requireInput(baseline);
    Path after = requireInput(candidate);
    Path htmlOutput = requireOutput(html);
    Path jsonOutput = json == null ? null : requireOutput(json);
    protectInputs(htmlOutput, before, after);
    if (jsonOutput != null) {
      protectInputs(jsonOutput, before, after);
      if (aliases(htmlOutput, jsonOutput))
        throw new IOException("HTML and JSON comparison outputs must be distinct");
    }
    var baselineReport = ReportWriter.read(before);
    var candidateReport = ReportWriter.read(after);
    try (var htmlStage = new StagedOutput(htmlOutput);
        var jsonStage = jsonOutput == null ? null : new StagedOutput(jsonOutput)) {
      ReportWriter.comparison(htmlStage.temporary, baselineReport, candidateReport);
      if (jsonStage != null)
        ReportWriter.comparisonJson(jsonStage.temporary, baselineReport, candidateReport);
      htmlStage.publish();
      if (jsonStage != null) jsonStage.publish();
    }
  }

  private static Path requireInput(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("comparison input must be a regular unlinked report file");
    return path.toRealPath();
  }

  private static Path requireOutput(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null || absolute.getFileName() == null)
      throw new IOException("comparison output requires a file name");
    Path target = parent.toRealPath().resolve(absolute.getFileName());
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("comparison output must be a regular unlinked file");
    return target;
  }

  private static void protectInputs(Path output, Path baseline, Path candidate) throws IOException {
    if (aliases(output, baseline) || aliases(output, candidate))
      throw new IOException("comparison output must not replace an input report");
  }

  private static boolean aliases(Path first, Path second) throws IOException {
    return first.equals(second)
        || Files.exists(first, LinkOption.NOFOLLOW_LINKS)
            && Files.exists(second, LinkOption.NOFOLLOW_LINKS)
            && Files.isSameFile(first, second);
  }

  private static final class StagedOutput implements AutoCloseable {
    private final Path destination;
    private final Path temporary;

    StagedOutput(Path destination) throws IOException {
      this.destination = destination;
      temporary = Files.createTempFile(destination.getParent(), ".threadlens-comparison-", ".tmp");
    }

    void publish() throws IOException {
      // Same-directory staging allows an atomic individual replacement. The HTML/JSON pair is
      // not a filesystem transaction: a later move failure may leave one newly published artifact.
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
