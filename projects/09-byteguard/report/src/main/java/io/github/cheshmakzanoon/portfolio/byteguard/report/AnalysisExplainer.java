package io.github.cheshmakzanoon.portfolio.byteguard.report;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.cfg.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit opt-in IR/frame disclosure, separate from the small stable SARIF contract. */
public final class AnalysisExplainer {
  public void write(Analyzer.Result analysis, Path directory, String selectedMethod)
      throws IOException {
    Files.createDirectories(directory);
    SarifExporter exporter = new SarifExporter();
    List<Object> methods = new ArrayList<>();
    for (var c : analysis.classes().values())
      for (MethodIr method : c.methods().values()) {
        if (selectedMethod != null && !selectedMethod.equals(method.id)) continue;
        var result = analysis.analysis().methods().get(method.id);
        if (result == null) {
          methods.add(
              Map.of("method", method.id, "coverage", "unfinished", "instructions", List.of()));
          continue;
        }
        String filename = BoundedJarReader.sha(method.id.getBytes(StandardCharsets.UTF_8)) + ".dot";
        var graph = new ControlFlowBuilder().build(method);
        Files.writeString(directory.resolve(filename), graph.dot());
        List<Object> instructions = new ArrayList<>();
        for (int i = 0; i < method.instructions.size(); i++) {
          Map<String, Object> instruction = new LinkedHashMap<>();
          instruction.put("id", method.at(i).toString());
          instruction.put("opcode", method.instructions.get(i).getOpcode());
          instruction.put("line", method.line(i));
          instruction.put("normalSuccessors", graph.normal().get(i));
          instruction.put("exceptionalSuccessors", graph.exceptional().get(i));
          if (result != null && result.frames().get(i) != null) {
            instruction.put("locals", result.frames().get(i).locals);
            instruction.put("stack", result.frames().get(i).stack);
          } else instruction.put("reachable", false);
          instructions.add(instruction);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", method.id);
        details.put("cfg", filename);
        details.put("instructions", instructions);
        details.put(
            "summary",
            analysis.analysis().summaries().getOrDefault(method.id, MethodSummary.empty()));
        methods.add(details);
      }
    if (selectedMethod != null && methods.isEmpty())
      throw new IOException("requested explanation method not found");
    Map<String, Object> contexts = new TreeMap<>();
    analysis
        .analysis()
        .contexts()
        .forEach(
            (key, value) -> {
              if (selectedMethod == null
                  || key.callee().equals(selectedMethod)
                  || key.callSite().method().equals(selectedMethod))
                contexts.put(key.toString(), value);
            });
    exporter.write(
        directory.resolve("analysis.json"),
        Map.of(
            "schemaVersion",
            1,
            "methods",
            methods,
            "callSiteContexts",
            contexts,
            "partial",
            analysis.analysis().partial(),
            "unfinishedFrontier",
            analysis.analysis().unfinished()));
  }
}
