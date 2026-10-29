package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import io.github.cheshmakzanoon.portfolio.backtrace.report.FlameExport;
import io.github.cheshmakzanoon.portfolio.backtrace.report.ReportWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts a finished report's sampled stacks into formats other profilers read. The input is
 * validated as a report first; the output is staged and published atomically.
 */
final class ExportCommand {
  private static final SafeFiles FILES = new SafeFiles("export");

  static final String USAGE =
      "export folded report.json output.txt | export speedscope report.json output.json"
          + " | export diff baseline.json candidate.json output.txt";

  private ExportCommand() {}

  static void run(String format, Path[] inputs, Path output) throws IOException {
    Path[] resolved = new Path[inputs.length];
    for (int i = 0; i < inputs.length; i++) resolved[i] = FILES.requireInput(inputs[i]);
    Path destination = FILES.requireOutput(output);
    FILES.protect(destination, resolved);
    var first = ReportWriter.read(resolved[0]);
    String text =
        switch (format) {
          case "folded" -> FlameExport.folded(first.profile().flame());
          case "speedscope" -> FlameExport.speedscope(first.recordingId(), first.profile().flame());
          case "diff" ->
              FlameExport.foldedDifferential(
                  first.profile().flame(), ReportWriter.read(resolved[1]).profile().flame());
          default -> throw new IllegalArgumentException(ExportCommand.USAGE);
        };
    try (var stage = FILES.stage(destination)) {
      Files.writeString(stage.temporary, text, StandardCharsets.UTF_8);
      stage.publish();
    }
  }
}
