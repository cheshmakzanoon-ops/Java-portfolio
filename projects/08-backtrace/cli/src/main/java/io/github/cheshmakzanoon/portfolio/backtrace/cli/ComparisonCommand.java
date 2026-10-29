package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import io.github.cheshmakzanoon.portfolio.backtrace.report.ReportWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Owns local comparison inputs and staged output; no publication begins before both renders pass.
 */
final class ComparisonCommand {
  private static final SafeFiles FILES = new SafeFiles("comparison");

  private ComparisonCommand() {}

  static void run(Path baseline, Path candidate, Path html, Path json) throws IOException {
    Path before = FILES.requireInput(baseline);
    Path after = FILES.requireInput(candidate);
    Path htmlOutput = FILES.requireOutput(html);
    Path jsonOutput = json == null ? null : FILES.requireOutput(json);
    FILES.protect(htmlOutput, before, after);
    if (jsonOutput != null) {
      FILES.protect(jsonOutput, before, after);
      if (SafeFiles.aliases(htmlOutput, jsonOutput))
        throw new IOException("HTML and JSON comparison outputs must be distinct");
    }
    var baselineReport = ReportWriter.read(before);
    var candidateReport = ReportWriter.read(after);
    try (var htmlStage = FILES.stage(htmlOutput);
        var jsonStage = jsonOutput == null ? null : FILES.stage(jsonOutput)) {
      ReportWriter.comparison(htmlStage.temporary, baselineReport, candidateReport);
      if (jsonStage != null)
        ReportWriter.comparisonJson(jsonStage.temporary, baselineReport, candidateReport);
      htmlStage.publish();
      if (jsonStage != null) jsonStage.publish();
    }
  }
}
