package io.github.cheshmakzanoon.portfolio.faultline.bytecode;

import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.AnalysisBudget;
import java.util.*;
import org.objectweb.asm.Opcodes;

/**
 * Linear-size direct hierarchy index. Traversals are iterative and share the global scan budget.
 */
public final class TypeHierarchy {
  private final Map<String, ClassFactsReader.ClassFacts> classes;
  private final Map<String, SortedSet<String>> children = new HashMap<>();
  private final Map<String, List<String>> targetCache = new HashMap<>();
  private final AnalysisBudget budget;

  public TypeHierarchy(Map<String, ClassFactsReader.ClassFacts> classes) {
    this(classes, AnalysisBudget.defaults());
  }

  public TypeHierarchy(Map<String, ClassFactsReader.ClassFacts> classes, AnalysisBudget budget) {
    this.classes = Map.copyOf(classes);
    this.budget = budget;
    for (var c : classes.values()) {
      budget.spend();
      if (c.superName() != null)
        children.computeIfAbsent(c.superName(), k -> new TreeSet<>()).add(c.name());
      for (String parent : c.interfaces()) {
        budget.spend();
        children.computeIfAbsent(parent, k -> new TreeSet<>()).add(c.name());
      }
    }
  }

  public boolean subtype(String type, String parent) {
    if (type.equals(parent) || parent.equals("java/lang/Object")) return true;
    Set<String> seen = new HashSet<>();
    Deque<String> pending = new ArrayDeque<>();
    pending.add(type);
    while (!pending.isEmpty()) {
      budget.spend();
      String name = pending.removeFirst();
      if (!seen.add(name)) continue;
      if (name.equals(parent)) return true;
      var c = classes.get(name);
      if (c != null) {
        if (c.superName() != null) pending.addLast(c.superName());
        pending.addAll(c.interfaces());
      }
    }
    return false;
  }

  public List<String> targets(String owner, String name, String desc, boolean dynamic) {
    String key = owner + "." + name + desc + ":" + dynamic;
    return targetCache.computeIfAbsent(
        key,
        k -> {
          TreeSet<String> ids = new TreeSet<>();
          Set<String> visited = new HashSet<>();
          Deque<String> candidates = new ArrayDeque<>();
          candidates.add(owner);
          while (!candidates.isEmpty() && ids.size() <= budget.dispatchTargets) {
            budget.spend();
            String candidate = candidates.removeFirst();
            if (!visited.add(candidate)) continue;
            resolve(candidate, name, desc, ids);
            if (dynamic)
              candidates.addAll(children.getOrDefault(candidate, Collections.emptySortedSet()));
          }
          return List.copyOf(ids);
        });
  }

  private void resolve(String candidate, String name, String desc, Set<String> ids) {
    ClassFactsReader.ClassFacts c = classes.get(candidate);
    Set<String> seen = new HashSet<>();
    List<String> interfaces = new ArrayList<>();
    while (c != null && seen.add(c.name())) {
      budget.spend();
      String id = c.name() + "." + name + desc;
      MethodIr method = c.methods().get(id);
      if (method != null) {
        if ((method.node.access & Opcodes.ACC_ABSTRACT) == 0) ids.add(id);
        if ((method.node.access & Opcodes.ACC_ABSTRACT) == 0) return;
      }
      interfaces.addAll(c.interfaces());
      c = classes.get(c.superName());
    }
    Deque<String> pending = new ArrayDeque<>(interfaces);
    while (!pending.isEmpty() && ids.size() <= budget.dispatchTargets) {
      budget.spend();
      String iface = pending.removeFirst();
      if (!seen.add(iface)) continue;
      var facts = classes.get(iface);
      if (facts == null) continue;
      String id = iface + "." + name + desc;
      MethodIr method = facts.methods().get(id);
      if (method != null && (method.node.access & Opcodes.ACC_ABSTRACT) == 0) ids.add(id);
      pending.addAll(facts.interfaces());
    }
  }
}
