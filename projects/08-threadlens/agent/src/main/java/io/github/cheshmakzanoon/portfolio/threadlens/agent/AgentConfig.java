package io.github.cheshmakzanoon.portfolio.threadlens.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict YAML subset: scalar keys plus inline package and optional exact method-name lists. */
public record AgentConfig(
    List<String> allowPackages,
    List<String> methodNames,
    int maxMethods,
    int queueCapacity,
    Path outputDirectory) {
  public AgentConfig {
    allowPackages = List.copyOf(allowPackages);
    methodNames = List.copyOf(methodNames);
    if (allowPackages.isEmpty()
        || allowPackages.size() > 32
        || methodNames.size() > 100
        || maxMethods < 1
        || maxMethods > 10_000
        || queueCapacity < 2
        || queueCapacity > 65_536)
      throw new IllegalArgumentException("invalid agent limits/allowlist");
    for (String item : allowPackages)
      if (!item.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+"))
        throw new IllegalArgumentException("explicit application package required");
    for (String method : methodNames)
      if (!method.matches("[a-zA-Z_$][\\w$]*"))
        throw new IllegalArgumentException("invalid exact method name");
    if (outputDirectory == null) throw new IllegalArgumentException("outputDirectory required");
  }

  public static AgentConfig read(String argument) throws IOException {
    if (argument == null || !argument.startsWith("config=") || argument.length() > 4096)
      throw new IllegalArgumentException("use config=path");
    Path path = Path.of(argument.substring(7));
    if (Files.size(path) > 16_384) throw new IOException("configuration exceeds 16 KiB");
    List<String> packages = new ArrayList<>(), methods = new ArrayList<>();
    int limit = 100, capacity = 65_536;
    Path output = Path.of("threadlens-output");
    Set<String> seen = new java.util.HashSet<>();
    for (String line : Files.readAllLines(path)) {
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] pair = line.split(":", 2);
      if (pair.length != 2 || !seen.add(pair[0].strip()))
        throw new IllegalArgumentException("invalid or duplicate configuration key");
      String key = pair[0].strip(), value = pair[1].strip();
      switch (key) {
        case "allowPackages" -> packages.addAll(inlineList(value));
        case "methodNames" -> methods.addAll(inlineList(value));
        case "maxMethods" -> limit = Integer.parseInt(value);
        case "queueCapacity" -> capacity = Integer.parseInt(value);
        case "outputDirectory" -> output = Path.of(unquote(value));
        default -> throw new IllegalArgumentException("unknown configuration key");
      }
    }
    return new AgentConfig(packages, methods, limit, capacity, output);
  }

  private static List<String> inlineList(String value) {
    if (!value.startsWith("[") || !value.endsWith("]"))
      throw new IllegalArgumentException("inline list required");
    String contents = value.substring(1, value.length() - 1).strip();
    if (contents.isEmpty()) return List.of();
    return java.util.Arrays.stream(contents.split(","))
        .map(String::strip)
        .map(AgentConfig::unquote)
        .toList();
  }

  private static String unquote(String value) {
    if (value.length() > 1
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))))
      return value.substring(1, value.length() - 1);
    return value;
  }
}
