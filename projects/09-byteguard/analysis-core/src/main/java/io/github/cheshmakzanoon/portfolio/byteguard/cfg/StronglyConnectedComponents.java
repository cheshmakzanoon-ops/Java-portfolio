package io.github.cheshmakzanoon.portfolio.byteguard.cfg;

import java.util.*;

/** Iterative Kosaraju traversal avoids call-stack failure on hostile deep call graphs. */
public final class StronglyConnectedComponents {
  public <T extends Comparable<T>> List<List<T>> find(Map<T, ? extends Collection<T>> graph) {
    return find(graph, () -> {});
  }

  public <T extends Comparable<T>> List<List<T>> find(
      Map<T, ? extends Collection<T>> graph, Runnable tick) {
    TreeSet<T> vertices = new TreeSet<>(graph.keySet());
    for (Collection<T> edges : graph.values()) vertices.addAll(edges);
    Map<T, SortedSet<T>> reverse = new TreeMap<>();
    for (T v : vertices) reverse.put(v, new TreeSet<>());
    for (var edge : graph.entrySet())
      for (T w : edge.getValue()) {
        tick.run();
        reverse.get(w).add(edge.getKey());
      }
    Set<T> seen = new HashSet<>();
    List<T> finished = new ArrayList<>();
    for (T root : vertices) {
      if (!seen.add(root)) continue;
      Deque<T> nodes = new ArrayDeque<>();
      Deque<Iterator<T>> edges = new ArrayDeque<>();
      nodes.push(root);
      edges.push(neighbors(graph, root).iterator());
      while (!nodes.isEmpty()) {
        tick.run();
        if (edges.peek().hasNext()) {
          T w = edges.peek().next();
          if (seen.add(w)) {
            nodes.push(w);
            edges.push(neighbors(graph, w).iterator());
          }
        } else {
          finished.add(nodes.pop());
          edges.pop();
        }
      }
    }
    seen.clear();
    List<List<T>> result = new ArrayList<>();
    Collections.reverse(finished);
    for (T root : finished) {
      if (!seen.add(root)) continue;
      List<T> component = new ArrayList<>();
      Deque<T> pending = new ArrayDeque<>();
      pending.push(root);
      while (!pending.isEmpty()) {
        tick.run();
        T v = pending.pop();
        component.add(v);
        for (T w : reverse.get(v)) if (seen.add(w)) pending.push(w);
      }
      Collections.sort(component);
      result.add(List.copyOf(component));
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  private <T extends Comparable<T>> SortedSet<T> neighbors(
      Map<T, ? extends Collection<T>> graph, T v) {
    Collection<T> edges = graph.get(v);
    return edges == null ? Collections.emptySortedSet() : new TreeSet<>(edges);
  }
}
