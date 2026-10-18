package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.LegacyInterpreterV2.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class LegacyTypedReplayV2Test {
  static Node n(
      String id,
      Kind kind,
      List<String> requires,
      String activity,
      Type in,
      Type out,
      String expression,
      String when) {
    return new Node(
        id,
        kind,
        requires,
        activity,
        activity == null ? null : Safety.PURE,
        null,
        in,
        out,
        0,
        kind == Kind.SIGNAL ? id : null,
        expression,
        when);
  }

  Event e(long sequence, EventType type, String task, List<Value> result) {
    return new Event(sequence, type, task, "recorded", Instant.ofEpochSecond(sequence), result);
  }

  List<Event> started() {
    return new ArrayList<>(List.of(e(1, EventType.STARTED, "", List.of())));
  }

  @Test
  void integerSignalFlowsThroughJoinSumAndBooleanBranch() {
    Definition d =
        new Definition(
            "typed",
            1,
            List.of(
                n("wait", Kind.SIGNAL, List.of(), null, Type.INTEGER, Type.INTEGER, null, null),
                n(
                    "left",
                    Kind.ACTIVITY,
                    List.of("wait"),
                    "pure",
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null),
                n(
                    "right",
                    Kind.ACTIVITY,
                    List.of("wait"),
                    "pure",
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null),
                n(
                    "join",
                    Kind.PARALLEL_ALL,
                    List.of("left", "right"),
                    null,
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null),
                n(
                    "sum",
                    Kind.ACTIVITY,
                    List.of("join"),
                    "sum",
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null),
                n(
                    "choice",
                    Kind.BRANCH,
                    List.of("sum"),
                    null,
                    Type.INTEGER,
                    Type.BOOLEAN,
                    "value.sum==14",
                    null),
                n(
                    "yes",
                    Kind.ACTIVITY,
                    List.of("choice"),
                    "not",
                    Type.BOOLEAN,
                    Type.BOOLEAN,
                    null,
                    "choice==true"),
                n(
                    "no",
                    Kind.ACTIVITY,
                    List.of("choice"),
                    "pure",
                    Type.BOOLEAN,
                    Type.BOOLEAN,
                    null,
                    "choice==false")));
    ExecutionInput input = new ExecutionInput(Value.integer(2), Map.of());
    LegacyInterpreterV2 engine = new LegacyInterpreterV2();
    List<Event> h = started();
    h.add(e(2, EventType.SIGNAL, "wait", List.of(Value.integer(7))));
    Result ready = engine.replay(d, h, input);
    assertEquals(List.of("left", "right"), ready.decisions().stream().map(Decision::id).toList());
    assertEquals(List.of(Value.integer(7)), ready.decisions().getFirst().input().arguments());
    h.add(e(3, EventType.COMPLETED, "right", List.of(Value.integer(7))));
    h.add(e(4, EventType.COMPLETED, "left", List.of(Value.integer(7))));
    Result sum = engine.replay(d, h, input);
    assertEquals(
        List.of(Value.integer(7), Value.integer(7)),
        sum.decisions().getFirst().input().arguments());
    h.add(e(5, EventType.COMPLETED, "sum", List.of(Value.integer(14))));
    Result choice = engine.replay(d, h, input);
    assertEquals(Outcome.SKIPPED, choice.values().get("no").outcome());
    assertEquals(List.of(Value.bool(true)), choice.decisions().getFirst().input().arguments());
    h.add(e(6, EventType.COMPLETED, "yes", List.of(Value.bool(false))));
    Result done = engine.replay(d, h, input);
    assertEquals(State.COMPLETED, done.state());
    assertEquals(done, engine.replay(d, List.copyOf(h), input));
  }

  @Test
  void timeoutIsAnOutcomeAndCanTakeAnExplicitFallback() {
    Definition d =
        new Definition(
            "timeout",
            1,
            List.of(
                n("wait", Kind.SIGNAL, List.of(), null, Type.INTEGER, Type.INTEGER, null, null),
                n(
                    "normal",
                    Kind.ACTIVITY,
                    List.of("wait"),
                    "sum",
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null),
                n(
                    "expired",
                    Kind.BRANCH,
                    List.of("wait"),
                    null,
                    Type.INTEGER,
                    Type.BOOLEAN,
                    "status.wait==TIMED_OUT",
                    null),
                n(
                    "fallback",
                    Kind.ACTIVITY,
                    List.of("expired"),
                    "pure",
                    Type.BOOLEAN,
                    Type.BOOLEAN,
                    null,
                    "expired==true")));
    ExecutionInput input = new ExecutionInput(Value.integer(3), Map.of());
    List<Event> h = started();
    h.add(e(2, EventType.TIMER_FIRED, "wait", List.of()));
    Result waiting = new LegacyInterpreterV2().replay(d, h, input);
    assertEquals(Output.timedOut(), waiting.values().get("wait"));
    assertEquals(Output.skipped(), waiting.values().get("normal"));
    assertEquals("fallback", waiting.decisions().getFirst().id());
    h.add(e(3, EventType.COMPLETED, "fallback", List.of(Value.bool(true))));
    assertEquals(State.COMPLETED, new LegacyInterpreterV2().replay(d, h, input).state());
  }

  @Test
  void wrongStartSignalAndCompletionTypesAreRejected() {
    Definition d =
        new Definition(
            "types",
            1,
            List.of(
                n("wait", Kind.SIGNAL, List.of(), null, Type.INTEGER, Type.INTEGER, null, null),
                n(
                    "sum",
                    Kind.ACTIVITY,
                    List.of("wait"),
                    "sum",
                    Type.INTEGER,
                    Type.INTEGER,
                    null,
                    null)));
    LegacyInterpreterV2 engine = new LegacyInterpreterV2();
    ExecutionInput input = new ExecutionInput(Value.integer(3), Map.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.replay(d, started(), new ExecutionInput(Value.text("3"), Map.of())));
    List<Event> badSignal = started();
    badSignal.add(e(2, EventType.SIGNAL, "wait", List.of(Value.text("7"))));
    assertThrows(IllegalArgumentException.class, () -> engine.replay(d, badSignal, input));
    List<Event> h = started();
    h.add(e(2, EventType.SIGNAL, "wait", List.of(Value.integer(7))));
    h.add(e(3, EventType.COMPLETED, "sum", List.of(Value.bool(true))));
    assertThrows(IllegalArgumentException.class, () -> engine.replay(d, h, input));
  }

  @Test
  void timerPreservesBusinessInputAndContextPredicatesAreTyped() {
    Definition d =
        new Definition(
            "context",
            1,
            List.of(
                n("timer", Kind.TIMER, List.of(), null, Type.BOOLEAN, Type.BOOLEAN, null, null),
                n(
                    "choice",
                    Kind.BRANCH,
                    List.of("timer"),
                    null,
                    Type.BOOLEAN,
                    Type.BOOLEAN,
                    "input.approved==true",
                    null)),
            Map.of("approved", Type.BOOLEAN));
    ExecutionInput input =
        new ExecutionInput(Value.bool(false), Map.of("approved", Value.bool(true)));
    List<Event> h = started();
    h.add(e(2, EventType.TIMER_FIRED, "timer", List.of()));
    Result result = new LegacyInterpreterV2().replay(d, h, input);
    assertEquals(List.of(Value.bool(false)), result.values().get("timer").values());
    assertEquals(List.of(Value.bool(true)), result.values().get("choice").values());
    assertEquals(State.COMPLETED, result.state());
  }

  @org.junit.jupiter.api.Test
  void terminalTaskAndAdministrativePayloadCorruptionIsRejected() {
    Definition definition =
        new Definition(
            "terminal-order",
            1,
            List.of(
                new Node(
                    "root",
                    Kind.ACTIVITY,
                    List.of(),
                    "reserve",
                    Safety.NON_IDEMPOTENT,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null)));
    var start = new Event(1, EventType.STARTED, "", "started", Instant.EPOCH);
    for (EventType terminal : List.of(EventType.FAILED, EventType.UNKNOWN_OUTCOME)) {
      for (EventType illegal :
          List.of(
              EventType.COMPLETED,
              EventType.DISPATCHED,
              EventType.FAILED,
              EventType.UNKNOWN_OUTCOME)) {
        var history =
            List.of(
                start,
                new Event(2, terminal, "root", "failed", Instant.ofEpochSecond(1)),
                new Event(3, illegal, "root", "late", Instant.ofEpochSecond(2)));
        assertThrows(
            IllegalArgumentException.class,
            () -> new LegacyInterpreterV2().replay(definition, history, Map.of()));
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    definition,
                    List.of(
                        new Event(
                            1,
                            EventType.STARTED,
                            "",
                            "started",
                            Instant.EPOCH,
                            List.of(Value.text("unexpected")))),
                    Map.of()));
    var signal =
        new Definition(
            "cancelled-signal",
            1,
            List.of(
                new Node(
                    "wait",
                    Kind.SIGNAL,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    60,
                    "approval",
                    null,
                    null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    signal,
                    List.of(
                        start,
                        new Event(
                            2, EventType.CANCELLED, "", "cancelled", Instant.ofEpochSecond(1)),
                        new Event(3, EventType.SIGNAL, "wait", "late", Instant.ofEpochSecond(2))),
                    Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    definition,
                    List.of(
                        start,
                        new Event(
                            2,
                            EventType.UNKNOWN_OUTCOME,
                            "root",
                            "unknown",
                            Instant.ofEpochSecond(1)),
                        new Event(
                            3,
                            EventType.OPERATOR_RESOLVED,
                            "root",
                            "NOT_APPLIED",
                            Instant.ofEpochSecond(2),
                            List.of(Value.text("unexpected")))),
                    Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    definition,
                    List.of(
                        start,
                        new Event(2, EventType.COMPLETED, "root", "done", Instant.ofEpochSecond(1)),
                        new Event(
                            3, EventType.CANCELLED, "", "cancelled", Instant.ofEpochSecond(2))),
                    Map.of()));
    Definition parallel =
        new Definition(
            "paused-parallel",
            1,
            List.of(
                new Node(
                    "start",
                    Kind.TIMER,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null),
                new Node(
                    "left",
                    Kind.ACTIVITY,
                    List.of("start"),
                    "reserve",
                    Safety.NON_IDEMPOTENT,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null),
                new Node(
                    "right",
                    Kind.ACTIVITY,
                    List.of("start"),
                    "reserve",
                    Safety.NON_IDEMPOTENT,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null)));
    for (EventType pause : List.of(EventType.UNKNOWN_OUTCOME, EventType.FAILED)) {
      var history =
          List.of(
              start,
              new Event(2, EventType.TIMER_FIRED, "start", "timer", Instant.ofEpochSecond(1)),
              new Event(3, pause, "left", "pause", Instant.ofEpochSecond(2)),
              new Event(4, EventType.DISPATCHED, "right", "illegal", Instant.ofEpochSecond(3)));
      assertThrows(
          IllegalArgumentException.class,
          () -> new LegacyInterpreterV2().replay(parallel, history, Map.of()));
    }
  }

  @Test
  void recordedActivityAndReconciliationResultsMustRespectCardinality() {
    Definition definition =
        new Definition(
            "scalar-result",
            1,
            List.of(
                new Node(
                    "root",
                    Kind.ACTIVITY,
                    List.of(),
                    "reserve",
                    Safety.NON_IDEMPOTENT,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null)));
    Event start = new Event(1, EventType.STARTED, "", "started", Instant.EPOCH);
    var wrong = List.of(Value.text("one"), Value.text("two"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    definition,
                    List.of(
                        start,
                        new Event(
                            2,
                            EventType.COMPLETED,
                            "root",
                            "completed",
                            Instant.ofEpochSecond(1),
                            wrong)),
                    Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV2()
                .replay(
                    definition,
                    List.of(
                        start,
                        new Event(
                            2,
                            EventType.UNKNOWN_OUTCOME,
                            "root",
                            "unknown",
                            Instant.ofEpochSecond(1)),
                        new Event(
                            3,
                            EventType.OPERATOR_RESOLVED,
                            "root",
                            "APPLIED",
                            Instant.ofEpochSecond(2),
                            wrong)),
                    Map.of()));
  }
}
