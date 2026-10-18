package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import java.time.Instant;
import java.util.*;

/** Frozen semantics 2: typed replay and dataflow. No clocks, generated IDs, or external calls. */
public final class LegacyInterpreterV2 {
  public static final String SEMANTICS = "2";

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

  public record Event(
      long sequence, EventType type, String task, String value, Instant at, List<Value> result) {
    public Event {
      if (type == null || task == null || value == null || at == null || result == null)
        throw new IllegalArgumentException("event fields");
      result = List.copyOf(result);
    }

    public Event(long sequence, EventType type, String task, String value, Instant at) {
      this(
          sequence,
          type,
          task,
          value,
          at,
          switch (type) {
            case COMPLETED, SIGNAL -> List.of(Value.text(value));
            case OPERATOR_RESOLVED ->
                value.equals("APPLIED") ? List.of(Value.text("resolved")) : List.of();
            default -> List.of();
          });
    }
  }

  public record Decision(
      String id,
      Kind kind,
      String activity,
      Safety safety,
      String compensation,
      Instant due,
      String signal,
      TaskInput input,
      Type outputType) {}

  public record Result(State state, List<Decision> decisions, Map<String, Output> values) {
    public Result {
      decisions = List.copyOf(decisions);
      values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
  }

  public Result replay(Definition definition, List<Event> history, Map<String, String> context) {
    return replay(definition, history, ExecutionInput.textContext(context));
  }

  public Result replay(Definition definition, List<Event> history, ExecutionInput input) {
    definition.validate();
    input.validate(definition);
    if (history.isEmpty() || history.getFirst().type() != EventType.STARTED)
      throw new IllegalArgumentException("STARTED required");
    Map<String, Node> nodes = new LinkedHashMap<>();
    definition.nodes().forEach(node -> nodes.put(node.id(), node));
    LinkedHashMap<String, Output> outputs = new LinkedHashMap<>();
    Map<String, Instant> times = new HashMap<>();
    LinkedHashSet<String> completionOrder = new LinkedHashSet<>();
    Set<String> failed = new HashSet<>(), unknown = new HashSet<>();
    boolean cancelled = false;
    Instant start = history.getFirst().at();
    long sequence = 0;
    derive(definition, input, outputs, times, start);
    for (Event event : history) {
      if (event.sequence() != ++sequence) throw new IllegalArgumentException("history ordering");
      if (event.type() != EventType.COMPLETED
          && event.type() != EventType.SIGNAL
          && event.type() != EventType.OPERATOR_RESOLVED
          && !event.result().isEmpty())
        throw new IllegalArgumentException("event does not carry a result");
      if (event.type() == EventType.STARTED) {
        if (sequence != 1 || !event.task().isEmpty())
          throw new IllegalArgumentException("invalid start");
        continue;
      }
      if (event.type() == EventType.LATE_EVENT) continue;
      if (event.type() == EventType.CANCELLED) {
        if (cancelled
            || !event.task().isEmpty()
            || (failed.isEmpty()
                && unknown.isEmpty()
                && definition.nodes().stream().allMatch(n -> outputs.containsKey(n.id()))))
          throw new IllegalArgumentException("invalid cancellation");
        cancelled = true;
        continue;
      }
      boolean undo = event.task().startsWith("comp-");
      Node node = nodes.get(undo ? event.task().substring(5) : event.task());
      if (node == null
          || (undo
              && (node.compensation() == null
                  || !completionOrder.contains(node.id())
                  || (!cancelled && failed.isEmpty()))))
        throw new IllegalArgumentException("unknown task");
      if (outputs.containsKey(event.task()))
        throw new IllegalArgumentException("task already settled");
      if (!undo && !enabled(node, outputs))
        throw new IllegalArgumentException("out-of-order task event");
      if (!undo && node.when() != null && !guard(node, outputs))
        throw new IllegalArgumentException("inactive branch task");
      if (unknown.contains(event.task()) && event.type() != EventType.OPERATOR_RESOLVED)
        throw new IllegalArgumentException("unknown outcome requires operator resolution");
      if (failed.contains(event.task()) && !(undo && event.type() == EventType.OPERATOR_RETRY))
        throw new IllegalArgumentException("failed task requires authorized compensation retry");
      if (!undo
          && event.type() == EventType.DISPATCHED
          && (cancelled || !failed.isEmpty() || !unknown.isEmpty()))
        throw new IllegalArgumentException("forward dispatch after workflow pause");
      if (!undo
          && (event.type() == EventType.SIGNAL || event.type() == EventType.TIMER_FIRED)
          && (cancelled || !failed.isEmpty()))
        throw new IllegalArgumentException("new work after cancellation or failure");
      Kind kind = undo ? Kind.ACTIVITY : node.kind();
      switch (event.type()) {
        case COMPLETED -> {
          requireKind(kind, Kind.ACTIVITY);
          Output output = activityOutput(node, undo, input, outputs, event.result());
          outputs.put(event.task(), output);
          completionOrder.add(event.task());
        }
        case SIGNAL -> {
          requireKind(kind, Kind.SIGNAL);
          if (event.result().size() != 1)
            throw new IllegalArgumentException("one signal value required");
          Output output = Output.of(event.result());
          output.require(node.outputType());
          outputs.put(event.task(), output);
        }
        case TIMER_FIRED -> {
          if (kind == Kind.SIGNAL) outputs.put(event.task(), Output.timedOut());
          else {
            requireKind(kind, Kind.TIMER);
            outputs.put(event.task(), Output.of(arguments(node, input, outputs)));
          }
        }
        case DISPATCHED -> requireKind(kind, Kind.ACTIVITY);
        case UNKNOWN_OUTCOME -> {
          requireKind(kind, Kind.ACTIVITY);
          unknown.add(event.task());
        }
        case FAILED -> {
          requireKind(kind, Kind.ACTIVITY);
          failed.add(event.task());
        }
        case OPERATOR_RESOLVED -> {
          if (!unknown.remove(event.task())
              || !Set.of("APPLIED", "NOT_APPLIED").contains(event.value()))
            throw new IllegalArgumentException("invalid operator resolution");
          if (event.value().equals("APPLIED")) {
            Output output = activityOutput(node, undo, input, outputs, event.result());
            outputs.put(event.task(), output);
            completionOrder.add(event.task());
          } else {
            if (!event.result().isEmpty())
              throw new IllegalArgumentException("NOT_APPLIED has no result");
            failed.add(event.task());
          }
        }
        case OPERATOR_RETRY -> {
          if (!undo || !failed.remove(event.task()))
            throw new IllegalArgumentException("invalid compensation retry");
        }
        default -> throw new IllegalArgumentException("invalid history event");
      }
      times.put(event.task(), event.at());
      derive(definition, input, outputs, times, start);
    }
    if (!unknown.isEmpty()) return new Result(State.MANUAL_REVIEW, List.of(), outputs);
    if (cancelled || !failed.isEmpty()) {
      List<String> reversed = new ArrayList<>(completionOrder);
      Collections.reverse(reversed);
      for (String id : reversed) {
        Node node = nodes.get(id);
        if (node == null || node.compensation() == null) continue;
        String undo = "comp-" + id;
        if (failed.contains(undo)) return new Result(State.COMPENSATION_FAILED, List.of(), outputs);
        if (!outputs.containsKey(undo)) {
          TaskInput parameters =
              new TaskInput(input, outputs.get(id).values(), Map.of(id, outputs.get(id)));
          return new Result(
              State.COMPENSATING,
              List.of(
                  new Decision(
                      undo,
                      Kind.ACTIVITY,
                      node.compensation(),
                      node.safety(),
                      null,
                      start,
                      null,
                      parameters,
                      node.outputType())),
              outputs);
        }
      }
      return new Result(cancelled ? State.CANCELLED : State.FAILED, List.of(), outputs);
    }
    List<Decision> decisions = new ArrayList<>();
    for (Node node : definition.nodes())
      if (!outputs.containsKey(node.id()) && enabled(node, outputs)) {
        Map<String, Output> predecessors = new LinkedHashMap<>();
        node.requires().forEach(id -> predecessors.put(id, outputs.get(id)));
        TaskInput parameters = new TaskInput(input, arguments(node, input, outputs), predecessors);
        decisions.add(
            new Decision(
                node.id(),
                node.kind(),
                node.activity(),
                node.safety(),
                node.compensation(),
                anchor(node, times, start).plusSeconds(node.delaySeconds()),
                node.signal(),
                parameters,
                node.outputType()));
      }
    boolean terminal = definition.nodes().stream().allMatch(node -> outputs.containsKey(node.id()));
    State state =
        terminal
            ? State.COMPLETED
            : decisions.stream().anyMatch(d -> d.kind() == Kind.ACTIVITY)
                ? State.RUNNING
                : State.WAITING;
    return new Result(state, decisions, outputs);
  }

  private static Output activityOutput(
      Node node,
      boolean undo,
      ExecutionInput input,
      Map<String, Output> outputs,
      List<Value> result) {
    int arguments =
        (undo ? outputs.get(node.id()).values() : arguments(node, input, outputs)).size();
    return Data.activityResult(
        undo ? node.compensation() : node.activity(), node.outputType(), arguments, result);
  }

  private static void requireKind(Kind actual, Kind expected) {
    if (actual != expected) throw new IllegalArgumentException("event/task kind mismatch");
  }

  private static boolean enabled(Node node, Map<String, Output> outputs) {
    return node.requires().stream().allMatch(outputs::containsKey);
  }

  private static Instant anchor(Node node, Map<String, Instant> times, Instant start) {
    Instant anchor = start;
    for (String id : node.requires())
      if (times.getOrDefault(id, start).isAfter(anchor)) anchor = times.get(id);
    return anchor;
  }

  private static boolean guard(Node node, Map<String, Output> outputs) {
    String[] expression = node.when().split("==");
    Output branch = outputs.get(expression[0]);
    return branch != null
        && branch.outcome() == Outcome.VALUE
        && branch.values().equals(List.of(Value.bool(Boolean.parseBoolean(expression[1]))));
  }

  private static List<Value> arguments(
      Node node, ExecutionInput input, Map<String, Output> outputs) {
    if (node.requires().isEmpty()) return List.of(input.payload());
    List<Value> values = new ArrayList<>();
    for (String id : node.requires()) {
      Output output = outputs.get(id);
      output.require(node.inputType());
      values.addAll(output.values());
    }
    return List.copyOf(values);
  }

  private static boolean expression(Node node, ExecutionInput input, Map<String, Output> outputs) {
    String[] expression = node.expression().split("==");
    String[] reference = expression[0].split("\\.");
    if (reference[0].equals("status"))
      return outputs.get(reference[1]).outcome().name().equals(expression[1]);
    Value actual;
    if (reference[0].equals("input")) actual = input.context().get(reference[1]);
    else {
      Output source = outputs.get(reference[1]);
      if (source.outcome() != Outcome.VALUE || source.values().size() != 1)
        throw new IllegalArgumentException("scalar branch requires one VALUE");
      actual = source.values().getFirst();
    }
    return actual.equals(Value.literal(actual.type(), expression[1]));
  }

  private static void derive(
      Definition definition,
      ExecutionInput input,
      Map<String, Output> outputs,
      Map<String, Instant> times,
      Instant start) {
    boolean changed;
    do {
      changed = false;
      for (Node node : definition.nodes()) {
        if (outputs.containsKey(node.id()) || !enabled(node, outputs)) continue;
        Output value = null;
        if (node.when() != null && !guard(node, outputs)) value = Output.skipped();
        else if (node.kind() == Kind.BRANCH)
          value = Output.of(List.of(Value.bool(expression(node, input, outputs))));
        else if (node.kind() == Kind.PARALLEL_ALL) {
          List<Value> values = new ArrayList<>();
          boolean timeout = false;
          for (String id : node.requires()) {
            Output previous = outputs.get(id);
            timeout |= previous.outcome() == Outcome.TIMED_OUT;
            if (previous.outcome() == Outcome.VALUE) {
              previous.require(node.inputType());
              values.addAll(previous.values());
            }
          }
          value =
              timeout ? Output.timedOut() : values.isEmpty() ? Output.skipped() : Output.of(values);
        } else if (node.requires().stream()
            .anyMatch(id -> outputs.get(id).outcome() != Outcome.VALUE)) value = Output.skipped();
        if (value != null) {
          outputs.put(node.id(), value);
          times.put(node.id(), anchor(node, times, start));
          changed = true;
        }
      }
    } while (changed);
  }
}
