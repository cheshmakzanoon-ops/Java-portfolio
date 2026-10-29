package io.github.cheshmakzanoon.portfolio.faultline.cli;

import com.fasterxml.jackson.databind.*;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.report.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
    name = "faultline",
    mixinStandardHelpOptions = true,
    version = "Faultline 1.0.0",
    subcommands = {Faultline.Scan.class, Faultline.Rules.class})
public final class Faultline implements Runnable {
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    System.exit(execute(args, System.out, System.err));
  }

  public static int execute(String[] args, PrintStream out, PrintStream err) {
    CommandLine cmd = new CommandLine(new Faultline());
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    cmd.setExecutionExceptionHandler(
        (ex, c, parse) -> {
          err.println("Faultline analysis failure: " + ex.getMessage());
          return 2;
        });
    return cmd.execute(args);
  }

  @Command(
      name = "rules",
      mixinStandardHelpOptions = true,
      description = "List the sources, sinks and sanitizers a scan would use")
  public static final class Rules implements Callable<Integer> {
    @Option(names = "--rules", description = "Rule model document; the built-in model if omitted")
    Path rules;

    @Spec Model.CommandSpec spec;

    public Integer call() {
      try {
        RuleModels models = rules == null ? new RuleModels() : RuleModelLoader.read(rules);
        spec.commandLine()
            .getOut()
            .print(ModelListing.describe(models, rules == null ? "built-in" : rules.toString()));
        spec.commandLine().getOut().flush();
        return 0;
      } catch (IOException | IllegalArgumentException e) {
        spec.commandLine()
            .getErr()
            .println("Faultline INVALID/FAILED: " + e.getMessage() + "; exit 2");
        return 2;
      }
    }
  }

  @Command(
      name = "scan",
      mixinStandardHelpOptions = true,
      description = "Scan explicit local classfiles without executing target code")
  public static final class Scan implements Callable<Integer> {
    @Option(names = "--input", required = true)
    List<Path> input;

    @Option(names = "--classpath", split = ",")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--target-release", defaultValue = "25")
    int release;

    @Option(names = "--rules")
    Path rules;

    @Option(names = "--format", defaultValue = "sarif")
    String format;

    @Option(names = "--out", required = true)
    Path out;

    @Option(names = "--baseline")
    Path baseline;

    @Option(names = "--work-budget", defaultValue = "50000000")
    long work;

    @Option(names = "--deadline-ms", defaultValue = "120000")
    long deadline;

    @Option(names = "--dispatch-limit", defaultValue = "32")
    int dispatch;

    @Option(names = "--provenance-depth", defaultValue = "5")
    int provenance;

    @Option(names = "--explain-dir")
    Path explainDir;

    @Option(names = "--explain-method")
    String explainMethod;

    @Spec Model.CommandSpec spec;

    public Integer call() {
      boolean outputSafe = false;
      try {
        OutputGuard.validate(out, explainDir, input, classpath, rules, baseline);
        outputSafe = true;
        if (!Set.of("sarif", "json", "text").contains(format))
          throw new IllegalArgumentException("format must be sarif, json or text");
        RuleModels models = rules == null ? new RuleModels() : RuleModelLoader.read(rules);
        SarifExporter exporter = new SarifExporter();
        Set<String> previous = baseline == null ? Set.of() : exporter.baseline(baseline);
        Analyzer.Result analysis =
            new Analyzer()
                .scan(
                    input,
                    classpath,
                    release,
                    new InputLimits(25_000, 200L * 1024 * 1024, 8 * 1024 * 1024, 100, deadline),
                    new AnalysisBudget(work, deadline, dispatch, provenance),
                    models);
        if (explainMethod != null && explainDir == null)
          throw new IllegalArgumentException("--explain-method requires --explain-dir");
        if (explainDir != null) new AnalysisExplainer().write(analysis, explainDir, explainMethod);
        List<Finding> findings = new FindingReporter().findings(analysis);
        Object report =
            format.equals("text")
                ? TextReport.render(
                    findings,
                    previous,
                    analysis.analysis().partial(),
                    analysis.analysis().unfinished(),
                    analysis.analysis().diagnostics().size())
                : format.equals("sarif")
                    ? exporter.report(analysis, findings, previous)
                    : Map.of(
                        "schemaVersion",
                        1,
                        "partial",
                        analysis.analysis().partial(),
                        "findings",
                        findings,
                        "diagnostics",
                        analysis.analysis().diagnostics(),
                        "unfinishedFrontier",
                        analysis.analysis().unfinished(),
                        "workUnits",
                        analysis.analysis().work());
        if (report instanceof String text) exporter.writeText(out, text);
        else exporter.write(out, report);
        long fresh = findings.stream().filter(f -> !previous.contains(f.fingerprint())).count();
        int code = analysis.analysis().partial() ? 3 : fresh > 0 ? 1 : 0;
        spec.commandLine()
            .getErr()
            .println(
                "Faultline "
                    + (code == 3 ? "PARTIAL" : "COMPLETE")
                    + ": "
                    + findings.size()
                    + " findings, "
                    + fresh
                    + " new, "
                    + analysis.analysis().diagnostics().size()
                    + " diagnostics; exit "
                    + code);
        for (String d : analysis.analysis().diagnostics()) spec.commandLine().getErr().println(d);
        if (code == 3)
          spec.commandLine()
              .getErr()
              .println("unfinished frontier: " + analysis.analysis().unfinished());
        return code;
      } catch (IOException | IllegalArgumentException e) {
        boolean partial = e.getMessage() != null && e.getMessage().contains("deadline exceeded");
        int code = partial ? 3 : 2;
        spec.commandLine()
            .getErr()
            .println(
                "Faultline "
                    + (partial ? "PARTIAL" : "INVALID/FAILED")
                    + ": "
                    + e.getMessage()
                    + "; exit "
                    + code);
        if (!outputSafe) return code;
        try {
          SarifExporter exporter = new SarifExporter();
          if (format.equals("text"))
            exporter.writeText(out, TextReport.failure(e.getMessage(), partial));
          else
            exporter.write(
                out,
                format.equals("sarif")
                    ? exporter.failure(e.getMessage(), partial)
                    : Map.of(
                        "schemaVersion",
                        1,
                        "partial",
                        partial,
                        "diagnostic",
                        e.getMessage(),
                        "exitCode",
                        code));
        } catch (IOException ignored) {
          spec.commandLine().getErr().println("Unable to write failure report");
        }
        return code;
      }
    }
  }
}
