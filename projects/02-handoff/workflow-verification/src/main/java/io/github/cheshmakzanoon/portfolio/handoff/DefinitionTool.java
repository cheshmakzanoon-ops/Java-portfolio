package io.github.cheshmakzanoon.portfolio.handoff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cheshmakzanoon.portfolio.handoff.DefinitionAnalyzer.Analysis;
import io.github.cheshmakzanoon.portfolio.handoff.DefinitionAnalyzer.Finding;
import io.github.cheshmakzanoon.portfolio.handoff.DefinitionAnalyzer.Severity;
import io.github.cheshmakzanoon.portfolio.handoff.WorkflowSimulator.Run;
import io.github.cheshmakzanoon.portfolio.handoff.WorkflowSimulator.Scenario;
import io.github.cheshmakzanoon.portfolio.handoff.WorkflowSimulator.Step;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Design-time commands that need neither PostgreSQL nor a running service: review a definition,
 * draw it, and play it against scripted outcomes through the real interpreter.
 *
 * <pre>
 * lint     definition.json [--format text|json]     exit 0 clean, 1 warnings, 2 invalid
 * graph    definition.json [--format mermaid|dot]
 * simulate definition.json [scenario.json] [--format text|json]
 * </pre>
 */
public final class DefinitionTool {
  static final long MAXIMUM_FILE_BYTES = 1024 * 1024;

  static final String USAGE =
      "lint definition.json [--format text|json] | graph definition.json [--format mermaid|dot]"
          + " | simulate definition.json [scenario.json] [--format text|json]";

  /** Scenario file: every field optional, unknown fields rejected. */
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record ScenarioFile(
      String payload,
      Map<String, String> context,
      List<String> fail,
      Map<String, String> unknown,
      Map<String, String> signals,
      Integer cancelAfterEvents) {
    Scenario toScenario() {
      return new Scenario(
          payload,
          context,
          fail == null ? Set.of() : new HashSet<>(fail),
          unknown,
          signals,
          cancelAfterEvents);
    }
  }

  private static final ObjectMapper READER =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private static final ObjectMapper WRITER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private DefinitionTool() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      if (args.length < 2) throw new IllegalArgumentException(USAGE);
      List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
      String format = null;
      int formatAt = rest.indexOf("--format");
      if (formatAt >= 0) {
        if (formatAt + 1 >= rest.size()) throw new IllegalArgumentException(USAGE);
        format = rest.get(formatAt + 1);
        rest.subList(formatAt, formatAt + 2).clear();
      }
      return switch (args[0]) {
        case "lint" -> lint(single(rest), format == null ? "text" : format, out);
        case "graph" -> graph(single(rest), format == null ? "mermaid" : format, out);
        case "simulate" -> simulate(rest, format == null ? "text" : format, out);
        default -> throw new IllegalArgumentException(USAGE);
      };
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      err.println("handoff: " + failure.getMessage());
      return 2;
    }
  }

  private static Path single(List<String> rest) {
    if (rest.size() != 1) throw new IllegalArgumentException(USAGE);
    return Path.of(rest.getFirst());
  }

  private static int lint(Path file, String format, PrintStream out) throws IOException {
    Analysis analysis = DefinitionAnalyzer.analyze(definition(file));
    switch (format) {
      case "text" -> out.print(lintText(analysis));
      case "json" -> out.println(WRITER.writeValueAsString(lintJson(analysis)));
      default -> throw new IllegalArgumentException("lint format must be text or json");
    }
    if (!analysis.valid()) return 2;
    return analysis.count(Severity.WARNING) > 0 ? 1 : 0;
  }

  private static int graph(Path file, String format, PrintStream out) throws IOException {
    Definition definition = definition(file).validate();
    switch (format) {
      case "mermaid" -> out.print(DefinitionGraph.mermaid(definition));
      case "dot" -> out.print(DefinitionGraph.dot(definition));
      default -> throw new IllegalArgumentException("graph format must be mermaid or dot");
    }
    return 0;
  }

  private static int simulate(List<String> rest, String format, PrintStream out)
      throws IOException {
    if (rest.isEmpty() || rest.size() > 2) throw new IllegalArgumentException(USAGE);
    Definition definition = definition(Path.of(rest.getFirst()));
    Scenario scenario =
        rest.size() == 2
            ? read(Path.of(rest.get(1)), ScenarioFile.class).toScenario()
            : Scenario.happyPath();
    Run run = new WorkflowSimulator().simulate(definition, scenario);
    switch (format) {
      case "text" -> out.print(simulationText(definition, run));
      case "json" -> out.println(WRITER.writeValueAsString(simulationJson(definition, run)));
      default -> throw new IllegalArgumentException("simulate format must be text or json");
    }
    return 0;
  }

  private static Definition definition(Path file) throws IOException {
    return read(file, Definition.class);
  }

  private static <T> T read(Path file, Class<T> type) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("not a regular file: " + file.getFileName());
    if (Files.size(file) > MAXIMUM_FILE_BYTES)
      throw new IOException(file.getFileName() + " exceeds 1 MiB");
    try {
      return READER.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
    } catch (com.fasterxml.jackson.core.JacksonException malformed) {
      throw new IOException(
          file.getFileName()
              + " is not a valid "
              + type.getSimpleName()
              + ": "
              + malformed.getOriginalMessage());
    }
  }

  static String lintText(Analysis analysis) {
    StringBuilder out =
        new StringBuilder(analysis.name()).append(" v").append(analysis.version()).append('\n');
    var m = analysis.metrics();
    if (analysis.valid())
      out.append(
          String.format(
              "  %d nodes, %d edges: %d activities (%d external effects, %d compensable), %d signals, %d timers, %d branches, %d joins%n"
                  + "  longest chain %d, widest fan-out %d, widest fan-in %d, longest wait %d s%n",
              m.nodes(),
              m.edges(),
              m.activities(),
              m.effects(),
              m.compensable(),
              m.signals(),
              m.timers(),
              m.branches(),
              m.joins(),
              m.longestChain(),
              m.maxFanOut(),
              m.maxFanIn(),
              m.longestWaitSeconds()));
    for (Finding f : analysis.findings())
      out.append(
          String.format(
              "%-7s %-30s %s%s%n",
              f.severity(), f.code(), f.node() == null ? "" : "[" + f.node() + "] ", f.message()));
    out.append(
        String.format(
            "%d error(s), %d warning(s), %d note(s)%n",
            analysis.count(Severity.ERROR),
            analysis.count(Severity.WARNING),
            analysis.count(Severity.INFO)));
    return out.toString();
  }

  static Map<String, Object> lintJson(Analysis analysis) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", analysis.name());
    out.put("version", analysis.version());
    out.put("valid", analysis.valid());
    out.put("metrics", analysis.metrics());
    List<Map<String, Object>> findings = new ArrayList<>();
    for (Finding f : analysis.findings()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("severity", f.severity());
      item.put("code", f.code());
      item.put("node", f.node());
      item.put("message", f.message());
      findings.add(item);
    }
    out.put("findings", findings);
    return out;
  }

  static String simulationText(Definition definition, Run run) {
    StringBuilder out =
        new StringBuilder(definition.name())
            .append(" v")
            .append(definition.version())
            .append(" (hash ")
            .append(definition.contentHash(), 0, 12)
            .append(", semantics ")
            .append(Interpreter.SEMANTICS)
            .append(")\n");
    for (Step step : run.steps())
      out.append(
          String.format(
              "  t+%-6s #%-3d %-17s %s%s%n",
              step.at().toSeconds() + "s",
              step.sequence(),
              step.type(),
              step.task().isEmpty() ? "" : step.task() + "  ",
              step.value()));
    out.append("final state: ").append(run.state()).append('\n');
    out.append("node outcomes:");
    run.outcomes()
        .forEach((node, outcome) -> out.append(' ').append(node).append('=').append(outcome));
    out.append('\n');
    if (!run.compensationOrder().isEmpty())
      out.append("compensated (in order): ")
          .append(String.join(", ", run.compensationOrder()))
          .append('\n');
    if (!run.blockedOn().isEmpty())
      out.append("waiting on: ").append(String.join(", ", run.blockedOn())).append('\n');
    if (!run.uncompensatedEffects().isEmpty())
      out.append("effects completed whose compensation did not run: ")
          .append(String.join(", ", run.uncompensatedEffects()))
          .append('\n');
    return out.toString();
  }

  static Map<String, Object> simulationJson(Definition definition, Run run) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", definition.name());
    out.put("version", definition.version());
    out.put("definitionHash", definition.contentHash());
    out.put("semantics", Interpreter.SEMANTICS);
    out.put("finalState", run.state());
    List<Map<String, Object>> steps = new ArrayList<>();
    for (Step step : run.steps()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("sequence", step.sequence());
      item.put("type", step.type());
      item.put("task", step.task());
      item.put("value", step.value());
      item.put("atSeconds", step.at().toSeconds());
      steps.add(item);
    }
    out.put("steps", steps);
    out.put("outcomes", run.outcomes());
    out.put("compensationOrder", run.compensationOrder());
    out.put("uncompensatedEffects", run.uncompensatedEffects());
    out.put("blockedOn", run.blockedOn());
    return out;
  }
}
