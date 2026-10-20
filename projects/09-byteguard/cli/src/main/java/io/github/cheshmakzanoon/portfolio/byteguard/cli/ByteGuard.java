package io.github.cheshmakzanoon.portfolio.byteguard.cli;

import com.fasterxml.jackson.databind.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.report.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
    name = "byteguard",
    mixinStandardHelpOptions = true,
    version = "ByteGuard 1.0.0",
    subcommands = {ByteGuard.Scan.class})
public final class ByteGuard implements Runnable {
  public void run() {
    CommandLine.usage(this, System.out);
  }

  public static void main(String[] args) {
    System.exit(execute(args, System.out, System.err));
  }

  public static int execute(String[] args, PrintStream out, PrintStream err) {
    CommandLine cmd = new CommandLine(new ByteGuard());
    cmd.setOut(new PrintWriter(out, true));
    cmd.setErr(new PrintWriter(err, true));
    cmd.setExecutionExceptionHandler(
        (ex, c, parse) -> {
          err.println("ByteGuard analysis failure: " + ex.getMessage());
          return 2;
        });
    return cmd.execute(args);
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
        if (!Set.of("sarif", "json").contains(format))
          throw new IllegalArgumentException("format must be sarif or json");
        RuleModels models = rules == null ? new RuleModels() : validateRules(rules);
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
            format.equals("sarif")
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
        exporter.write(out, report);
        long fresh = findings.stream().filter(f -> !previous.contains(f.fingerprint())).count();
        int code = analysis.analysis().partial() ? 3 : fresh > 0 ? 1 : 0;
        spec.commandLine()
            .getErr()
            .println(
                "ByteGuard "
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
                "ByteGuard "
                    + (partial ? "PARTIAL" : "INVALID/FAILED")
                    + ": "
                    + e.getMessage()
                    + "; exit "
                    + code);
        if (!outputSafe) return code;
        try {
          SarifExporter exporter = new SarifExporter();
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

    private RuleModels validateRules(Path path) throws IOException {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("rule model must be a regular file");
      if (Files.size(path) > 1024 * 1024) throw new IOException("rule model exceeds 1 MiB");
      byte[] bytes;
      try (InputStream input = Files.newInputStream(path)) {
        bytes = input.readNBytes(1024 * 1024 + 1);
      }
      if (bytes.length > 1024 * 1024) throw new IOException("rule model exceeds 1 MiB");
      JsonNode node = new ObjectMapper().readTree(bytes);
      if (!RuleModels.VERSION.equals(node.path("version").asText())
          || !node.path("model").asText().equals("byteguard-core"))
        throw new IOException("unsupported versioned rule model");
      Set<String> ids = new TreeSet<>();
      for (JsonNode r : node.path("rules")) ids.add(r.path("id").asText());
      if (!ids.equals(new RuleModels().rules()))
        throw new IOException("model must declare exactly supported rule IDs");
      Set<String> sources = new TreeSet<>();
      for (JsonNode source : node.path("sources")) sources.add(source.asText());
      Map<String, RuleModels.Sink> sinks = new TreeMap<>();
      for (JsonNode sink : node.path("sinks")) {
        String descriptor = sink.path("descriptor").asText();
        if (!descriptor.contains("(")) throw new IOException("exact sink descriptor required");
        sinks.put(
            descriptor,
            new RuleModels.Sink(
                sink.path("rule").asText(),
                sink.path("mask").asInt(),
                sink.path("argument").asInt()));
      }
      Map<String, Integer> sanitizers = new TreeMap<>();
      for (JsonNode sanitizer : node.path("sanitizers"))
        sanitizers.put(
            sanitizer.path("descriptor").asText(), sanitizer.path("contextMask").asInt());
      return new RuleModels(sources, sinks, sanitizers);
    }
  }
}
