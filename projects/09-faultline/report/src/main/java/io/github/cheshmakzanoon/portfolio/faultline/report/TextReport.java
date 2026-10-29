package io.github.cheshmakzanoon.portfolio.faultline.report;

import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.util.*;

/**
 * Plain-text rendering of a scan for people at a terminal or in a code-review comment. It adds no
 * information beyond the SARIF/JSON report, never fabricates a line number, and is fully
 * deterministic: findings are ordered by rule, sink location, then fingerprint.
 */
public final class TextReport {
  private TextReport() {}

  public static String render(
      List<Finding> findings,
      Set<String> baseline,
      boolean partial,
      List<String> unfinished,
      int diagnostics) {
    List<Finding> ordered =
        findings.stream()
            .sorted(
                Comparator.comparing(Finding::rule)
                    .thenComparing(f -> f.sinkLocation().uri())
                    .thenComparingInt(f -> f.sinkLocation().line())
                    .thenComparing(Finding::sinkDescriptor)
                    .thenComparing(Finding::fingerprint))
            .toList();
    long fresh = ordered.stream().filter(f -> !baseline.contains(f.fingerprint())).count();
    StringBuilder out = new StringBuilder();
    out.append("Faultline ")
        .append(RuleModels.VERSION)
        .append(partial ? " PARTIAL: " : " COMPLETE: ")
        .append(ordered.size())
        .append(ordered.size() == 1 ? " finding (" : " findings (")
        .append(fresh)
        .append(" new, ")
        .append(ordered.size() - fresh)
        .append(" unchanged), ")
        .append(diagnostics)
        .append(" diagnostics\n");
    for (Finding f : ordered) {
      out.append('\n')
          .append(baseline.contains(f.fingerprint()) ? "[unchanged] " : "[new] ")
          .append(f.rule())
          .append("  ")
          .append(f.severity())
          .append('/')
          .append(f.confidence())
          .append("  ")
          .append(f.fingerprint(), 0, 12)
          .append('\n');
      out.append("  source  ")
          .append(where(f.sourceLocation()))
          .append("  ")
          .append(f.sourceDescriptor())
          .append('\n');
      out.append("  sink    ")
          .append(where(f.sinkLocation()))
          .append("  ")
          .append(f.sinkDescriptor())
          .append('\n');
      if (!f.callPath().isEmpty())
        out.append("  via     ")
            .append(String.join(" > ", f.callPath()))
            .append(f.provenanceTruncated() ? " > ..." : "")
            .append('\n');
      if (f.confidence().equals("low"))
        out.append("  note    the value also passes through code the model treats as unknown\n");
    }
    if (partial) {
      out.append("\nCoverage is incomplete; absence of findings below proves nothing.\n");
      for (String item : unfinished) out.append("  unfinished: ").append(item).append('\n');
    }
    return out.toString();
  }

  public static String failure(String message, boolean partial) {
    return "Faultline " + (partial ? "PARTIAL" : "INVALID/FAILED") + ": " + message + "\n";
  }

  private static String where(Finding.Location location) {
    return location.uri() + (location.line() > 0 ? ":" + location.line() : "");
  }
}
