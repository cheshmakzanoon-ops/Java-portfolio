package io.github.cheshmakzanoon.portfolio.byteguard.dataflow;

import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.AnalysisBudget;
import java.util.*;

/** Weak field-sensitive heap with bounded, flow-insensitive reference alias closure. */
public final class HeapSummary {
  private final SortedMap<String, Taint> fields = new TreeMap<>();
  private final Map<String, SortedSet<String>> aliases = new TreeMap<>();
  private final AnalysisBudget budget;
  private boolean changed;

  public HeapSummary() {
    this(AnalysisBudget.defaults());
  }

  public HeapSummary(AnalysisBudget budget) {
    this.budget = budget;
  }

  public Taint read(String field) {
    return fields.getOrDefault(field, Taint.BOTTOM);
  }

  public void write(String field, Taint value) {
    Taint old = read(field), joined = old.join(value);
    if (!old.equals(joined)) {
      fields.put(field, joined);
      changed = true;
    }
  }

  public void alias(String reference, Collection<String> others) {
    for (String other : others) {
      budget.spend();
      if (reference.equals(other)) continue;
      if (aliases.computeIfAbsent(reference, k -> new TreeSet<>()).add(other)) {
        budget.reserveFact();
        aliases.computeIfAbsent(other, k -> new TreeSet<>()).add(reference);
        changed = true;
      }
    }
  }

  public SortedSet<String> references(Collection<String> roots) {
    TreeSet<String> visited = new TreeSet<>(roots);
    ArrayDeque<String> todo = new ArrayDeque<>(roots);
    while (!todo.isEmpty()) {
      budget.spend();
      String next = todo.removeFirst();
      if (visited.size() > 4096)
        throw new AnalysisBudget.Exhausted("heap alias budget exceeded (4096 per value)");
      for (String adjacent : aliases.getOrDefault(next, Collections.emptySortedSet()))
        if (visited.add(adjacent)) todo.addLast(adjacent);
    }
    return visited;
  }

  public AbstractValue readField(String field, AbstractValue.Kind kind) {
    return new AbstractValue(
        kind,
        read(field),
        kind == AbstractValue.Kind.REF ? new TreeSet<>(Set.of("field:" + field)) : new TreeSet<>(),
        null);
  }

  public void writeField(String field, AbstractValue value) {
    write(field, elements(value));
    if (value.kind() == AbstractValue.Kind.REF) alias("field:" + field, value.references());
  }

  public Taint elements(AbstractValue object) {
    Taint t = object.taint();
    for (String ref : references(object.references())) t = t.join(read(ref + "[]"));
    return t;
  }

  public void addElement(AbstractValue object, Taint value) {
    for (String ref : object.references()) write(ref + "[]", value);
  }

  public boolean consumeChanged() {
    boolean c = changed;
    changed = false;
    return c;
  }

  public Map<String, Taint> snapshot() {
    return Collections.unmodifiableMap(fields);
  }
}
