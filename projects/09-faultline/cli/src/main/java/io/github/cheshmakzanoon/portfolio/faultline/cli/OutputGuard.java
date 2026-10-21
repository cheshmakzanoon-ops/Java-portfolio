package io.github.cheshmakzanoon.portfolio.faultline.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Refuse output locations that can replace analysis inputs or their configuration. */
final class OutputGuard {
  private OutputGuard() {}

  static void validate(
      Path output,
      Path explanations,
      List<Path> inputs,
      List<Path> classpath,
      Path rules,
      Path baseline)
      throws IOException {
    Path resolvedOutput = resolved(output);
    if (Files.isSymbolicLink(output)) throw new IOException("symbolic report output rejected");
    Path resolvedExplanations = explanations == null ? null : resolved(explanations);
    for (Path protectedPath :
        java.util.stream.Stream.concat(
                java.util.stream.Stream.concat(inputs.stream(), classpath.stream()),
                java.util.stream.Stream.of(rules, baseline).filter(java.util.Objects::nonNull))
            .toList()) {
      Path resolvedProtected = resolved(protectedPath);
      if (Files.isDirectory(protectedPath)) {
        if (resolvedOutput.startsWith(resolvedProtected)
            || resolvedExplanations != null && resolvedExplanations.startsWith(resolvedProtected))
          throw new IOException("output must be outside input directories");
      } else {
        if (same(output, resolvedOutput, protectedPath, resolvedProtected))
          throw new IOException("report output aliases an input or configuration file");
        if (resolvedExplanations != null && resolvedProtected.startsWith(resolvedExplanations))
          throw new IOException("explanation directory contains an input or configuration file");
      }
    }
    if (resolvedExplanations != null && resolvedOutput.startsWith(resolvedExplanations))
      throw new IOException("report output must be outside the explanation directory");
  }

  private static boolean same(Path output, Path resolvedOutput, Path input, Path resolvedInput)
      throws IOException {
    return resolvedOutput.equals(resolvedInput)
        || Files.exists(output) && Files.exists(input) && Files.isSameFile(output, input);
  }

  private static Path resolved(Path path) throws IOException {
    // Resolve existing components before collapsing '..': a preceding symbolic link can
    // change which parent the filesystem will use when the output is opened.
    Path absolute = path.toAbsolutePath();
    if (Files.exists(absolute)) return absolute.toRealPath();
    Path parent = absolute.getParent();
    return parent == null ? absolute : resolved(parent).resolve(absolute.getFileName()).normalize();
  }
}
