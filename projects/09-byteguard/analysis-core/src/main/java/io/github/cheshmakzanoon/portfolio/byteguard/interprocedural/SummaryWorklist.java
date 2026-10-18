package io.github.cheshmakzanoon.portfolio.byteguard.interprocedural;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.cfg.*;
import io.github.cheshmakzanoon.portfolio.byteguard.dataflow.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.util.*;

public final class SummaryWorklist {
  private static final class BoundedDiagnostics extends TreeSet<String> {
    @Override
    public boolean add(String value) {
      if (size() >= 10_000 && !contains(value) && !value.startsWith("RESOURCE_LIMIT"))
        throw new AnalysisBudget.Exhausted("diagnostic budget exceeded (10000)");
      return super.add(value);
    }
  }

  public record Result(
      SortedMap<String, MethodSummary> summaries,
      SortedSet<String> diagnostics,
      boolean partial,
      List<String> unfinished,
      long work,
      Map<String, WorklistSolver.Result> methods,
      List<List<String>> recursiveComponents,
      SortedMap<CallContext, MethodSummary> contexts) {}

  public Result analyze(
      SortedMap<String, ClassFactsReader.ClassFacts> classes,
      AnalysisBudget budget,
      boolean reverse) {
    return analyze(classes, budget, reverse, new RuleModels());
  }

  public Result analyze(
      SortedMap<String, ClassFactsReader.ClassFacts> classes,
      AnalysisBudget budget,
      boolean reverse,
      RuleModels models) {
    TreeMap<String, MethodIr> methods = new TreeMap<>();
    Set<String> application = new HashSet<>();
    classes
        .values()
        .forEach(
            c -> {
              methods.putAll(c.methods());
              if (c.application()) application.addAll(c.methods().keySet());
            });
    TreeMap<String, MethodSummary> summaries = new TreeMap<>();
    methods.forEach(
        (id, method) -> {
          if ((method.node.access
                  & (org.objectweb.asm.Opcodes.ACC_NATIVE | org.objectweb.asm.Opcodes.ACC_ABSTRACT))
              == 0) summaries.put(id, MethodSummary.empty());
        });
    TreeSet<String> diagnostics = new BoundedDiagnostics();
    Map<String, WorklistSolver.Result> results = new TreeMap<>();
    HeapSummary heap = new HeapSummary(budget);
    TreeMap<CallContext, MethodSummary> contexts = new TreeMap<>();
    List<List<String>> components = List.of();
    Deque<String> pending = new ArrayDeque<>();
    String current = "setup";
    boolean partial = false;
    List<String> unfinished = new ArrayList<>();
    try {
      CallResolver resolver = new CallResolver(new TypeHierarchy(classes, budget));
      Map<String, ControlFlowBuilder.Graph> graphs = new TreeMap<>();
      for (var entry : methods.entrySet()) {
        budget.spend();
        graphs.put(entry.getKey(), new ControlFlowBuilder().build(entry.getValue(), budget::spend));
      }
      Map<String, Collection<String>> calls = new TreeMap<>();
      for (MethodIr m : methods.values()) {
        TreeSet<String> targets = new TreeSet<>();
        for (var n : m.instructions) {
          budget.spend();
          if (n instanceof org.objectweb.asm.tree.MethodInsnNode call)
            targets.addAll(resolver.resolve(call.owner, call.name, call.desc, call.getOpcode()));
          if (n instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic
              && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
              && Set.of("metafactory", "altMetafactory").contains(dynamic.bsm.getName()))
            for (Object argument : dynamic.bsmArgs) {
              budget.spend();
              if (argument instanceof org.objectweb.asm.Handle handle) {
                String target = handle.getOwner() + "." + handle.getName() + handle.getDesc();
                if (methods.containsKey(target)) targets.add(target);
              }
            }
        }
        calls.put(m.id, targets);
      }
      Map<String, Set<String>> callers = new TreeMap<>();
      calls.forEach(
          (caller, targets) ->
              targets.forEach(
                  target -> callers.computeIfAbsent(target, k -> new TreeSet<>()).add(caller)));
      components = new StronglyConnectedComponents().find(calls, budget::spend);
      List<String> order = new ArrayList<>();
      for (List<String> component : components) order.addAll(component);
      if (reverse) Collections.reverse(order);
      pending.addAll(order);
      Set<String> queued = new HashSet<>(order);
      while (!pending.isEmpty()) {
        current = pending.removeFirst();
        queued.remove(current);
        MethodIr m = methods.get(current);
        TransferFunction transfer =
            new TransferFunction(
                m, heap, models, resolver, summaries, budget, diagnostics, contexts);
        WorklistSolver.Result result =
            new WorklistSolver().solve(m, graphs.get(current), transfer, budget, reverse);
        results.put(current, result);
        MethodSummary old = summaries.getOrDefault(current, MethodSummary.empty()),
            next = old.join(result.summary());
        boolean heapChanged = heap.consumeChanged();
        if (!old.equals(next)) {
          summaries.put(current, next);
          for (String id : callers.getOrDefault(current, Set.of()))
            if (queued.add(id)) pending.addLast(id);
        }
        if (heapChanged) for (String id : order) if (queued.add(id)) pending.addLast(id);
      }
    } catch (AnalysisBudget.Exhausted exhausted) {
      partial = true;
      diagnostics.add("RESOURCE_LIMIT " + exhausted.getMessage());
      unfinished.add(current);
      if (current.equals("setup")) unfinished.addAll(methods.keySet());
      else unfinished.addAll(pending);
    }
    for (String diagnostic : diagnostics)
      if (diagnostic.startsWith("DISPATCH_LIMIT")
          || diagnostic.startsWith("UNSUPPORTED_BOOTSTRAP")
          || diagnostic.startsWith("UNSUPPORTED_LAMBDA")) {
        partial = true;
        if (unfinished.isEmpty()) unfinished.add(diagnostic);
      }
    TreeMap<String, MethodSummary> app = new TreeMap<>();
    summaries.forEach(
        (k, v) -> {
          if (application.contains(k)) app.put(k, v);
        });
    return new Result(
        app,
        diagnostics,
        partial,
        List.copyOf(unfinished),
        budget.work(),
        Collections.unmodifiableMap(results),
        components,
        Collections.unmodifiableSortedMap(contexts));
  }
}
