package io.github.cheshmakzanoon.portfolio.durableflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable finite typed graph. Expressions are deliberately not a general language. */
public record LegacyDefinitionV1(String name, int version, List<Node> nodes) {
  public enum Kind {
    ACTIVITY,
    TIMER,
    SIGNAL,
    BRANCH,
    PARALLEL_ALL
  }

  public enum Safety {
    PURE,
    IDEMPOTENT_WITH_KEY,
    NON_IDEMPOTENT
  }

  public enum Type {
    TEXT,
    BOOLEAN,
    INTEGER
  }

  public record Node(
      String id,
      Kind kind,
      List<String> requires,
      String activity,
      Safety safety,
      String compensation,
      Type inputType,
      Type outputType,
      long delaySeconds,
      String signal,
      String expression,
      String when) {
    public Node {
      requires = List.copyOf(requires);
    }
  }

  public static final Set<String> ACTIVITIES =
      Set.of(
          "reserve",
          "charge",
          "ship",
          "release",
          "refund",
          "transcode",
          "thumbnail",
          "publish",
          "unpublish",
          "pure");

  public LegacyDefinitionV1 {
    nodes = List.copyOf(nodes);
  }

  public LegacyDefinitionV1 validate() {
    if (name == null
        || !name.matches("[a-z][a-z0-9-]{0,63}")
        || version < 1
        || nodes.isEmpty()
        || nodes.size() > 128) throw new IllegalArgumentException("definition bounds");
    Map<String, Node> index = new LinkedHashMap<>();
    for (Node n : nodes) {
      if (n.id == null
          || !n.id.matches("[a-z][a-z0-9-]{0,63}")
          || n.id.startsWith("comp-")
          || index.put(n.id, n) != null
          || n.kind == null
          || n.inputType == null
          || n.outputType == null
          || n.requires.size() > 16
          || new HashSet<>(n.requires).size() != n.requires.size())
        throw new IllegalArgumentException("invalid node");
      if (n.kind == Kind.ACTIVITY
          && (n.activity == null
              || !ACTIVITIES.contains(n.activity)
              || n.safety == null
              || n.outputType != Type.TEXT))
        throw new IllegalArgumentException("untrusted activity");
      if (n.compensation != null
          && (n.kind != Kind.ACTIVITY || !ACTIVITIES.contains(n.compensation)))
        throw new IllegalArgumentException("untrusted compensation");
      if (n.delaySeconds < 0 || n.delaySeconds > 604800)
        throw new IllegalArgumentException("timer bound");
      if (n.kind == Kind.BRANCH
          && (n.expression == null
              || !n.expression.matches("input\\.[a-zA-Z][a-zA-Z0-9_]{0,31}==[a-zA-Z0-9_-]{1,64}")
              || n.outputType != Type.BOOLEAN))
        throw new IllegalArgumentException("invalid expression");
      if (n.when != null && !n.when.matches("[a-z][a-z0-9-]{0,63}==(true|false)"))
        throw new IllegalArgumentException("invalid guard");
      if (n.kind == Kind.SIGNAL && (n.signal == null || !n.signal.matches("[a-z][a-z0-9-]{0,63}")))
        throw new IllegalArgumentException("signal name");
    }
    if (nodes.stream().filter(n -> n.requires.isEmpty()).count() != 1)
      throw new IllegalArgumentException("one reachable root required");
    for (Node n : nodes) {
      for (String r : n.requires) {
        Node previous = index.get(r);
        if (previous == null || previous.outputType != n.inputType)
          throw new IllegalArgumentException("missing reference/type mismatch");
      }
      if (n.when != null) {
        String branch = n.when.split("==")[0];
        if (!index.containsKey(branch)
            || index.get(branch).kind != Kind.BRANCH
            || !ancestors(n.id, index, new HashSet<>()).contains(branch))
          throw new IllegalArgumentException("guard must name an ancestor branch");
      }
      visit(n.id, index, new HashSet<>(), new HashSet<>());
    }
    for (Node parent : nodes)
      if (nodes.stream().filter(n -> n.requires().contains(parent.id())).count() > 16)
        throw new IllegalArgumentException("fan-out bound");
    return this;
  }

  private static Set<String> ancestors(String id, Map<String, Node> index, Set<String> seen) {
    if (!seen.add(id)) return seen;
    for (String parent : index.get(id).requires)
      if (index.containsKey(parent)) ancestors(parent, index, seen);
    return seen;
  }

  private static void visit(
      String id, Map<String, Node> index, Set<String> visiting, Set<String> done) {
    if (done.contains(id)) return;
    if (!visiting.add(id)) throw new IllegalArgumentException("cycle");
    for (String parent : index.get(id).requires) visit(parent, index, visiting, done);
    visiting.remove(id);
    done.add(id);
  }

  public String contentHash() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      toString()
                          .replaceFirst("LegacyDefinitionV1", "Definition")
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
