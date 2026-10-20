package io.github.cheshmakzanoon.portfolio.byteguard.interprocedural;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.dataflow.*;
import java.util.*;

public record MethodSummary(
    Taint returns,
    SortedMap<String, Flow> flows,
    SortedMap<Integer, Taint> elementWrites,
    SortedMap<String, Taint> fieldWrites) {
  public MethodSummary(Taint returns, SortedMap<String, Flow> flows) {
    this(returns, flows, new TreeMap<>(), new TreeMap<>());
  }

  public MethodSummary(
      Taint returns, SortedMap<String, Flow> flows, SortedMap<Integer, Taint> elementWrites) {
    this(returns, flows, elementWrites, new TreeMap<>());
  }

  public record Flow(
      String rule,
      int mask,
      InstructionId sink,
      String descriptor,
      Taint taint,
      List<String> callPath,
      boolean truncated) {
    public Flow {
      callPath = List.copyOf(callPath);
    }
  }

  public static MethodSummary empty() {
    return new MethodSummary(Taint.BOTTOM, new TreeMap<>());
  }

  public MethodSummary {
    flows = Collections.unmodifiableSortedMap(new TreeMap<>(flows));
    elementWrites = Collections.unmodifiableSortedMap(new TreeMap<>(elementWrites));
    fieldWrites = Collections.unmodifiableSortedMap(new TreeMap<>(fieldWrites));
  }

  public MethodSummary join(MethodSummary other) {
    TreeMap<String, Flow> all = new TreeMap<>(flows);
    other.flows.forEach(
        (k, v) ->
            all.merge(
                k,
                v,
                (a, b) ->
                    new Flow(
                        a.rule,
                        a.mask,
                        a.sink,
                        a.descriptor,
                        a.taint.join(b.taint),
                        choose(a.callPath, b.callPath),
                        a.truncated || b.truncated)));
    TreeMap<Integer, Taint> effects = new TreeMap<>(elementWrites);
    other.elementWrites.forEach((k, v) -> effects.merge(k, v, Taint::join));
    TreeMap<String, Taint> fieldEffects = new TreeMap<>(fieldWrites);
    other.fieldWrites.forEach((k, v) -> fieldEffects.merge(k, v, Taint::join));
    return new MethodSummary(returns.join(other.returns), all, effects, fieldEffects);
  }

  public MethodSummary instantiate(List<Taint> arguments) {
    return instantiate(arguments, null);
  }

  public MethodSummary instantiate(List<Taint> arguments, String method) {
    TreeMap<String, Flow> instantiated = new TreeMap<>();
    flows.forEach(
        (key, flow) ->
            instantiated.put(
                key,
                new Flow(
                    flow.rule(),
                    flow.mask(),
                    flow.sink(),
                    flow.descriptor(),
                    flow.taint().substitute(arguments, method),
                    flow.callPath(),
                    flow.truncated())));
    TreeMap<Integer, Taint> effects = new TreeMap<>();
    elementWrites.forEach((key, taint) -> effects.put(key, taint.substitute(arguments, method)));
    TreeMap<String, Taint> fieldEffects = new TreeMap<>();
    fieldWrites.forEach((k, v) -> fieldEffects.put(k, v.substitute(arguments, method)));
    return new MethodSummary(
        returns.substitute(arguments, method), instantiated, effects, fieldEffects);
  }

  private static List<String> choose(List<String> a, List<String> b) {
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      int comparison = a.get(i).compareTo(b.get(i));
      if (comparison != 0) return comparison < 0 ? a : b;
    }
    return a.size() <= b.size() ? a : b;
  }
}
