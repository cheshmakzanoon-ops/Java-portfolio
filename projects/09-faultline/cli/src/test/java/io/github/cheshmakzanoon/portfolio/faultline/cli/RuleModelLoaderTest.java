package io.github.cheshmakzanoon.portfolio.faultline.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleModelLoaderTest {
  private static final String SOURCE = "example/Input.read()Ljava/lang/String;";
  private static final String SINK = "example/Database.execute(Ljava/lang/String;)V";
  private static final String SANITIZER = "example/Safe.sql(Ljava/lang/String;)Ljava/lang/String;";
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path temp;

  private ObjectNode model() {
    ObjectNode root = JSON.createObjectNode();
    root.put("model", "faultline-core").put("version", RuleModels.VERSION);
    ArrayNode rules = root.putArray("rules");
    rules.addObject().put("id", "BG-SQL-TEXT").put("contextMask", 1);
    rules.addObject().put("id", "BG-SHELL-COMMAND").put("contextMask", 2);
    rules.addObject().put("id", "BG-PATH-ACCESS").put("contextMask", 4);
    root.putArray("sources").add(SOURCE);
    root.putArray("sinks")
        .addObject()
        .put("descriptor", SINK)
        .put("rule", "BG-SQL-TEXT")
        .put("mask", 1)
        .put("argument", 0);
    root.putArray("sanitizers").addObject().put("descriptor", SANITIZER).put("contextMask", 1);
    return root;
  }

  private RuleModels read(ObjectNode document) throws IOException {
    Path path = temp.resolve("rules.json");
    JSON.writeValue(path.toFile(), document);
    return RuleModelLoader.read(path);
  }

  private void refused(Consumer<ObjectNode> edit) {
    ObjectNode root = model();
    edit.accept(root);
    assertThrows(IOException.class, () -> read(root));
  }

  private ObjectNode first(ObjectNode root, String array) {
    return (ObjectNode) root.withArray(array).get(0);
  }

  @Test
  void exactConfiguredDescriptorsAndContextsDriveBehavior() throws Exception {
    RuleModels rules = read(model());
    assertTrue(rules.source(SOURCE));
    assertFalse(rules.source("example/Input.other()Ljava/lang/String;"));
    assertEquals(new RuleModels.Sink("BG-SQL-TEXT", 1, 0), rules.sink(SINK));
    assertEquals(1, rules.sanitizer(SANITIZER));
    assertEquals(0, rules.sanitizer("example/Safe.other(Ljava/lang/String;)Ljava/lang/String;"));
  }

  @Test
  void shippedModelRemainsValidAndPreservesExactContracts() throws Exception {
    RuleModels rules = RuleModelLoader.read(Path.of("../rules/core.yaml"));
    assertTrue(rules.source("java/io/BufferedReader.readLine()Ljava/lang/String;"));
    assertEquals(
        4, rules.sanitizer("fixtures/Safe.confinedPath(Ljava/lang/String;)Ljava/lang/String;"));
    assertEquals(0, rules.sink("java/sql/Statement.execute(Ljava/lang/String;)Z").argument());
  }

  @Test
  void allBehavioralArraysMustBeExplicitRatherThanDefaultingToEmpty() {
    for (String name : List.of("rules", "sources", "sinks", "sanitizers")) {
      refused(root -> root.remove(name));
      refused(root -> root.putNull(name));
      refused(root -> root.putObject(name));
      refused(root -> root.put(name, "[]"));
    }
  }

  @Test
  void unknownFieldsCannotSilentlyDisableAnIntendedContract() {
    refused(root -> root.put("santizers", "misspelled"));
    refused(root -> first(root, "sinks").put("arguments", 0));
    refused(root -> first(root, "sanitizers").put("context", 1));
    refused(root -> first(root, "rules").put("enabled", false));
  }

  @Test
  void duplicateDeclarationsCannotOverwritePriorBehavior() {
    for (String name : List.of("rules", "sources", "sinks", "sanitizers")) {
      refused(root -> root.withArray(name).add(root.withArray(name).get(0).deepCopy()));
    }
    refused(
        root -> {
          ObjectNode sink = first(root, "sinks").deepCopy();
          sink.put("argument", 1);
          root.withArray("sinks").add(sink);
        });
  }

  @Test
  void numericFieldsRejectNullMissingFractionalStringsAndOverflow() {
    for (String array : List.of("rules", "sinks", "sanitizers")) {
      String field = array.equals("sinks") ? "mask" : "contextMask";
      refused(root -> first(root, array).remove(field));
      refused(root -> first(root, array).putNull(field));
      refused(root -> first(root, array).put(field, "1"));
      refused(root -> first(root, array).put(field, 1.9));
      refused(root -> first(root, array).put(field, true));
      refused(root -> first(root, array).put(field, 4294967297L));
    }
    refused(root -> first(root, "sinks").remove("argument"));
    refused(root -> first(root, "sinks").put("argument", "0"));
    refused(root -> first(root, "sinks").put("argument", 0.5));
    refused(root -> first(root, "sinks").put("argument", -1));
    refused(root -> first(root, "sinks").put("argument", 1));
  }

  @Test
  void ruleContextsAndDeclaredRuleSetMustMatchTheSupportedModel() {
    refused(root -> first(root, "rules").put("contextMask", 4));
    refused(root -> first(root, "rules").put("id", "BG-CUSTOM"));
    refused(root -> root.withArray("rules").remove(0));
    refused(root -> first(root, "sinks").put("rule", "BG-PATH-ACCESS"));
    refused(root -> first(root, "sanitizers").put("contextMask", 7));
  }

  @Test
  void documentIdentityCannotBeCoercedOrOmitted() {
    refused(root -> root.put("model", 1));
    refused(root -> root.put("version", 1.0));
    refused(root -> root.put("version", "2.0.0"));
    refused(root -> root.put("model", "faultline-custom"));
    refused(root -> root.remove("version"));
  }

  @Test
  void malformedMethodDescriptorsFailBeforeArrayAllocationOrScanning() {
    for (String value :
        List.of(
            "missing",
            "example/Input.read(V)I",
            "example/Input.read([V)I",
            "example/Input.read()V",
            "example/Input.read()Iextra",
            "example/Input.read(" + "I".repeat(256) + ")I",
            "example/Input.read(" + "[".repeat(256) + "I)I")) {
      refused(root -> root.putArray("sources").add(value));
    }
    refused(
        root ->
            first(root, "sanitizers").put("descriptor", "example/Safe.clean(Ljava/lang/String;)V"));
  }

  @Test
  void constructorsRequireVoidReturnAndReserveTheReceiverSlot() throws Exception {
    refused(root -> root.putArray("sources").add("example/Input.<init>()I"));
    refused(root -> first(root, "sanitizers").put("descriptor", "example/Safe.<init>(I)I"));
    refused(root -> first(root, "sinks").put("descriptor", "example/Sink.<init>(I)I"));
    refused(
        root ->
            first(root, "sinks")
                .put("descriptor", "example/Sink.<init>(" + "I".repeat(255) + ")V"));
    ObjectNode root = model();
    first(root, "sinks").put("descriptor", "example/Sink.<init>(Ljava/lang/String;)V");
    assertNotNull(read(root).sink("example/Sink.<init>(Ljava/lang/String;)V"));
  }

  @Test
  void descriptiveLibraryMetadataCannotIntroduceUnsupportedExecutableModels() throws Exception {
    ObjectNode root = model();
    root.putObject("libraryModels").put("shell", "fixed built-in semantics");
    assertNotNull(read(root));
    refused(node -> node.putObject("libraryModels").put("arbitrary-plugin", "enabled"));
    refused(node -> node.putObject("libraryModels").put("shell", true));
    refused(node -> node.putArray("libraryModels"));
  }

  @Test
  void optionalDocumentationMustRemainText() throws Exception {
    ObjectNode root = model();
    first(root, "rules").put("safe", "an explicit assumption").put("sink", "SQL command text");
    first(root, "sanitizers").put("trustedImplementation", "reviewed separately");
    assertNotNull(read(root));
    refused(node -> first(node, "rules").put("safe", false));
    refused(node -> first(node, "sanitizers").putNull("trustedImplementation"));
  }

  @Test
  void duplicateJsonKeysAndTrailingValuesAreRejectedWithoutLeakingConfiguration() throws Exception {
    Path path = temp.resolve("rules.json");
    for (String document :
        List.of(
            model().toString() + " {}",
            model().toString() + " true",
            model().toString().replace("\"mask\":1", "\"mask\":4,\"mask\":1"),
            "{\"model\":\"confidential-marker\",\"model\":\"faultline-core\"}")) {
      Files.writeString(path, document);
      IOException failure = assertThrows(IOException.class, () -> RuleModelLoader.read(path));
      assertFalse(failure.getMessage().contains("confidential-marker"));
    }
  }

  @Test
  void arrayBudgetsAreEnforcedBeforeContractsAreBuilt() {
    refused(
        root -> {
          ArrayNode values = root.putArray("sources");
          for (int i = 0; i < 4097; i++) values.add("example/Input.read" + i + "()I");
        });
  }
}
