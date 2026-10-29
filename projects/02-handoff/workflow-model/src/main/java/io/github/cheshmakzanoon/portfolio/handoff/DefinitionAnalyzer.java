package io.github.cheshmakzanoon.portfolio.handoff;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.Kind;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.Node;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.Safety;
import java.util.*;

/**
 * Design-time review of a definition. {@link Definition#validate()} answers "can the engine run
 * this?" and stops at the first reason it cannot; this answers "will it do what its author probably
 * meant?" and reports every concern with the node it concerns.
 *
 * <p>The analysis is pure and runs only on definitions that validate. It never changes behaviour
 * and none of its warnings is a rule the engine enforces: they point at consequences of the
 * documented semantics (timeouts skip rather than fail, compensation runs only on failure or
 * cancellation, an effect with an unknown outcome waits for an operator).
 */
public final class DefinitionAnalyzer {
  public enum Severity {
    ERROR,
    WARNING,
    INFO
  }

  public record Finding(Severity severity, String code, String node, String message) {}

  /** Shape and bounds; a longest "wait" is timer delays and signal timeouts summed along a path. */
  public record Metrics(
      int nodes,
      int edges,
      int activities,
      int effects,
      int compensable,
      int signals,
      int timers,
      int branches,
      int joins,
      int longestChain,
      int maxFanOut,
      int maxFanIn,
      long longestWaitSeconds) {
    static Metrics empty() {
      return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
  }

  public record Analysis(String name, int version, List<Finding> findings, Metrics metrics) {
    public Analysis {
      findings = List.copyOf(findings);
    }

    public boolean valid() {
      return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
    }

    public long count(Severity severity) {
      return findings.stream().filter(f -> f.severity() == severity).count();
    }
  }

  private DefinitionAnalyzer() {}

  public static Analysis analyze(Definition definition) {
    try {
      definition.validate();
    } catch (RuntimeException invalid) {
      return new Analysis(
          String.valueOf(definition.name()),
          definition.version(),
          List.of(
              new Finding(
                  Severity.ERROR,
                  "INVALID_DEFINITION",
                  null,
                  "the engine would refuse this definition: " + invalid.getMessage())),
          Metrics.empty());
    }
    Map<String, Node> index = new LinkedHashMap<>();
    Map<String, List<Node>> successors = new HashMap<>();
    for (Node node : definition.nodes()) {
      index.put(node.id(), node);
      successors.put(node.id(), new ArrayList<>());
    }
    for (Node node : definition.nodes())
      for (String parent : node.requires()) successors.get(parent).add(node);

    List<Finding> findings = new ArrayList<>();
    for (Node node : definition.nodes()) {
      if (isEffect(node)) effectFindings(node, successors, findings);
      switch (node.kind()) {
        case SIGNAL -> signalFindings(definition, node, index, successors, findings);
        case TIMER -> {
          if (node.delaySeconds() == 0)
            findings.add(
                new Finding(
                    Severity.INFO,
                    "TIMER_WITHOUT_DELAY",
                    node.id(),
                    "timer '" + node.id() + "' has no delay and only passes its values through"));
        }
        case BRANCH -> {
          if (!referenced(node.id(), definition))
            findings.add(
                new Finding(
                    Severity.WARNING,
                    "UNUSED_BRANCH",
                    node.id(),
                    "branch '"
                        + node.id()
                        + "' is not required by any node and guards none, so its result is never used"));
        }
        default -> {}
      }
    }
    Metrics metrics = metrics(definition, index, successors);
    limits(metrics, findings);
    findings.sort(
        Comparator.comparing(Finding::severity)
            .thenComparing(f -> f.node() == null ? "" : f.node())
            .thenComparing(Finding::code));
    return new Analysis(definition.name(), definition.version(), findings, metrics);
  }

  private static boolean isEffect(Node node) {
    return node.kind() == Kind.ACTIVITY && node.safety() != Safety.PURE;
  }

  private static void effectFindings(
      Node node, Map<String, List<Node>> successors, List<Finding> findings) {
    if (node.safety() == Safety.NON_IDEMPOTENT)
      findings.add(
          new Finding(
              Severity.INFO,
              "NON_IDEMPOTENT_EFFECT",
              node.id(),
              "'"
                  + node.id()
                  + "' is not idempotent: a lost acknowledgement leaves it in UNKNOWN_OUTCOME until an operator reconciles it"));
    if (node.compensation() == null) {
      List<String> later = new ArrayList<>();
      for (Node descendant : descendants(node, successors))
        if (isEffect(descendant)) later.add(descendant.id());
      if (!later.isEmpty())
        findings.add(
            new Finding(
                Severity.WARNING,
                "EFFECT_NEVER_UNDONE",
                node.id(),
                "'"
                    + node.id()
                    + "' has no compensation, so if "
                    + names(later)
                    + " fails the workflow cannot undo it"));
    }
  }

  private static void signalFindings(
      Definition definition,
      Node signal,
      Map<String, Node> index,
      Map<String, List<Node>> successors,
      List<Finding> findings) {
    if (signal.delaySeconds() == 0) {
      findings.add(
          new Finding(
              Severity.WARNING,
              "SIGNAL_TIMES_OUT_IMMEDIATELY",
              signal.id(),
              "signal '"
                  + signal.id()
                  + "' has a zero timeout, so it expires the moment it is waited on and can never be received"));
      return;
    }
    String handled = "status." + signal.id() + "==TIMED_OUT";
    boolean handledByBranch =
        definition.nodes().stream()
            .anyMatch(n -> n.kind() == Kind.BRANCH && handled.equals(n.expression()));
    List<Node> skipped = descendants(signal, successors);
    if (handledByBranch || skipped.isEmpty()) return;
    List<String> undoable = new ArrayList<>();
    for (Node ancestor : ancestors(signal, index))
      if (ancestor.kind() == Kind.ACTIVITY && ancestor.compensation() != null)
        undoable.add(ancestor.id());
    Collections.sort(undoable);
    if (undoable.isEmpty())
      findings.add(
          new Finding(
              Severity.INFO,
              "TIMEOUT_SKIPS_DEPENDANTS",
              signal.id(),
              "if '"
                  + signal.id()
                  + "' times out, "
                  + skipped.size()
                  + " dependant node(s) are skipped and the workflow still completes"));
    else
      findings.add(
          new Finding(
              Severity.WARNING,
              "TIMEOUT_LEAVES_EFFECT_APPLIED",
              signal.id(),
              "if '"
                  + signal.id()
                  + "' times out its "
                  + skipped.size()
                  + " dependant node(s) are skipped and the workflow completes, leaving "
                  + names(undoable)
                  + " applied: compensation runs only on failure or cancellation. Branch on status."
                  + signal.id()
                  + "==TIMED_OUT and undo explicitly"));
  }

  private static boolean referenced(String branch, Definition definition) {
    for (Node node : definition.nodes()) {
      if (node.requires().contains(branch)) return true;
      if (node.when() != null && node.when().startsWith(branch + "==")) return true;
    }
    return false;
  }

  private static void limits(Metrics metrics, List<Finding> findings) {
    if (metrics.nodes() >= 96)
      findings.add(
          new Finding(
              Severity.INFO,
              "NEAR_NODE_LIMIT",
              null,
              "the definition has " + metrics.nodes() + " of at most 128 nodes"));
    if (metrics.maxFanOut() >= 12)
      findings.add(
          new Finding(
              Severity.INFO,
              "NEAR_FAN_OUT_LIMIT",
              null,
              "a node has " + metrics.maxFanOut() + " dependants; at most 16 are allowed"));
    if (metrics.maxFanIn() >= 12)
      findings.add(
          new Finding(
              Severity.INFO,
              "NEAR_REQUIRES_LIMIT",
              null,
              "a node requires " + metrics.maxFanIn() + " predecessors; at most 16 are allowed"));
  }

  private static Metrics metrics(
      Definition definition, Map<String, Node> index, Map<String, List<Node>> successors) {
    int edges = 0, activities = 0, effects = 0, compensable = 0, signals = 0, timers = 0;
    int branches = 0, joins = 0, fanIn = 0, fanOut = 0;
    for (Node node : definition.nodes()) {
      edges += node.requires().size();
      fanIn = Math.max(fanIn, node.requires().size());
      fanOut = Math.max(fanOut, successors.get(node.id()).size());
      switch (node.kind()) {
        case ACTIVITY -> {
          activities++;
          if (isEffect(node)) effects++;
          if (node.compensation() != null) compensable++;
        }
        case SIGNAL -> signals++;
        case TIMER -> timers++;
        case BRANCH -> branches++;
        case PARALLEL_ALL -> joins++;
      }
    }
    Map<String, Integer> chain = new HashMap<>();
    Map<String, Long> wait = new HashMap<>();
    int longest = 0;
    long longestWait = 0;
    // Nodes are acyclic (validated) but not necessarily listed in dependency order.
    for (Node node : definition.nodes()) {
      longest = Math.max(longest, chain(node, index, chain));
      longestWait = Math.max(longestWait, wait(node, index, wait));
    }
    return new Metrics(
        definition.nodes().size(),
        edges,
        activities,
        effects,
        compensable,
        signals,
        timers,
        branches,
        joins,
        longest,
        fanOut,
        fanIn,
        longestWait);
  }

  private static int chain(Node node, Map<String, Node> index, Map<String, Integer> memo) {
    Integer known = memo.get(node.id());
    if (known != null) return known;
    int deepest = 0;
    for (String parent : node.requires())
      deepest = Math.max(deepest, chain(index.get(parent), index, memo));
    memo.put(node.id(), deepest + 1);
    return deepest + 1;
  }

  private static long wait(Node node, Map<String, Node> index, Map<String, Long> memo) {
    Long known = memo.get(node.id());
    if (known != null) return known;
    long longest = 0;
    for (String parent : node.requires())
      longest = Math.max(longest, wait(index.get(parent), index, memo));
    long own = node.kind() == Kind.TIMER || node.kind() == Kind.SIGNAL ? node.delaySeconds() : 0;
    long total = Math.addExact(longest, own);
    memo.put(node.id(), total);
    return total;
  }

  private static List<Node> descendants(Node from, Map<String, List<Node>> successors) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<Node> pending = new ArrayDeque<>(successors.get(from.id()));
    List<Node> result = new ArrayList<>();
    while (!pending.isEmpty()) {
      Node next = pending.removeFirst();
      if (!seen.add(next.id())) continue;
      result.add(next);
      pending.addAll(successors.get(next.id()));
    }
    return result;
  }

  private static List<Node> ancestors(Node from, Map<String, Node> index) {
    Set<String> seen = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(from.requires());
    List<Node> result = new ArrayList<>();
    while (!pending.isEmpty()) {
      String next = pending.removeFirst();
      if (!seen.add(next)) continue;
      Node node = index.get(next);
      result.add(node);
      pending.addAll(node.requires());
    }
    return result;
  }

  private static String names(List<String> ids) {
    List<String> shown = ids.size() > 3 ? ids.subList(0, 3) : ids;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < shown.size(); i++) {
      if (i > 0) out.append(i == shown.size() - 1 && ids.size() <= 3 ? " or " : ", ");
      out.append('\'').append(shown.get(i)).append('\'');
    }
    if (ids.size() > 3) out.append(" or ").append(ids.size() - 3).append(" more");
    return out.toString();
  }
}
