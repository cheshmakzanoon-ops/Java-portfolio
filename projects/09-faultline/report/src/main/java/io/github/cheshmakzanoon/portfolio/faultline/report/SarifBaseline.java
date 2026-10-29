package io.github.cheshmakzanoon.portfolio.faultline.report;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/** Exact finding identities from an explicitly identified Faultline SARIF document. */
final class SarifBaseline {
  private SarifBaseline() {}

  static Set<String> read(Path path) throws IOException {
    JsonNode root = BoundedJson.object(path, 8 * 1024 * 1024, "baseline");
    if (!textEquals(root.get("version"), "2.1.0")) throw invalid("SARIF version must be 2.1.0");
    JsonNode runs = root.get("runs");
    if (runs == null || !runs.isArray() || runs.isEmpty() || runs.size() > 64)
      throw invalid("runs must contain between 1 and 64 Faultline runs");
    Set<String> supportedRules = new RuleModels().rules();
    TreeSet<String> fingerprints = new TreeSet<>();
    for (JsonNode run : runs) {
      if (!run.isObject()) throw invalid("each run must be an object");
      JsonNode driver = run.path("tool").path("driver");
      if (!textEquals(driver.get("name"), "Faultline")
          || driver.get("version") == null
          || !driver.get("version").isTextual()
          || !RuleModels.SUPPORTED_VERSIONS.contains(driver.get("version").textValue()))
        throw invalid("tool name and model version must match Faultline");
      JsonNode results = run.get("results");
      if (results == null || !results.isArray()) throw invalid("results must be an explicit array");
      for (JsonNode result : results) {
        JsonNode rule = result.get("ruleId");
        if (!result.isObject()
            || rule == null
            || !rule.isTextual()
            || !supportedRules.contains(rule.textValue()))
          throw invalid("result must identify a supported rule");
        JsonNode fingerprint = result.path("partialFingerprints").get("faultline/v1");
        if (fingerprint == null
            || !fingerprint.isTextual()
            || !fingerprint.textValue().matches("[0-9a-f]{64}"))
          throw invalid("result must include an exact faultline/v1 fingerprint");
        fingerprints.add(fingerprint.textValue());
      }
    }
    return Set.copyOf(fingerprints);
  }

  private static boolean textEquals(JsonNode node, String expected) {
    return node != null && node.isTextual() && node.textValue().equals(expected);
  }

  private static IOException invalid(String reason) {
    return new IOException("invalid baseline: " + reason);
  }
}
