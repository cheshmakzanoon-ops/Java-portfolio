package io.github.cheshmakzanoon.portfolio.handoff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable finite typed graph. Expressions are deliberately not a general language. */
public record Definition(String name, int version, List<Node> nodes, Map<String, Type> variables) {
  public Definition(String name, int version, List<Node> nodes) {
    this(name, version, nodes, Map.of());
  }

  public Node root() {
    return nodes.stream().filter(n -> n.requires().isEmpty()).findFirst().orElseThrow();
  }

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
          "pure",
          "sum",
          "not");

  public Definition {
    nodes = List.copyOf(nodes);
    variables =
        variables == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(variables));
  }

  public Definition validate() {
    if (name == null
        || !name.matches("[a-z][a-z0-9-]{0,63}")
        || version < 1
        || nodes.isEmpty()
        || nodes.size() > 128) throw new IllegalArgumentException("definition bounds");
    if (variables.size() > 32
        || variables.entrySet().stream()
            .anyMatch(
                e -> !e.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,31}") || e.getValue() == null))
      throw new IllegalArgumentException("variable schema");
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
          && (n.activity == null || !ACTIVITIES.contains(n.activity) || n.safety == null))
        throw new IllegalArgumentException("untrusted activity");
      if (n.kind == Kind.ACTIVITY)
        activitySignature(n.activity, n.safety, n.inputType, n.outputType);
      if (n.kind == Kind.TIMER && n.inputType != n.outputType)
        throw new IllegalArgumentException("timer passes through its arguments");
      if (n.kind == Kind.PARALLEL_ALL && n.inputType != n.outputType)
        throw new IllegalArgumentException("join preserves element type");
      if (n.compensation != null
          && (n.kind != Kind.ACTIVITY || !ACTIVITIES.contains(n.compensation)))
        throw new IllegalArgumentException("untrusted compensation");
      if (n.delaySeconds < 0 || n.delaySeconds > 604800)
        throw new IllegalArgumentException("timer bound");
      if (n.kind == Kind.BRANCH
          && (n.expression == null
              || !n.expression.matches(
                  "(input|value|status)\\.[a-zA-Z][a-zA-Z0-9_-]{0,63}==[a-zA-Z0-9_-]{1,64}")
              || n.outputType != Type.BOOLEAN))
        throw new IllegalArgumentException("invalid expression");
      if (n.when != null && !n.when.matches("[a-z][a-z0-9-]{0,63}==(true|false)"))
        throw new IllegalArgumentException("invalid guard");
      if (n.kind == Kind.SIGNAL && (n.signal == null || !n.signal.matches("[a-z][a-z0-9-]{0,63}")))
        throw new IllegalArgumentException("signal name");
    }
    Set<String> signalNames = new HashSet<>();
    for (Node n : nodes)
      if (n.kind == Kind.SIGNAL && !signalNames.add(n.signal))
        throw new IllegalArgumentException("unique signal names required");
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
      if (n.kind == Kind.BRANCH) {
        String[] expression = n.expression.split("==");
        String[] reference = expression[0].split("\\.");
        if (reference[0].equals("input")) {
          Type type = variables.get(reference[1]);
          if (type == null) throw new IllegalArgumentException("unknown input variable");
          Data.Value.literal(type, expression[1]);
        } else {
          Node source = index.get(reference[1]);
          if (source == null || !n.requires.contains(source.id))
            throw new IllegalArgumentException("branch must inspect direct predecessor");
          if (reference[0].equals("status")) {
            if (!Set.of("VALUE", "SKIPPED", "TIMED_OUT").contains(expression[1]))
              throw new IllegalArgumentException("invalid outcome literal");
          } else Data.Value.literal(source.outputType, expression[1]);
        }
      }
      if (n.compensation != null)
        activitySignature(n.compensation, n.safety, n.outputType, n.outputType);
      visit(n.id, index, new HashSet<>(), new HashSet<>());
    }
    Map<String, Integer> arities = new HashMap<>();
    for (Node n : nodes) arity(n, index, arities);
    for (Node parent : nodes)
      if (nodes.stream().filter(n -> n.requires().contains(parent.id())).count() > 16)
        throw new IllegalArgumentException("fan-out bound");
    return this;
  }

  private static int arity(Node node, Map<String, Node> nodes, Map<String, Integer> memo) {
    Integer known = memo.get(node.id());
    if (known != null) return known;
    int inputs = node.requires().isEmpty() ? 1 : 0;
    for (String previous : node.requires()) {
      inputs = Math.addExact(inputs, arity(nodes.get(previous), nodes, memo));
      if (inputs > 128) throw new IllegalArgumentException("bounded argument vector");
    }
    if (node.kind() == Kind.PARALLEL_ALL && node.requires().size() < 2)
      throw new IllegalArgumentException("parallel-all requires at least two branches");
    if (node.kind() == Kind.ACTIVITY && node.activity().equals("not") && inputs != 1)
      throw new IllegalArgumentException("not requires one scalar");
    if (node.kind() == Kind.BRANCH && node.expression().startsWith("value.")) {
      String source = node.expression().split("==")[0].substring(6);
      if (arity(nodes.get(source), nodes, memo) != 1)
        throw new IllegalArgumentException("value predicate requires scalar predecessor");
    }
    int outputs =
        switch (node.kind()) {
          case SIGNAL, BRANCH -> 1;
          case ACTIVITY -> node.activity().equals("pure") ? inputs : 1;
          case TIMER, PARALLEL_ALL -> inputs;
        };
    if (node.compensation() != null && node.compensation().equals("not") && outputs != 1)
      throw new IllegalArgumentException("not compensation requires scalar");
    memo.put(node.id(), outputs);
    return outputs;
  }

  private static void activitySignature(String name, Safety safety, Type input, Type output) {
    switch (name) {
      case "pure" -> {
        if (safety != Safety.PURE || input != output)
          throw new IllegalArgumentException("echo signature");
      }
      case "sum" -> {
        if (safety != Safety.PURE || input != Type.INTEGER || output != Type.INTEGER)
          throw new IllegalArgumentException("sum signature");
      }
      case "not" -> {
        if (safety != Safety.PURE || input != Type.BOOLEAN || output != Type.BOOLEAN)
          throw new IllegalArgumentException("not signature");
      }
      default -> {
        if (safety == Safety.PURE || output != Type.TEXT)
          throw new IllegalArgumentException("provider signature");
      }
    }
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
                  .digest(toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
