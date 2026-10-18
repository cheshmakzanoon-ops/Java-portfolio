package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.LegacyDefinitionV1.*;
import java.time.Instant;
import java.util.*;

/** No clocks, random values, generated identifiers, or I/O enter replay. */
public final class LegacyInterpreterV1 {
  public static final String SEMANTICS = "1";

  public enum State {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    CANCEL_REQUESTED,
    COMPENSATING,
    COMPENSATION_FAILED,
    MANUAL_REVIEW
  }

  public enum EventType {
    STARTED,
    COMPLETED,
    TIMER_FIRED,
    SIGNAL,
    FAILED,
    CANCELLED,
    DISPATCHED,
    UNKNOWN_OUTCOME,
    OPERATOR_RESOLVED,
    OPERATOR_RETRY,
    LATE_EVENT
  }

  public record Event(long sequence, EventType type, String task, String value, Instant at) {}

  public record Decision(
      String id,
      Kind kind,
      String activity,
      Safety safety,
      String compensation,
      Instant due,
      String signal) {}

  public record Result(State state, List<Decision> decisions, Map<String, String> values) {
    public Result {
      decisions = List.copyOf(decisions);
      values = Map.copyOf(values);
    }
  }

  public Result replay(
      LegacyDefinitionV1 definition, List<Event> history, Map<String, String> input) {
    definition.validate();
    if (history.isEmpty() || history.getFirst().type() != EventType.STARTED)
      throw new IllegalArgumentException("STARTED required");
    Map<String, Node> index = new HashMap<>();
    for (Node node : definition.nodes()) index.put(node.id(), node);
    Map<String, String> values = new LinkedHashMap<>();
    Map<String, Event> successes = new LinkedHashMap<>();
    Set<String> unknown = new HashSet<>(), failed = new HashSet<>();
    boolean cancelled = false;
    Instant started = Instant.EPOCH;
    long sequence = 0;
    for (Event e : history) {
      if (e.sequence() != ++sequence) throw new IllegalArgumentException("history ordering");
      if (e.type() == EventType.STARTED && e.sequence() != 1)
        throw new IllegalArgumentException("duplicate start");
      if (e.type() != EventType.STARTED
          && e.type() != EventType.CANCELLED
          && e.type() != EventType.LATE_EVENT) {
        boolean undo = e.task().startsWith("comp-");
        Node node = index.get(undo ? e.task().substring(5) : e.task());
        if (node == null
            || (undo
                && (node.compensation() == null
                    || !successes.containsKey(node.id())
                    || (!cancelled && failed.isEmpty()))))
          throw new IllegalArgumentException("unknown task");
        Kind kind = undo ? Kind.ACTIVITY : node.kind();
        if (successes.containsKey(e.task()))
          throw new IllegalArgumentException("task already settled");
        if (!undo && node.when() != null) {
          String[] guard = node.when().split("==");
          String[] expression = index.get(guard[0]).expression().split("==");
          String actual =
              Boolean.toString(expression[1].equals(input.get(expression[0].substring(6))));
          if (!actual.equals(guard[1])) throw new IllegalArgumentException("inactive branch task");
        }
        if (Set.of(
                    EventType.DISPATCHED,
                    EventType.FAILED,
                    EventType.UNKNOWN_OUTCOME,
                    EventType.OPERATOR_RETRY)
                .contains(e.type())
            && kind != Kind.ACTIVITY) throw new IllegalArgumentException("activity event required");

        if ((e.type() == EventType.COMPLETED && kind != Kind.ACTIVITY)
            || (e.type() == EventType.SIGNAL && kind != Kind.SIGNAL)
            || (e.type() == EventType.TIMER_FIRED && kind != Kind.SIGNAL && kind != Kind.TIMER))
          throw new IllegalArgumentException("event/task kind mismatch");
        if (e.type() == EventType.OPERATOR_RESOLVED
            && (!unknown.contains(e.task())
                || !Set.of("APPLIED", "NOT_APPLIED").contains(e.value())))
          throw new IllegalArgumentException("invalid operator resolution");
        if (!undo
            && e.type() != EventType.OPERATOR_RESOLVED
            && node.requires().stream()
                .anyMatch(parent -> !settled(parent, index, successes, input)))
          throw new IllegalArgumentException("out-of-order task event");
      }
      switch (e.type()) {
        case STARTED -> started = e.at();
        case COMPLETED, TIMER_FIRED, SIGNAL -> {
          if (successes.putIfAbsent(e.task(), e) == null) values.put(e.task(), e.value());
        }
        case UNKNOWN_OUTCOME -> unknown.add(e.task());
        case OPERATOR_RESOLVED -> {
          unknown.remove(e.task());
          if ("APPLIED".equals(e.value())) {
            successes.put(e.task(), e);
            values.put(e.task(), "resolved");
          } else failed.add(e.task());
        }
        case OPERATOR_RETRY -> {
          if (!e.task().startsWith("comp-") || !failed.remove(e.task()))
            throw new IllegalArgumentException("invalid compensation retry");
        }
        case FAILED -> failed.add(e.task());
        case CANCELLED -> cancelled = true;
        default -> {}
      }
    }
    if (!unknown.isEmpty()) return new Result(State.MANUAL_REVIEW, List.of(), values);
    if (cancelled || !failed.isEmpty()) {
      List<Decision> undo = new ArrayList<>();
      List<Node> completed = new ArrayList<>();
      for (String id : successes.keySet())
        definition.nodes().stream()
            .filter(n -> n.id().equals(id) && n.compensation() != null)
            .findFirst()
            .ifPresent(completed::add);
      Collections.reverse(completed);
      for (Node n : completed) {
        String id = "comp-" + n.id();
        if (failed.contains(id)) return new Result(State.COMPENSATION_FAILED, List.of(), values);
        if (!successes.containsKey(id)) {
          undo.add(
              new Decision(id, Kind.ACTIVITY, n.compensation(), n.safety(), null, started, null));
          break;
        }
      }
      return new Result(
          undo.isEmpty() ? (cancelled ? State.CANCELLED : State.FAILED) : State.COMPENSATING,
          undo,
          values);
    }
    List<Decision> decisions = new ArrayList<>();
    Map<String, Instant> settledAt = new HashMap<>();
    for (var entry : successes.entrySet()) settledAt.put(entry.getKey(), entry.getValue().at());
    Set<String> settled = new HashSet<>(successes.keySet());
    boolean changed;
    do {
      changed = false;
      for (Node n : definition.nodes()) {
        if (settled.contains(n.id()) || !settled.containsAll(n.requires())) continue;
        Instant derivedAt = started;
        for (String parent : n.requires())
          if (settledAt.getOrDefault(parent, started).isAfter(derivedAt))
            derivedAt = settledAt.get(parent);
        if (n.when() != null) {
          String[] guard = n.when().split("==");
          if (!guard[1].equals(values.get(guard[0]))) {
            settled.add(n.id());
            values.put(n.id(), "skipped");
            settledAt.put(n.id(), derivedAt);
            changed = true;
            continue;
          }
        }
        if (n.kind() == Kind.BRANCH || n.kind() == Kind.PARALLEL_ALL) {
          String value = "joined";
          if (n.kind() == Kind.BRANCH) {
            String[] expr = n.expression().split("==");
            value = Boolean.toString(expr[1].equals(input.get(expr[0].substring(6))));
          }
          values.put(n.id(), value);
          settledAt.put(n.id(), derivedAt);
          settled.add(n.id());
          changed = true;
        }
      }
    } while (changed);
    for (Node n : definition.nodes()) {
      if (settled.contains(n.id()) || !settled.containsAll(n.requires())) continue;
      Instant anchor = started;
      for (String r : n.requires())
        if (settledAt.getOrDefault(r, started).isAfter(anchor)) anchor = settledAt.get(r);
      decisions.add(
          new Decision(
              n.id(),
              n.kind(),
              n.activity(),
              n.safety(),
              n.compensation(),
              anchor.plusSeconds(n.delaySeconds()),
              n.signal()));
    }
    State state =
        settled.size() == definition.nodes().size()
            ? State.COMPLETED
            : decisions.stream().anyMatch(d -> d.kind() == Kind.ACTIVITY)
                ? State.RUNNING
                : State.WAITING;
    return new Result(state, decisions, values);
  }

  private static boolean settled(
      String id, Map<String, Node> index, Map<String, Event> successes, Map<String, String> input) {
    if (successes.containsKey(id)) return true;
    Node n = index.get(id);
    if (!n.requires().stream().allMatch(p -> settled(p, index, successes, input))) return false;
    if (n.kind() == Kind.BRANCH || n.kind() == Kind.PARALLEL_ALL) return true;
    if (n.when() != null) {
      String[] guard = n.when().split("==");
      Node branch = index.get(guard[0]);
      String[] expression = branch.expression().split("==");
      String actual = Boolean.toString(expression[1].equals(input.get(expression[0].substring(6))));
      return !actual.equals(guard[1]);
    }
    return false;
  }
}
