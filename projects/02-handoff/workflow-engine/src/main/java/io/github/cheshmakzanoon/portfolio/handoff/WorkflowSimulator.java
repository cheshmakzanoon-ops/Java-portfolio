package io.github.cheshmakzanoon.portfolio.handoff;

import io.github.cheshmakzanoon.portfolio.handoff.Data.*;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Runs a definition through the real {@link Interpreter} with scripted outcomes and a virtual
 * clock: no database, no workers, no wall time. It plays the role of the scheduler and workers,
 * appending exactly the history events they would, and replays the whole history after each one, so
 * every transition is checked by the same code that checks production histories.
 *
 * <p>The simulation is deterministic. Of the decisions that are ready, the one that happens
 * earliest runs first (ties by node order): activities and satisfied signals happen immediately,
 * timers and unanswered signal timeouts at their due time. It shows what the semantics do, not how
 * a deployment performs; provider latency, retries and lease expiry are outside it.
 */
public final class WorkflowSimulator {
  public static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
  private static final int STEP_LIMIT = 4096;

  /** What the outside world does. Unlisted activities succeed; unlisted signals never arrive. */
  public record Scenario(
      String payload,
      Map<String, String> context,
      Set<String> failures,
      Map<String, String> unknownOutcomes,
      Map<String, String> signals,
      Integer cancelAfterEvents) {
    public Scenario {
      context = context == null ? Map.of() : Map.copyOf(context);
      failures = failures == null ? Set.of() : Set.copyOf(failures);
      unknownOutcomes = unknownOutcomes == null ? Map.of() : Map.copyOf(unknownOutcomes);
      signals = signals == null ? Map.of() : Map.copyOf(signals);
      for (String resolution : unknownOutcomes.values())
        if (!Set.of("APPLIED", "NOT_APPLIED", "UNRESOLVED").contains(resolution))
          throw new IllegalArgumentException("unknown-outcome resolution");
      if (cancelAfterEvents != null && cancelAfterEvents < 1)
        throw new IllegalArgumentException("cancelAfterEvents");
    }

    public static Scenario happyPath() {
      return new Scenario(null, null, null, null, null, null);
    }
  }

  public record Step(long sequence, EventType type, String task, String value, Duration at) {}

  /**
   * The outcome of a simulation. {@code uncompensatedEffects} lists effects that completed and
   * declare a compensation that did not run; an explicit undo step in the definition is not
   * recognised as a compensation, so read it together with the node outcomes.
   */
  public record Run(
      State state,
      List<Step> steps,
      Map<String, String> outcomes,
      List<String> compensationOrder,
      List<String> uncompensatedEffects,
      List<String> blockedOn) {
    public Run {
      steps = List.copyOf(steps);
      outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
      compensationOrder = List.copyOf(compensationOrder);
      uncompensatedEffects = List.copyOf(uncompensatedEffects);
      blockedOn = List.copyOf(blockedOn);
    }
  }

  private final Interpreter interpreter = new Interpreter();

  public Run simulate(Definition definition, Scenario scenario) {
    definition.validate();
    ExecutionInput input = input(definition, scenario);
    input.validate(definition);
    Map<String, Node> nodes = new HashMap<>();
    definition.nodes().forEach(node -> nodes.put(node.id(), node));

    List<Event> history = new ArrayList<>();
    Instant clock = START;
    long sequence = 0;
    history.add(new Event(++sequence, EventType.STARTED, "", "start", clock));
    boolean cancelled = false;
    Set<String> unresolved = new LinkedHashSet<>();
    Result result = interpreter.replay(definition, history, input);

    boolean settled = false;
    for (int step = 0; step < STEP_LIMIT && !settled; step++) {
      if (scenario.cancelAfterEvents() != null
          && !cancelled
          && history.size() >= scenario.cancelAfterEvents()
          && !finished(result.state())) {
        cancelled = true;
        history.add(
            new Event(++sequence, EventType.CANCELLED, "", "simulated cancellation", clock));
        result = interpreter.replay(definition, history, input);
        continue;
      }
      if (result.state() == State.MANUAL_REVIEW) {
        String resolved = nextResolvable(history, scenario, unresolved);
        if (resolved == null) {
          settled = true;
          continue;
        }
        boolean applied = scenario.unknownOutcomes().get(resolved).equals("APPLIED");
        List<Value> value = applied ? List.of(Value.text("ok:" + resolved)) : List.of();
        history.add(
            new Event(
                ++sequence,
                EventType.OPERATOR_RESOLVED,
                resolved,
                applied ? "APPLIED" : "NOT_APPLIED",
                clock,
                value));
        result = interpreter.replay(definition, history, input);
        continue;
      }
      if (finished(result.state()) || result.decisions().isEmpty()) {
        settled = true;
        continue;
      }

      Decision chosen = null;
      Instant chosenAt = null;
      for (Decision decision : result.decisions()) {
        Instant at = happensAt(decision, nodes, scenario, clock);
        if (chosenAt == null || at.isBefore(chosenAt)) {
          chosen = decision;
          chosenAt = at;
        }
      }
      clock = chosenAt;
      Decision decision = chosen;
      switch (decision.kind()) {
        case TIMER ->
            history.add(
                new Event(++sequence, EventType.TIMER_FIRED, decision.id(), "fired", clock));
        case SIGNAL -> {
          String delivered = scenario.signals().get(decision.signal());
          if (delivered == null)
            history.add(
                new Event(++sequence, EventType.TIMER_FIRED, decision.id(), "timeout", clock));
          else
            history.add(
                new Event(
                    ++sequence,
                    EventType.SIGNAL,
                    decision.id(),
                    delivered,
                    clock,
                    List.of(Value.literal(decision.outputType(), delivered))));
        }
        default -> {
          if (decision.safety() != Safety.PURE)
            history.add(
                new Event(++sequence, EventType.DISPATCHED, decision.id(), "dispatched", clock));
          if (scenario.failures().contains(decision.id()))
            history.add(
                new Event(++sequence, EventType.FAILED, decision.id(), "simulated failure", clock));
          else if (scenario.unknownOutcomes().containsKey(decision.id())) {
            history.add(
                new Event(
                    ++sequence,
                    EventType.UNKNOWN_OUTCOME,
                    decision.id(),
                    "simulated lost acknowledgement",
                    clock));
            unresolved.add(decision.id());
          } else
            history.add(
                new Event(
                    ++sequence,
                    EventType.COMPLETED,
                    decision.id(),
                    "ok",
                    clock,
                    simulatedResult(decision)));
        }
      }
      result = interpreter.replay(definition, history, input);
    }
    if (!settled) throw new IllegalStateException("simulation did not converge");
    return summarize(definition, nodes, history, result);
  }

  private static boolean finished(State state) {
    return switch (state) {
      case COMPLETED, FAILED, CANCELLED, COMPENSATION_FAILED -> true;
      default -> false;
    };
  }

  private static Instant happensAt(
      Decision decision, Map<String, Node> nodes, Scenario scenario, Instant clock) {
    Instant at =
        switch (decision.kind()) {
          case TIMER -> decision.due();
          case SIGNAL ->
              scenario.signals().containsKey(decision.signal())
                  ? decision.due().minusSeconds(nodes.get(decision.id()).delaySeconds())
                  : decision.due();
          default -> clock;
        };
    return at.isBefore(clock) ? clock : at;
  }

  /** The first operation with an unknown outcome that the scenario resolves; null if none. */
  private static String nextResolvable(
      List<Event> history, Scenario scenario, Set<String> unresolved) {
    Set<String> settled = new HashSet<>();
    for (Event event : history)
      if (event.type() == EventType.OPERATOR_RESOLVED) settled.add(event.task());
    for (String task : unresolved)
      if (!settled.contains(task) && !scenario.unknownOutcomes().get(task).equals("UNRESOLVED"))
        return task;
    return null;
  }

  private static ExecutionInput input(Definition definition, Scenario scenario) {
    Type payloadType = definition.root().inputType();
    Value payload =
        Value.literal(
            payloadType,
            scenario.payload() != null
                ? scenario.payload()
                : defaultLiteral(payloadType, "payload"));
    Map<String, Value> context = new TreeMap<>();
    definition
        .variables()
        .forEach(
            (name, type) ->
                context.put(
                    name,
                    Value.literal(
                        type,
                        scenario.context().getOrDefault(name, defaultLiteral(type, "sample")))));
    for (String name : scenario.context().keySet())
      if (!definition.variables().containsKey(name))
        throw new IllegalArgumentException("scenario context names an undeclared variable");
    return new ExecutionInput(payload, context);
  }

  private static String defaultLiteral(Type type, String text) {
    return switch (type) {
      case TEXT -> text;
      case INTEGER -> "1";
      case BOOLEAN -> "true";
    };
  }

  private static List<Value> simulatedResult(Decision decision) {
    List<Value> arguments = decision.input().arguments();
    return switch (decision.activity()) {
      case "pure" -> arguments;
      case "sum" -> {
        long total = 0;
        for (Value value : arguments) total = Math.addExact(total, (Long) value.data());
        yield List.of(Value.integer(total));
      }
      case "not" -> List.of(Value.bool(!(Boolean) arguments.getFirst().data()));
      default -> List.of(Value.text("ok:" + decision.id()));
    };
  }

  private Run summarize(
      Definition definition, Map<String, Node> nodes, List<Event> history, Result result) {
    List<Step> steps = new ArrayList<>();
    for (Event event : history)
      steps.add(
          new Step(
              event.sequence(),
              event.type(),
              event.task(),
              event.value(),
              Duration.between(START, event.at())));
    Set<String> failed = new HashSet<>(), unknown = new HashSet<>();
    for (Event event : history)
      switch (event.type()) {
        case FAILED -> failed.add(event.task());
        case UNKNOWN_OUTCOME -> unknown.add(event.task());
        case OPERATOR_RESOLVED -> {
          unknown.remove(event.task());
          if (event.value().equals("NOT_APPLIED")) failed.add(event.task());
        }
        default -> {}
      }
    Map<String, String> outcomes = new LinkedHashMap<>();
    for (Node node : definition.nodes()) {
      Output output = result.values().get(node.id());
      outcomes.put(
          node.id(),
          output != null
              ? output.outcome().name()
              : failed.contains(node.id())
                  ? "FAILED"
                  : unknown.contains(node.id()) ? "UNKNOWN_OUTCOME" : "NOT_REACHED");
    }
    List<String> compensated = new ArrayList<>();
    for (Event event : history)
      if (event.task().startsWith("comp-")
          && (event.type() == EventType.COMPLETED
              || (event.type() == EventType.OPERATOR_RESOLVED && event.value().equals("APPLIED"))))
        compensated.add(event.task().substring(5));
    List<String> applied = new ArrayList<>();
    for (Node node : definition.nodes()) {
      Output output = result.values().get(node.id());
      if (node.kind() == Kind.ACTIVITY
          && node.safety() != Safety.PURE
          && node.compensation() != null
          && output != null
          && output.outcome() == Outcome.VALUE
          && !compensated.contains(node.id())) applied.add(node.id());
    }
    List<String> blocked = new ArrayList<>();
    if (!finished(result.state()) && result.state() != State.MANUAL_REVIEW)
      for (Decision decision : result.decisions()) blocked.add(decision.id());
    if (result.state() == State.MANUAL_REVIEW) {
      Set<String> resolved = new HashSet<>();
      for (Event event : history)
        if (event.type() == EventType.OPERATOR_RESOLVED) resolved.add(event.task());
      for (Event event : history)
        if (event.type() == EventType.UNKNOWN_OUTCOME && !resolved.contains(event.task()))
          blocked.add(event.task());
    }
    return new Run(result.state(), steps, outcomes, compensated, applied, blocked);
  }
}
