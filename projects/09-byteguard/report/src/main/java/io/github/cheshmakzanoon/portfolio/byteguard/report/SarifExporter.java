package io.github.cheshmakzanoon.portfolio.byteguard.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SarifExporter {
  private final ObjectMapper mapper = new ObjectMapper();

  public ObjectNode report(Analyzer.Result analysis, List<Finding> findings, Set<String> baseline) {
    ObjectNode root = mapper.createObjectNode();
    root.put("version", "2.1.0");
    root.put(
        "$schema", "https://schemastore.azurewebsites.net/schemas/json/sarif-2.1.0-rtm.5.json");
    ObjectNode run = root.putArray("runs").addObject();
    ObjectNode driver = run.putObject("tool").putObject("driver");
    driver.put("name", "ByteGuard");
    driver.put("version", RuleModels.VERSION);
    driver.put("informationUri", "https://github.com/cheshmakzanoon-ops/Java-portfolio");
    ArrayNode rules = driver.putArray("rules");
    for (String id : new TreeSet<>(new RuleModels().rules())) {
      ObjectNode rule = rules.addObject();
      rule.put("id", id);
      rule.put("name", id.replace("-", ""));
      rule.putObject("shortDescription").put("text", description(id));
      rule.putObject("defaultConfiguration").put("level", "warning");
      rule.putObject("properties").put("ruleVersion", RuleModels.VERSION);
    }
    ArrayNode results = run.putArray("results");
    for (Finding finding : findings) {
      ObjectNode node = results.addObject();
      node.put("ruleId", finding.rule());
      node.put("level", finding.severity());
      node.putObject("message")
          .put(
              "text",
              finding.sourceDescriptor()
                  + " reaches "
                  + finding.sinkDescriptor()
                  + " under model "
                  + finding.ruleVersion());
      node.putObject("partialFingerprints").put("byteguard/v1", finding.fingerprint());
      node.put("baselineState", baseline.contains(finding.fingerprint()) ? "unchanged" : "new");
      node.putArray("locations").add(location(finding.sinkLocation()));
      ArrayNode threads = node.putArray("codeFlows").addObject().putArray("threadFlows");
      ArrayNode steps = threads.addObject().putArray("locations");
      steps.addObject().set("location", location(finding.sourceLocation()));
      for (String call : finding.callPath()) {
        ObjectNode step = steps.addObject();
        ObjectNode loc = step.putObject("location");
        loc.putObject("message").put("text", call);
      }
      steps.addObject().set("location", location(finding.sinkLocation()));
      ObjectNode props = node.putObject("properties");
      props.put("confidence", finding.confidence());
      props.put("provenanceTruncated", finding.provenanceTruncated());
      props.put("sourceBytecode", finding.source().toString());
      props.put("sinkBytecode", finding.sink().toString());
      props.set("modelLimitations", mapper.valueToTree(finding.limitations()));
    }
    ObjectNode invocation = run.putArray("invocations").addObject();
    invocation.put("executionSuccessful", !analysis.analysis().partial());
    ArrayNode notifications = invocation.putArray("toolExecutionNotifications");
    for (String diagnostic : analysis.analysis().diagnostics()) {
      ObjectNode notification = notifications.addObject();
      notification.put("level", diagnostic.startsWith("RESOURCE_LIMIT") ? "error" : "warning");
      notification.putObject("message").put("text", diagnostic);
    }
    ObjectNode props = run.putObject("properties");
    props.put("partial", analysis.analysis().partial());
    props.put("inputClasses", analysis.manifest().classes().size());
    props.put("parsedClasses", analysis.classes().size());
    props.put("workUnits", analysis.analysis().work());
    props.put(
        "applicationClasses",
        analysis.manifest().classes().values().stream().filter(c -> c.application()).count());
    props.put(
        "unknownCalls",
        analysis.analysis().diagnostics().stream()
            .filter(d -> d.startsWith("UNKNOWN_CALL"))
            .count());
    props.set("unfinishedFrontier", mapper.valueToTree(analysis.analysis().unfinished()));
    return root;
  }

  public ObjectNode failure(String diagnostic, boolean partial) {
    ObjectNode root = mapper.createObjectNode();
    root.put("version", "2.1.0");
    ObjectNode run = root.putArray("runs").addObject();
    run.putObject("tool")
        .putObject("driver")
        .put("name", "ByteGuard")
        .put("version", RuleModels.VERSION);
    run.putArray("results");
    ObjectNode invocation = run.putArray("invocations").addObject();
    invocation.put("executionSuccessful", false);
    invocation
        .putArray("toolExecutionNotifications")
        .addObject()
        .put("level", "error")
        .putObject("message")
        .put("text", diagnostic);
    ObjectNode props = run.putObject("properties");
    props.put("partial", partial);
    props
        .putArray("unfinishedFrontier")
        .add(partial ? "input enumeration unfinished" : "invalid input/configuration");
    return root;
  }

  private String description(String rule) {
    return switch (rule) {
      case "BG-SQL-TEXT" -> "External input in SQL command text";
      case "BG-SHELL-COMMAND" -> "External input in modeled shell -c command";
      default -> "External input in filesystem access path";
    };
  }

  private ObjectNode location(Finding.Location loc) {
    ObjectNode node = mapper.createObjectNode();
    ObjectNode physical = node.putObject("physicalLocation");
    physical.putObject("artifactLocation").put("uri", loc.uri());
    if (loc.line() > 0) physical.putObject("region").put("startLine", loc.line());
    node.putArray("logicalLocations").addObject().put("fullyQualifiedName", loc.method());
    node.putObject("properties").put("instructionOrdinal", loc.instruction());
    return node;
  }

  public void write(Path path, Object value) throws IOException {
    OwnedOutput.write(
        path,
        temporary -> mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value));
  }

  public Set<String> baseline(Path path) throws IOException {
    return SarifBaseline.read(path);
  }
}
