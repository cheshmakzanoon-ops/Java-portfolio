package io.github.cheshmakzanoon.portfolio.backtrace.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.StackAggregator.Flame;
import java.io.IOException;
import java.util.*;

/**
 * Interchange formats for the sampled stack tree: collapsed stacks ({@code frame;frame;frame N},
 * the input of flamegraph.pl, inferno and speedscope), the differential variant ({@code ... A B})
 * and a speedscope sampled profile.
 *
 * <p>The tree is a bounded prefix trie, so a sample can be recorded in a prefix only (the node
 * limit was reached below it, or the sample had no stack). Those samples are exported as an
 * explicit {@value #TRUNCATED} or {@value #UNATTRIBUTED} leaf instead of being dropped or credited
 * to a frame that was not the leaf, so every export sums to exactly the report's sample count.
 */
public final class FlameExport {
  public static final String TRUNCATED = "[truncated]";
  public static final String UNATTRIBUTED = "[unattributed]";

  /** One distinct stack, root to leaf, with the number of samples whose leaf it was. */
  public record Stack(List<String> frames, long weight) {
    public Stack {
      frames = List.copyOf(frames);
    }
  }

  private FlameExport() {}

  /** Distinct stacks in depth-first, name-sorted order; weights sum to {@code root.inclusive()}. */
  public static List<Stack> stacks(Flame root) {
    List<Stack> stacks = new ArrayList<>();
    collect(root, new ArrayList<>(), true, stacks);
    return List.copyOf(stacks);
  }

  private static void collect(Flame node, List<String> path, boolean isRoot, List<Stack> out) {
    if (!isRoot) path.add(node.frame());
    if (node.self() > 0) out.add(new Stack(path, node.self()));
    long children = 0;
    for (Flame child : node.children()) {
      children = Math.addExact(children, child.inclusive());
      collect(child, path, false, out);
    }
    long residual = node.inclusive() - node.self() - children;
    if (residual > 0) {
      List<String> leaf = new ArrayList<>(path);
      leaf.add(isRoot ? UNATTRIBUTED : TRUNCATED);
      out.add(new Stack(leaf, residual));
    }
    if (!isRoot) path.removeLast();
  }

  /** Collapsed stacks with readable frame names; stacks that print identically are summed. */
  public static String folded(Flame root) {
    StringBuilder out = new StringBuilder();
    for (var line : displayWeights(root).entrySet())
      out.append(line.getKey()).append(' ').append(line.getValue()).append('\n');
    return out.toString();
  }

  /** {@code stack baseline candidate} lines, the input of a differential flame graph. */
  public static String foldedDifferential(Flame baseline, Flame candidate) {
    Map<String, long[]> merged = new TreeMap<>();
    displayWeights(baseline)
        .forEach((stack, weight) -> merged.computeIfAbsent(stack, k -> new long[2])[0] = weight);
    displayWeights(candidate)
        .forEach((stack, weight) -> merged.computeIfAbsent(stack, k -> new long[2])[1] = weight);
    StringBuilder out = new StringBuilder();
    merged.forEach(
        (stack, weights) ->
            out.append(stack)
                .append(' ')
                .append(weights[0])
                .append(' ')
                .append(weights[1])
                .append('\n'));
    return out.toString();
  }

  private static Map<String, Long> displayWeights(Flame root) {
    Map<String, Long> weights = new TreeMap<>();
    for (Stack stack : stacks(root)) {
      StringJoiner joined = new StringJoiner(";");
      for (String frame : stack.frames()) joined.add(displayName(frame));
      weights.merge(joined.toString(), stack.weight(), Math::addExact);
    }
    return weights;
  }

  /**
   * A speedscope "sampled" profile. Frame names are the report's exact names, so unlike the folded
   * text this export loses nothing.
   */
  public static String speedscope(String name, Flame root) throws IOException {
    Map<String, Integer> index = new LinkedHashMap<>();
    List<List<Integer>> samples = new ArrayList<>();
    List<Long> weights = new ArrayList<>();
    long total = 0;
    for (Stack stack : stacks(root)) {
      List<Integer> frames = new ArrayList<>();
      for (String frame : stack.frames())
        frames.add(index.computeIfAbsent(frame, k -> index.size()));
      samples.add(frames);
      weights.add(stack.weight());
      total = Math.addExact(total, stack.weight());
    }
    List<Map<String, String>> frames = new ArrayList<>();
    index.keySet().forEach(frame -> frames.add(Map.of("name", frame)));
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("type", "sampled");
    profile.put("name", name);
    profile.put("unit", "none");
    profile.put("startValue", 0);
    profile.put("endValue", total);
    profile.put("samples", samples);
    profile.put("weights", weights);
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("$schema", "https://www.speedscope.app/file-format-schema.json");
    document.put("shared", Map.of("frames", frames));
    document.put("profiles", List.of(profile));
    document.put("name", name);
    document.put("activeProfileIndex", 0);
    document.put("exporter", "Backtrace");
    return new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(document)
        + "\n";
  }

  /**
   * {@code com.x.Foo.bar(Ljava/lang/String;[I)V} becomes {@code
   * com.x.Foo.bar(java.lang.String,int[])}. The return type is dropped, so bridge methods that
   * differ only by it print identically. Any name that is not a JVM method signature is kept, with
   * the characters collapsed-stack consumers treat specially ({@code ;}, whitespace, control
   * characters) replaced.
   */
  public static String displayName(String frame) {
    return readable(generatedClassIdentity(frame));
  }

  /**
   * Hidden lambda classes, reflection accessors and proxies get a different number in every JVM
   * ({@code Main$$Lambda.0x000000008a048538}). Left in, the same code in a baseline and a candidate
   * recording would never line up in a differential graph, so the text exports drop the per-JVM
   * part. The speedscope export keeps the exact name.
   */
  private static String generatedClassIdentity(String frame) {
    return frame
        .replaceAll("\\$\\$Lambda(?:\\$\\d+)?[./]0x[0-9a-fA-F]+", "\\$\\$Lambda")
        .replaceAll("GeneratedMethodAccessor\\d+", "GeneratedMethodAccessor")
        .replaceAll("GeneratedConstructorAccessor\\d+", "GeneratedConstructorAccessor")
        .replaceAll("\\$Proxy\\d+", "\\$Proxy");
  }

  private static String readable(String frame) {
    int open = frame.indexOf('(');
    int close = open < 0 ? -1 : frame.indexOf(')', open);
    if (open > 0 && close > open) {
      String parameters = parameters(frame.substring(open + 1, close));
      if (parameters != null) return clean(frame.substring(0, open) + "(" + parameters + ")");
    }
    return clean(frame);
  }

  /** A JVM method has at most 255 parameter slots; more than that is not a descriptor. */
  private static final int MAXIMUM_PARAMETERS = 255;

  private static String parameters(String descriptor) {
    StringJoiner joined = new StringJoiner(",");
    int i = 0, count = 0;
    while (i < descriptor.length()) {
      if (++count > MAXIMUM_PARAMETERS) return null;
      int dimensions = 0;
      while (i < descriptor.length() && descriptor.charAt(i) == '[') {
        dimensions++;
        i++;
      }
      if (i >= descriptor.length()) return null;
      String type;
      char c = descriptor.charAt(i);
      if (c == 'L') {
        int end = descriptor.indexOf(';', i);
        if (end < 0 || end == i + 1) return null;
        type = descriptor.substring(i + 1, end).replace('/', '.');
        i = end + 1;
      } else {
        type =
            switch (c) {
              case 'B' -> "byte";
              case 'C' -> "char";
              case 'D' -> "double";
              case 'F' -> "float";
              case 'I' -> "int";
              case 'J' -> "long";
              case 'S' -> "short";
              case 'Z' -> "boolean";
              default -> null;
            };
        if (type == null) return null;
        i++;
      }
      joined.add(type + "[]".repeat(dimensions));
    }
    return joined.toString();
  }

  private static String clean(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == ';') out.append(':');
      else if (Character.isWhitespace(c) || Character.isISOControl(c)) out.append('_');
      else out.append(c);
    }
    return out.toString();
  }
}
