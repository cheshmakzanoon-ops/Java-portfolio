package io.github.cheshmakzanoon.portfolio.faultline.rules;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.MethodDescriptor;
import java.util.*;

/** Exact descriptors. The model is versioned; names alone never establish sanitization. */
public final class RuleModels {
  private final Set<String> configuredSources;
  private final Map<String, Sink> configuredSinks;
  private final Map<String, Integer> configuredSanitizers;

  public RuleModels() {
    configuredSources = null;
    configuredSinks = null;
    configuredSanitizers = null;
  }

  public RuleModels(Set<String> sources, Map<String, Sink> sinks, Map<String, Integer> sanitizers) {
    configuredSources = Set.copyOf(sources);
    configuredSinks = Map.copyOf(sinks);
    configuredSanitizers = Map.copyOf(sanitizers);
    sources.forEach(RuleModels::valueDescriptor);
    sanitizers.keySet().forEach(RuleModels::valueDescriptor);
    for (var entry : sinks.entrySet()) {
      String descriptor = descriptor(entry.getKey());
      Sink sink = entry.getValue();
      if (sink.argument() >= org.objectweb.asm.Type.getArgumentTypes(descriptor).length)
        throw new IllegalArgumentException("sink argument outside exact descriptor");
      if (sink.mask()
          != switch (sink.id()) {
            case "BG-SQL-TEXT" -> 1;
            case "BG-SHELL-COMMAND" -> 2;
            case "BG-PATH-ACCESS" -> 4;
            default -> 0;
          }) throw new IllegalArgumentException("sink rule/context mismatch");
    }
    for (Sink sink : sinks.values())
      if (!rules().contains(sink.id()) || sink.mask() < 1 || sink.mask() > 7 || sink.argument() < 0)
        throw new IllegalArgumentException("invalid exact sink model");
    for (int context : sanitizers.values())
      if (context != 1 && context != 2 && context != 4)
        throw new IllegalArgumentException("sanitizer must have exactly one context");
  }

  private static String descriptor(String id) {
    int start = id.indexOf('(');
    if (start < 1 || !id.substring(0, start).matches("[A-Za-z0-9_$/]+\\.(?:[A-Za-z0-9_$]+|<init>)"))
      throw new IllegalArgumentException("exact owner.name descriptor required");
    try {
      String descriptor = id.substring(start);
      boolean constructor = id.substring(0, start).endsWith(".<init>");
      MethodDescriptor.parameterSlots(descriptor, !constructor);
      var args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
      var returns = org.objectweb.asm.Type.getReturnType(descriptor);
      if (constructor && returns.getSort() != org.objectweb.asm.Type.VOID)
        throw new IllegalArgumentException("constructor descriptor must return void");
      if (!org.objectweb.asm.Type.getMethodDescriptor(returns, args).equals(descriptor))
        throw new IllegalArgumentException("invalid descriptor");
      return descriptor;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid method descriptor: " + id);
    }
  }

  private static void valueDescriptor(String id) {
    String method = descriptor(id);
    if (org.objectweb.asm.Type.getReturnType(method).getSort() == org.objectweb.asm.Type.VOID)
      throw new IllegalArgumentException("source and sanitizer contracts require a returned value");
  }

  public static final String VERSION = "1.0.0";

  public record Sink(String id, int mask, int argument) {}

  public boolean source(String id) {
    if (configuredSources != null) return configuredSources.contains(id);
    return Set.of(
            "java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;",
            "java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;",
            "java/io/BufferedReader.readLine()Ljava/lang/String;",
            "java/util/Scanner.nextLine()Ljava/lang/String;",
            "java/io/Console.readLine()Ljava/lang/String;")
        .contains(id);
  }

  public Sink sink(String id) {
    if (configuredSinks != null) return configuredSinks.get(id);
    return switch (id) {
      case "java/sql/Statement.execute(Ljava/lang/String;)Z",
          "java/sql/Statement.executeQuery(Ljava/lang/String;)Ljava/sql/ResultSet;",
          "java/sql/Statement.executeUpdate(Ljava/lang/String;)I",
          "java/sql/Connection.prepareStatement(Ljava/lang/String;)Ljava/sql/PreparedStatement;" ->
          new Sink("BG-SQL-TEXT", 1, 0);
      case "java/nio/file/Files.readString(Ljava/nio/file/Path;)Ljava/lang/String;",
          "java/nio/file/Files.readAllBytes(Ljava/nio/file/Path;)[B",
          "java/io/FileInputStream.<init>(Ljava/lang/String;)V" ->
          new Sink("BG-PATH-ACCESS", 4, 0);
      default -> null;
    };
  }

  public int sanitizer(String id) {
    if (configuredSanitizers != null) return configuredSanitizers.getOrDefault(id, 0);
    return switch (id) {
      case "fixtures/Safe.sqlIdentifier(Ljava/lang/String;)Ljava/lang/String;" -> 1;
      case "fixtures/Safe.shellToken(Ljava/lang/String;)Ljava/lang/String;" -> 2;
      case "fixtures/Safe.confinedPath(Ljava/lang/String;)Ljava/lang/String;" -> 4;
      default -> 0;
    };
  }

  public Set<String> rules() {
    return Set.of("BG-SQL-TEXT", "BG-SHELL-COMMAND", "BG-PATH-ACCESS");
  }
}
