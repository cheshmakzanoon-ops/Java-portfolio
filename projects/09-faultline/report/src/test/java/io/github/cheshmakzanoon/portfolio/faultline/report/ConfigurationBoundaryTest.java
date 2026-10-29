package io.github.cheshmakzanoon.portfolio.faultline.report;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationBoundaryTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String FINGERPRINT = "a".repeat(64);
  @TempDir Path temp;

  private ObjectNode baseline() {
    ObjectNode root = JSON.createObjectNode();
    root.put("version", "2.1.0");
    ObjectNode run = root.putArray("runs").addObject();
    run.putObject("tool").putObject("driver").put("name", "Faultline").put("version", "1.0.0");
    run.putArray("results")
        .addObject()
        .put("ruleId", "BG-SQL-TEXT")
        .putObject("partialFingerprints")
        .put("faultline/v1", FINGERPRINT);
    return root;
  }

  private Path write(String name, String content) throws IOException {
    Path path = temp.resolve(name);
    Files.writeString(path, content);
    return path;
  }

  private Set<String> read(ObjectNode root) throws IOException {
    return new SarifExporter().baseline(write("baseline.sarif", root.toString()));
  }

  private ObjectNode run(ObjectNode root) {
    return (ObjectNode) root.withArray("runs").get(0);
  }

  private ObjectNode result(ObjectNode root) {
    return (ObjectNode) run(root).withArray("results").get(0);
  }

  private void refused(Consumer<ObjectNode> edit) {
    ObjectNode root = baseline();
    edit.accept(root);
    assertThrows(IOException.class, () -> read(root));
  }

  @Test
  void exactIdentitiesRemainImmutableAndDeduplicatedAcrossRuns() throws Exception {
    ObjectNode root = baseline();
    root.withArray("runs").add(run(root).deepCopy());
    Set<String> found = read(root);
    assertEquals(Set.of(FINGERPRINT), found);
    assertThrows(UnsupportedOperationException.class, () -> found.add("b".repeat(64)));
  }

  @Test
  void otherToolsAndVersionsCannotSupplyFaultlineSuppressionIdentities() {
    refused(root -> run(root).withObject("/tool/driver").put("name", "AnotherAnalyzer"));
    refused(root -> run(root).withObject("/tool/driver").put("version", "2.0.0"));
    refused(root -> run(root).withObject("/tool/driver").remove("name"));
    refused(root -> run(root).remove("tool"));
    refused(root -> root.put("version", "1.0.0"));
    refused(root -> root.put("version", 2.1));
    refused(root -> root.remove("version"));
  }

  @Test
  void baselinesFromEverySupportedModelVersionKeepTheirIdentities() throws Exception {
    for (String version : RuleModels.SUPPORTED_VERSIONS) {
      ObjectNode root = baseline();
      run(root).withObject("/tool/driver").put("version", version);
      assertEquals(Set.of(FINGERPRINT), read(root), version);
    }
    for (String rule : RuleModels.CONTEXTS.keySet()) {
      ObjectNode root = baseline();
      result(root).put("ruleId", rule);
      assertEquals(Set.of(FINGERPRINT), read(root), rule);
    }
    refused(root -> run(root).withObject("/tool/driver").put("version", "1.0.1"));
    refused(root -> run(root).withObject("/tool/driver").put("version", 110));
    refused(root -> result(root).put("ruleId", "BG-UNKNOWN"));
  }

  @Test
  void malformedRunsAndResultsCannotBecomeAnEmptyAcceptedBaseline() {
    refused(root -> root.remove("runs"));
    refused(root -> root.putNull("runs"));
    refused(root -> root.putObject("runs"));
    refused(root -> root.putArray("runs"));
    refused(root -> root.putArray("runs").add("not a run"));
    refused(root -> run(root).remove("results"));
    refused(root -> run(root).putNull("results"));
    refused(root -> run(root).putObject("results"));
    refused(root -> run(root).putArray("results").add(1));
    refused(root -> run(root).putArray("results").addNull());
  }

  @Test
  void everyResultMustCarryASupportedRuleAndExactFingerprint() {
    refused(root -> result(root).remove("ruleId"));
    refused(root -> result(root).put("ruleId", "BG-CUSTOM"));
    refused(root -> result(root).put("ruleId", 1));
    refused(root -> result(root).remove("partialFingerprints"));
    refused(root -> result(root).putArray("partialFingerprints").add(FINGERPRINT));
    for (String invalid :
        List.of("*", "**/*.java", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
      refused(root -> result(root).withObject("/partialFingerprints").put("faultline/v1", invalid));
    }
    refused(root -> result(root).withObject("/partialFingerprints").put("faultline/v1", 1));
  }

  @Test
  void partialBaselineDoesNotChangeItsExactIdentityMeaning() throws Exception {
    ObjectNode root = baseline();
    run(root).putObject("properties").put("partial", true);
    run(root).putArray("invocations").addObject().put("executionSuccessful", false);
    assertEquals(Set.of(FINGERPRINT), read(root));
    // This operation only extracts identities. It does not certify complete prior coverage.
    assertEquals(Set.of(), read(new SarifExporter().failure("unfinished", true)));
  }

  @Test
  void completeEmptyExporterReportIsAnExplicitEmptyBaseline() throws Exception {
    ObjectNode root = baseline();
    run(root).putArray("results");
    assertEquals(Set.of(), read(root));
  }

  @Test
  void duplicateKeysAndTrailingDocumentsAreNeverLastValueWins() throws Exception {
    for (String document :
        List.of(
            baseline() + " {}",
            baseline() + " null",
            baseline() + " false",
            baseline().toString().replace("\"Faultline\"", "\"Other\",\"name\":\"Faultline\""),
            baseline().toString().replace("\"2.1.0\"", "\"1.0.0\",\"version\":\"2.1.0\""))) {
      Path path = write("ambiguous.sarif", document);
      assertThrows(IOException.class, () -> new SarifExporter().baseline(path));
    }
  }

  @Test
  void rootMustBeAnObjectWithBoundedNestingAndTokenLengths() throws Exception {
    for (String document :
        List.of(
            "",
            "null",
            "[]",
            "1",
            "true",
            "\"text\"",
            "{\"secret\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}",
            "{\"secret\":" + "1".repeat(40) + "}")) {
      Path path = write("bounded.json", document);
      assertThrows(IOException.class, () -> BoundedJson.object(path, 100000, "configuration"));
    }
  }

  @Test
  void byteBudgetUsesEncodedLengthAndAcceptsItsExactBoundary() throws Exception {
    String document = "{\"label\":\"آزمون 日本語\"}";
    Path path = write("unicode.json", document);
    int bytes = document.getBytes(StandardCharsets.UTF_8).length;
    JsonNode parsed = BoundedJson.object(path, bytes, "configuration");
    assertEquals("آزمون 日本語", parsed.get("label").textValue());
    assertThrows(IOException.class, () -> BoundedJson.object(path, bytes - 1, "configuration"));
    assertThrows(
        IllegalArgumentException.class, () -> BoundedJson.object(path, 0, "configuration"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BoundedJson.object(path, Integer.MAX_VALUE, "configuration"));
  }

  @Test
  void longGeneratedJvmIdentityStringsRemainCompatibleWithinTheDocumentBudget() throws Exception {
    ObjectNode root = baseline();
    String identity =
        "L"
            + "p".repeat(60000)
            + "/Owner."
            + "m".repeat(60000)
            + "(L"
            + "Argument".repeat(7000)
            + ";)Ljava/lang/String;";
    result(root).putObject("message").put("text", identity + " reaches " + identity);
    result(root).putObject("properties").put("sourceBytecode", identity);
    assertEquals(Set.of(FINGERPRINT), read(root));
  }

  @Test
  void directoriesMissingFilesAndSymbolicInputsCannotSupplyConfiguration() throws Exception {
    assertThrows(IOException.class, () -> BoundedJson.object(temp, 100, "configuration"));
    assertThrows(
        IOException.class, () -> BoundedJson.object(temp.resolve("absent"), 100, "configuration"));
    Path actual = write("real.json", "{}");
    Path symbolic = temp.resolve("alias.json");
    Files.createSymbolicLink(symbolic, actual);
    assertThrows(IOException.class, () -> BoundedJson.object(symbolic, 100, "configuration"));
    assertEquals(JSON.createObjectNode(), BoundedJson.object(actual, 100, "configuration"));
  }

  @Test
  void parseDiagnosticsDoNotQuoteSensitiveDocumentValues() throws Exception {
    Path path = write("private.json", "{\"secret\":PRIVATE_CONFIGURATION_VALUE}");
    IOException error =
        assertThrows(IOException.class, () -> BoundedJson.object(path, 1000, "configuration"));
    assertFalse(error.getMessage().contains("PRIVATE_CONFIGURATION_VALUE"));
    assertTrue(error.getMessage().contains("configuration"));
  }

  @Test
  void excessiveRunCountsAreRefusedBeforeIdentityExtraction() {
    refused(
        root -> {
          ObjectNode first = run(root).deepCopy();
          ArrayNode runs = root.withArray("runs");
          for (int i = 1; i < 65; i++) runs.add(first.deepCopy());
        });
  }
}
