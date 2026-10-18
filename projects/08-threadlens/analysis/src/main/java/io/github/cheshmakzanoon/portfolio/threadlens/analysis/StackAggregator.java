package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded prefix trie: inclusive counts per prefix; self counts only at the observed leaf. */
public final class StackAggregator {
  public record Flame(String frame, long inclusive, long self, List<Flame> children) {}

  public record MethodSamples(long inclusive, long self) {}

  private static final class Node {
    final String name;
    long inclusive, self;
    final TreeMap<String, Node> children = new TreeMap<>();

    Node(String name) {
      this.name = name;
    }

    Flame snapshot() {
      return new Flame(
          name, inclusive, self, children.values().stream().map(Node::snapshot).toList());
    }
  }

  private final Node root = new Node("[root]");
  private final TreeMap<String, long[]> methods = new TreeMap<>();
  private final int limit;
  private int nodes;
  private long overflow, missingStacks;

  public StackAggregator(int limit) {
    if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("stack node limit");
    this.limit = limit;
  }

  public void accept(List<String> stack) {
    root.inclusive++;
    if (stack.isEmpty()) {
      missingStacks++;
      return;
    }
    Node node = root;
    HashSet<String> seen = new HashSet<>();
    for (int i = 0; i < stack.size(); i++) {
      String frame = stack.get(i);
      if (seen.add(frame)) {
        long[] counts = methods.get(frame);
        if (counts == null && methods.size() < limit) {
          counts = new long[2];
          methods.put(frame, counts);
        }
        if (counts != null) {
          counts[0]++;
          if (i == stack.size() - 1) counts[1]++;
        } else overflow++;
      } else if (i == stack.size() - 1) {
        long[] counts = methods.get(frame);
        if (counts != null) counts[1]++;
      }
      Node next = node.children.get(frame);
      if (next == null) {
        if (nodes >= limit) {
          overflow++;
          return;
        }
        next = new Node(frame);
        node.children.put(frame, next);
        nodes++;
      }
      next.inclusive++;
      node = next;
    }
    node.self++;
  }

  public Flame snapshot() {
    return root.snapshot();
  }

  public Map<String, MethodSamples> methods() {
    Map<String, MethodSamples> result = new TreeMap<>();
    methods.forEach((key, value) -> result.put(key, new MethodSamples(value[0], value[1])));
    return result;
  }

  public long overflow() {
    return overflow;
  }

  public long missingStacks() {
    return missingStacks;
  }

  public int nodes() {
    return nodes;
  }
}
