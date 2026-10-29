package io.github.cheshmakzanoon.portfolio.faultline.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cheshmakzanoon.portfolio.faultline.report.BoundedJson;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validate operator intent before constructing executable source/sink/sanitizer contracts. */
final class RuleModelLoader {
  private static final int MAX_ENTRIES = 4096;
  private static final Set<String> ROOT_FIELDS =
      Set.of("model", "version", "rules", "sources", "sinks", "sanitizers", "libraryModels");
  private static final Set<String> LIBRARY_MODELS =
      Set.of(
          "StringConcatFactory",
          "LambdaMetafactory",
          "StringBuilder",
          "collections",
          "URI",
          "shell",
          "unknown");

  private RuleModelLoader() {}

  static RuleModels read(Path path) throws IOException {
    JsonNode root = BoundedJson.object(path, 1024 * 1024, "rule model");
    fields(root, ROOT_FIELDS, "rule model");
    String model = text(root, "model"), version = text(root, "version");
    if (!model.equals("faultline-core") || !RuleModels.SUPPORTED_VERSIONS.contains(version))
      throw invalid("unsupported model or version");
    // A model document declares exactly the rules of its own version: 1.0.0 documents keep
    // working unchanged and cannot reach for rules that did not exist when they were written.
    Set<String> supported = RuleModels.rulesFor(version);

    Set<String> rules = new HashSet<>();
    for (JsonNode node : array(root, "rules")) {
      fields(node, Set.of("id", "contextMask", "sink", "safe"), "rule declaration");
      String id = text(node, "id");
      Integer expected = supported.contains(id) ? RuleModels.CONTEXTS.get(id) : null;
      if (expected == null || integer(node, "contextMask") != expected)
        throw invalid("rule declaration/context mismatch");
      if (!rules.add(id)) throw invalid("duplicate rule declaration");
      optionalText(node, "sink");
      optionalText(node, "safe");
    }
    if (!rules.equals(supported)) throw invalid("declare every supported rule exactly once");

    Set<String> sources = new TreeSet<>();
    for (JsonNode node : array(root, "sources")) {
      if (!node.isTextual() || node.textValue().isBlank())
        throw invalid("source must be an exact descriptor string");
      if (!sources.add(node.textValue())) throw invalid("duplicate source descriptor");
    }

    Map<String, RuleModels.Sink> sinks = new TreeMap<>();
    for (JsonNode node : array(root, "sinks")) {
      fields(node, Set.of("descriptor", "rule", "mask", "argument"), "sink declaration");
      String descriptor = text(node, "descriptor");
      RuleModels.Sink sink =
          new RuleModels.Sink(text(node, "rule"), integer(node, "mask"), integer(node, "argument"));
      if (!supported.contains(sink.id())) throw invalid("sink rule is not part of this model");
      if (sinks.putIfAbsent(descriptor, sink) != null) throw invalid("duplicate sink descriptor");
    }

    Map<String, Integer> sanitizers = new TreeMap<>();
    for (JsonNode node : array(root, "sanitizers")) {
      fields(
          node,
          Set.of("descriptor", "contextMask", "trustedImplementation"),
          "sanitizer declaration");
      String descriptor = text(node, "descriptor");
      int context = integer(node, "contextMask");
      if (supported.stream().noneMatch(rule -> RuleModels.CONTEXTS.get(rule) == context))
        throw invalid("sanitizer context is not part of this model");
      if (sanitizers.putIfAbsent(descriptor, context) != null)
        throw invalid("duplicate sanitizer descriptor");
      optionalText(node, "trustedImplementation");
    }

    if (root.has("libraryModels")) {
      JsonNode libraries = root.get("libraryModels");
      fields(libraries, LIBRARY_MODELS, "library model documentation");
      for (String name : LIBRARY_MODELS) optionalText(libraries, name);
    }
    try {
      return new RuleModels(sources, sinks, sanitizers);
    } catch (IllegalArgumentException invalidContract) {
      throw invalid("invalid exact source/sink/sanitizer contract");
    }
  }

  private static JsonNode array(JsonNode root, String name) throws IOException {
    JsonNode value = root.get(name);
    if (value == null || !value.isArray() || value.size() > MAX_ENTRIES)
      throw invalid(name + " must be an array of at most " + MAX_ENTRIES + " entries");
    return value;
  }

  private static void fields(JsonNode node, Set<String> allowed, String description)
      throws IOException {
    if (!node.isObject()) throw invalid(description + " must be an object");
    var names = node.fieldNames();
    while (names.hasNext()) {
      if (!allowed.contains(names.next())) throw invalid(description + " has an unsupported field");
    }
  }

  private static String text(JsonNode node, String field) throws IOException {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank())
      throw invalid(field + " must be a nonempty string");
    return value.textValue();
  }

  private static void optionalText(JsonNode node, String field) throws IOException {
    if (node.has(field)) text(node, field);
  }

  private static int integer(JsonNode node, String field) throws IOException {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
      throw invalid(field + " must be an exact 32-bit integer");
    return value.intValue();
  }

  private static IOException invalid(String reason) {
    return new IOException("invalid rule model: " + reason);
  }
}
