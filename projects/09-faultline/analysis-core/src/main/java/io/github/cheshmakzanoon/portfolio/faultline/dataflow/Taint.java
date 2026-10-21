package io.github.cheshmakzanoon.portfolio.faultline.dataflow;

import java.util.*;

/** Finite powerset lattice; masks SQL=1,SHELL=2,PATH=4. Unknown is independent of bottom. */
public record Taint(SortedMap<String, Integer> labels, boolean unknown) {
  public static final Taint BOTTOM = new Taint(new TreeMap<>(), false);

  public Taint {
    if (labels.size() > 4096)
      throw new io.github.cheshmakzanoon.portfolio.faultline.interprocedural.AnalysisBudget
          .Exhausted("taint-label budget exceeded (4096 per value)");
    TreeMap<String, Integer> copy = new TreeMap<>();
    labels.forEach(
        (k, v) -> {
          if ((v & 7) != 0) copy.put(k, v & 7);
        });
    labels = Collections.unmodifiableSortedMap(copy);
  }

  public static Taint source(String id) {
    return new Taint(new TreeMap<>(Map.of(id, 7)), false);
  }

  public static Taint unknownValue() {
    return new Taint(new TreeMap<>(), true);
  }

  public Taint join(Taint other) {
    TreeMap<String, Integer> joined = new TreeMap<>(labels);
    other.labels.forEach((k, v) -> joined.merge(k, v, (a, b) -> a | b));
    return new Taint(joined, unknown || other.unknown);
  }

  public Taint sanitize(int context) {
    TreeMap<String, Integer> clean = new TreeMap<>();
    labels.forEach((k, v) -> clean.put(k, v & ~context));
    return new Taint(clean, unknown);
  }

  public static Taint parameter(String method, int index) {
    return parameterScope(
        io.github.cheshmakzanoon.portfolio.faultline.bytecode.MethodScope.of(method), index);
  }

  static Taint parameterScope(String scope, int index) {
    return source("P:" + scope + "#" + index);
  }

  public Taint substitute(List<Taint> arguments) {
    return substitute(arguments, null);
  }

  public Taint substitute(List<Taint> arguments, String method) {
    Taint result = new Taint(new TreeMap<>(), unknown);
    String prefix =
        method == null
            ? "P:"
            : "P:"
                + io.github.cheshmakzanoon.portfolio.faultline.bytecode.MethodScope.of(method)
                + "#";
    for (var e : labels.entrySet()) {
      String suffix = e.getKey().startsWith(prefix) ? e.getKey().substring(prefix.length()) : "";
      if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
        int i = Integer.parseInt(suffix);
        if (i >= arguments.size()) {
          result = result.join(unknownValue());
          continue;
        }
        TreeMap<String, Integer> mapped = new TreeMap<>();
        arguments.get(i).labels.forEach((k, v) -> mapped.put(k, v & e.getValue()));
        result = result.join(new Taint(mapped, arguments.get(i).unknown));
      } else
        result = result.join(new Taint(new TreeMap<>(Map.of(e.getKey(), e.getValue())), false));
    }
    return result;
  }

  public boolean lessOrEqual(Taint other) {
    return join(other).equals(other);
  }

  public boolean hasConcrete(int mask) {
    return labels.entrySet().stream()
        .anyMatch(e -> !e.getKey().startsWith("P:") && (e.getValue() & mask) != 0);
  }
}
