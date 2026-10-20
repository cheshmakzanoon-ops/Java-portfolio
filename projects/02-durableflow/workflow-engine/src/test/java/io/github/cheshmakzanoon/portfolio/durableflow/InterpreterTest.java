package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class InterpreterTest {
  static Node node(String id, Kind kind, List<String> requires, String compensation) {
    return new Node(
        id,
        kind,
        requires,
        "reserve",
        Safety.IDEMPOTENT_WITH_KEY,
        compensation,
        Type.TEXT,
        Type.TEXT,
        1,
        "approval",
        null,
        null);
  }

  static Event event(long sequence, EventType type, String id) {
    return new Event(sequence, type, id, "ok", Instant.ofEpochSecond(sequence));
  }

  @Test
  void generatedHistoriesMatchIndependentCompletedPrefixModel() {
    Random random = new Random(42);
    for (int sample = 0; sample < 1000; sample++) {
      int length = 1 + random.nextInt(32), done = random.nextInt(length + 1);
      List<Node> nodes = new ArrayList<>();
      List<Event> history = new ArrayList<>();
      history.add(event(1, EventType.STARTED, ""));
      for (int n = 0; n < length; n++)
        nodes.add(node("n" + n, Kind.ACTIVITY, n == 0 ? List.of() : List.of("n" + (n - 1)), null));
      for (int n = 0; n < done; n++) history.add(event(n + 2, EventType.COMPLETED, "n" + n));
      Definition definition = new Definition("chain", 1, nodes);
      Result result = new Interpreter().replay(definition, history, Map.of());
      assertEquals(done == length ? State.COMPLETED : State.RUNNING, result.state());
      assertEquals(
          done == length ? List.of() : List.of("n" + done),
          result.decisions().stream().map(Decision::id).toList());
      assertEquals(result, new Interpreter().replay(definition, List.copyOf(history), Map.of()));
    }
  }

  @Test
  void reverseCompletionCompensationAndVisibleFailure() {
    Definition d =
        new Definition(
            "parallel",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("a", Kind.ACTIVITY, List.of("root"), "refund"),
                node("b", Kind.ACTIVITY, List.of("root"), "unpublish"),
                node("wait", Kind.SIGNAL, List.of("a", "b"), null)));
    List<Event> h =
        new ArrayList<>(
            List.of(
                event(1, EventType.STARTED, ""),
                event(2, EventType.COMPLETED, "root"),
                event(3, EventType.COMPLETED, "b"),
                event(4, EventType.COMPLETED, "a"),
                event(5, EventType.CANCELLED, "")));
    Interpreter i = new Interpreter();
    assertEquals("comp-a", i.replay(d, h, Map.of()).decisions().getFirst().id());
    h.add(event(6, EventType.COMPLETED, "comp-a"));
    assertEquals("comp-b", i.replay(d, h, Map.of()).decisions().getFirst().id());
    h.add(event(7, EventType.FAILED, "comp-b"));
    assertEquals(State.COMPENSATION_FAILED, i.replay(d, h, Map.of()).state());
  }

  @Test
  void replayRejectsOutOfOrderCompensationRatherThanTrustingPersistedTaskNames() {
    Definition definition =
        new Definition(
            "ordered-undo",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("a", Kind.ACTIVITY, List.of("root"), "refund"),
                node("b", Kind.ACTIVITY, List.of("root"), "unpublish"),
                node("wait", Kind.SIGNAL, List.of("a", "b"), null)));
    List<Event> prefix =
        List.of(
            event(1, EventType.STARTED, ""),
            event(2, EventType.COMPLETED, "root"),
            event(3, EventType.COMPLETED, "b"),
            event(4, EventType.COMPLETED, "a"),
            event(5, EventType.CANCELLED, ""));
    Interpreter interpreter = new Interpreter();
    for (EventType invalid :
        List.of(
            EventType.COMPLETED,
            EventType.DISPATCHED,
            EventType.FAILED,
            EventType.UNKNOWN_OUTCOME)) {
      List<Event> corrupted = new ArrayList<>(prefix);
      corrupted.add(event(6, invalid, "comp-b"));
      assertThrows(
          IllegalArgumentException.class,
          () -> interpreter.replay(definition, corrupted, Map.of()));
    }
    List<Event> legal = new ArrayList<>(prefix);
    for (String undo : List.of("comp-a", "comp-b", "comp-root")) {
      assertEquals(
          undo, interpreter.replay(definition, legal, Map.of()).decisions().getFirst().id());
      legal.add(event(legal.size() + 1, EventType.COMPLETED, undo));
    }
    assertEquals(State.CANCELLED, interpreter.replay(definition, legal, Map.of()).state());
    assertTrue(interpreter.replay(definition, legal, Map.of()).decisions().isEmpty());
  }

  @Test
  void failedTasksAndCompensationRequireTheirExactRetryAuthority() {
    Definition definition =
        new Definition(
            "retry-undo",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("wait", Kind.SIGNAL, List.of("root"), null)));
    Interpreter interpreter = new Interpreter();
    List<Event> forwardFailed =
        new ArrayList<>(
            List.of(event(1, EventType.STARTED, ""), event(2, EventType.FAILED, "root")));
    for (EventType type :
        List.of(EventType.COMPLETED, EventType.OPERATOR_RETRY, EventType.DISPATCHED)) {
      List<Event> invalid = new ArrayList<>(forwardFailed);
      invalid.add(event(3, type, "root"));
      assertThrows(
          IllegalArgumentException.class, () -> interpreter.replay(definition, invalid, Map.of()));
    }
    List<Event> undo =
        new ArrayList<>(
            List.of(
                event(1, EventType.STARTED, ""),
                event(2, EventType.COMPLETED, "root"),
                event(3, EventType.CANCELLED, "")));
    List<Event> premature = new ArrayList<>(undo);
    premature.add(event(4, EventType.OPERATOR_RETRY, "comp-root"));
    assertThrows(
        IllegalArgumentException.class, () -> interpreter.replay(definition, premature, Map.of()));
    undo.add(event(4, EventType.FAILED, "comp-root"));
    List<Event> unapproved = new ArrayList<>(undo);
    unapproved.add(event(5, EventType.COMPLETED, "comp-root"));
    assertThrows(
        IllegalArgumentException.class, () -> interpreter.replay(definition, unapproved, Map.of()));
    undo.add(event(5, EventType.OPERATOR_RETRY, "comp-root"));
    assertEquals(State.COMPENSATING, interpreter.replay(definition, undo, Map.of()).state());
    undo.add(event(6, EventType.COMPLETED, "comp-root"));
    assertEquals(State.CANCELLED, interpreter.replay(definition, undo, Map.of()).state());
  }

  @Test
  void activityFailuresCannotBeAttachedToSignalsAndSignalResultsKeepTheirDeclaredType() {
    Definition signal =
        new Definition("signal-kinds", 1, List.of(node("root", Kind.SIGNAL, List.of(), null)));
    for (EventType type : List.of(EventType.UNKNOWN_OUTCOME, EventType.FAILED))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new Interpreter()
                  .replay(
                      signal,
                      List.of(event(1, EventType.STARTED, ""), event(2, type, "root")),
                      Map.of()));
    Definition integer =
        new Definition(
            "signal-types",
            1,
            List.of(
                new Node(
                    "root",
                    Kind.SIGNAL,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.INTEGER,
                    10,
                    "number",
                    null,
                    null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interpreter()
                .replay(
                    integer,
                    List.of(event(1, EventType.STARTED, ""), event(2, EventType.SIGNAL, "root")),
                    Map.of()));
  }

  @Test
  void mixedJoinPropagatesTimeoutAndDecisionsRetainPredecessorEvidence() {
    Definition definition =
        new Definition(
            "mixed-join",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), null),
                node("signal", Kind.SIGNAL, List.of("root"), null),
                node("other", Kind.ACTIVITY, List.of("root"), null),
                node("join", Kind.PARALLEL_ALL, List.of("signal", "other"), null)));
    Interpreter interpreter = new Interpreter();
    List<Event> history =
        new ArrayList<>(
            List.of(event(1, EventType.STARTED, ""), event(2, EventType.COMPLETED, "root")));
    var next = interpreter.replay(definition, history, Map.of());
    assertTrue(
        next.decisions().stream()
            .allMatch(
                decision ->
                    decision
                        .input()
                        .predecessors()
                        .equals(Map.of("root", Data.Output.of(List.of(Data.Value.text("ok")))))));
    history.add(event(3, EventType.TIMER_FIRED, "signal"));
    history.add(event(4, EventType.COMPLETED, "other"));
    var finished = interpreter.replay(definition, history, Map.of());
    assertEquals(Data.Output.timedOut(), finished.values().get("join"));
    assertEquals(State.COMPLETED, finished.state());
  }

  @Test
  void valueAndStatusExpressionsCanEvaluateFalseWithoutBeingSkipped() {
    Definition value =
        new Definition(
            "value-false",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), null),
                new Node(
                    "check",
                    Kind.BRANCH,
                    List.of("root"),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.BOOLEAN,
                    0,
                    null,
                    "value.root==different",
                    null)));
    var history = List.of(event(1, EventType.STARTED, ""), event(2, EventType.COMPLETED, "root"));
    assertEquals(
        Data.Output.of(List.of(Data.Value.bool(false))),
        new Interpreter().replay(value, history, Map.of()).values().get("check"));
    Definition status =
        new Definition(
            "status-false",
            1,
            List.of(
                node("root", Kind.SIGNAL, List.of(), null),
                new Node(
                    "check",
                    Kind.BRANCH,
                    List.of("root"),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.BOOLEAN,
                    0,
                    null,
                    "status.root==VALUE",
                    null)));
    assertEquals(
        Data.Output.of(List.of(Data.Value.bool(false))),
        new Interpreter()
            .replay(
                status,
                List.of(event(1, EventType.STARTED, ""), event(2, EventType.TIMER_FIRED, "root")),
                Map.of())
            .values()
            .get("check"));
  }

  @Test
  void maximumReverseDeclaredPureDagConvergesAtTheDefinedPassBoundary() {
    List<Node> nodes = new ArrayList<>();
    for (int index = 0; index < 128; index++) {
      String previous = "n" + (index - 1);
      nodes.add(
          new Node(
              "n" + index,
              Kind.BRANCH,
              index == 0 ? List.of() : List.of(previous),
              null,
              null,
              null,
              Type.BOOLEAN,
              Type.BOOLEAN,
              0,
              null,
              index == 0 ? "input.flag==true" : "value." + previous + "==true",
              null));
    }
    Collections.reverse(nodes);
    Definition definition = new Definition("reverse-dag", 1, nodes, Map.of("flag", Type.BOOLEAN));
    var input =
        new Data.ExecutionInput(Data.Value.bool(true), Map.of("flag", Data.Value.bool(true)));
    Result result =
        new Interpreter().replay(definition, List.of(event(1, EventType.STARTED, "")), input);
    assertEquals(State.COMPLETED, result.state());
    assertTrue(result.decisions().isEmpty());
    assertEquals(128, result.values().size());
    assertTrue(
        result.values().values().stream()
            .allMatch(output -> output.equals(Data.Output.of(List.of(Data.Value.bool(true))))));
  }

  @Test
  void unknownRequiresRecordedOperatorDecision() {
    Definition d = new Definition("one", 1, List.of(node("root", Kind.ACTIVITY, List.of(), null)));
    List<Event> h =
        new ArrayList<>(
            List.of(event(1, EventType.STARTED, ""), event(2, EventType.UNKNOWN_OUTCOME, "root")));
    assertEquals(State.MANUAL_REVIEW, new Interpreter().replay(d, h, Map.of()).state());
    h.add(new Event(3, EventType.OPERATOR_RESOLVED, "root", "APPLIED", Instant.EPOCH));
    assertEquals(State.COMPLETED, new Interpreter().replay(d, h, Map.of()).state());
  }

  @Test
  void rejectedAndDispatchEventsDoNotChangeLogicalDecisions() {
    Definition d = new Definition("one", 1, List.of(node("root", Kind.TIMER, List.of(), null)));
    List<Event> h =
        List.of(
            event(1, EventType.STARTED, ""),
            event(2, EventType.LATE_EVENT, "root"),
            event(3, EventType.LATE_EVENT, "root"));
    assertEquals(State.WAITING, new Interpreter().replay(d, h, Map.of()).state());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Interpreter().replay(d, List.of(event(2, EventType.STARTED, "")), Map.of()));
  }

  @Test
  void branchWhitelistAndBoundedJoin() {
    Node root =
        new Node(
            "choice",
            Kind.BRANCH,
            List.of(),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "input.approved==yes",
            null);
    Node yes =
        new Node(
            "yes",
            Kind.ACTIVITY,
            List.of("choice"),
            "reserve",
            Safety.IDEMPOTENT_WITH_KEY,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "choice==true");
    Node no =
        new Node(
            "no",
            Kind.ACTIVITY,
            List.of("choice"),
            "reserve",
            Safety.IDEMPOTENT_WITH_KEY,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "choice==false");
    Node join = node("join", Kind.PARALLEL_ALL, List.of("yes", "no"), null);
    Definition d =
        new Definition("branch", 1, List.of(root, yes, no, join), Map.of("approved", Type.TEXT));
    Interpreter i = new Interpreter();
    assertEquals(
        List.of("yes"),
        i
            .replay(d, List.of(event(1, EventType.STARTED, "")), Map.of("approved", "yes"))
            .decisions()
            .stream()
            .map(Decision::id)
            .toList());
    assertEquals(
        State.COMPLETED,
        i.replay(
                d,
                List.of(event(1, EventType.STARTED, ""), event(2, EventType.COMPLETED, "yes")),
                Map.of("approved", "yes"))
            .state());
  }

  @Test
  void terminalFailureCancellationAndCompensationCompletion() {
    Definition d =
        new Definition(
            "one",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("wait", Kind.SIGNAL, List.of("root"), null)));
    Interpreter i = new Interpreter();
    assertEquals(
        State.FAILED,
        i.replay(
                d,
                List.of(event(1, EventType.STARTED, ""), event(2, EventType.FAILED, "root")),
                Map.of())
            .state());
    assertEquals(
        State.CANCELLED,
        i.replay(
                d,
                List.of(event(1, EventType.STARTED, ""), event(2, EventType.CANCELLED, "")),
                Map.of())
            .state());
    assertEquals(
        State.CANCELLED,
        i.replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    event(2, EventType.COMPLETED, "root"),
                    event(3, EventType.CANCELLED, ""),
                    event(4, EventType.COMPLETED, "comp-root")),
                Map.of())
            .state());
    assertEquals(
        State.FAILED,
        i.replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    event(2, EventType.UNKNOWN_OUTCOME, "root"),
                    new Event(
                        3, EventType.OPERATOR_RESOLVED, "root", "NOT_APPLIED", Instant.EPOCH)),
                Map.of())
            .state());
  }

  @Test
  void cancellationAfterFailureDependsOnRealUnfulfilledCompensation() {
    Interpreter interpreter = new Interpreter();
    for (String compensation : Arrays.asList(null, "release")) {
      Definition definition =
          new Definition(
              "failed-with-obligation",
              1,
              List.of(
                  node("root", Kind.ACTIVITY, List.of(), compensation),
                  node("later", Kind.ACTIVITY, List.of("root"), null)));
      List<Event> failed =
          List.of(
              event(1, EventType.STARTED, ""),
              event(2, EventType.COMPLETED, "root"),
              event(3, EventType.FAILED, "later"),
              event(4, EventType.CANCELLED, ""));
      if (compensation == null)
        assertThrows(
            IllegalArgumentException.class, () -> interpreter.replay(definition, failed, Map.of()));
      else {
        Result result = interpreter.replay(definition, failed, Map.of());
        assertEquals(State.COMPENSATING, result.state());
        assertEquals(List.of("comp-root"), result.decisions().stream().map(Decision::id).toList());
      }
    }
  }

  @Test
  void pausedForwardWorkRejectsNewDispatchButRecordsAnAlreadyAuthorizedCompletion() {
    Definition definition =
        new Definition(
            "pause-forward",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), null),
                node("left", Kind.ACTIVITY, List.of("root"), null),
                node("right", Kind.ACTIVITY, List.of("root"), null)));
    Interpreter interpreter = new Interpreter();
    for (Event pause :
        List.of(
            event(3, EventType.FAILED, "left"),
            new Event(
                3,
                EventType.CANCELLED,
                "",
                "cancelled",
                Instant.ofEpochSecond(3),
                List.of(Data.Value.integer(1))))) {
      List<Event> prefix =
          List.of(event(1, EventType.STARTED, ""), event(2, EventType.COMPLETED, "root"), pause);
      List<Event> newDispatch = new ArrayList<>(prefix);
      newDispatch.add(event(4, EventType.DISPATCHED, "right"));
      assertThrows(
          IllegalArgumentException.class,
          () -> interpreter.replay(definition, newDispatch, Map.of()));
      List<Event> acknowledged = new ArrayList<>(prefix);
      acknowledged.add(event(4, EventType.COMPLETED, "right"));
      Result result = interpreter.replay(definition, acknowledged, Map.of());
      assertEquals(
          pause.type() == EventType.CANCELLED ? State.CANCELLED : State.FAILED, result.state());
      assertEquals(Data.Output.of(List.of(Data.Value.text("ok"))), result.values().get("right"));
      assertTrue(result.decisions().isEmpty());
    }
  }

  @Test
  void corruptAndOutOfOrderHistoriesAreRejected() {
    Definition d =
        new Definition(
            "guarded",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("wait", Kind.TIMER, List.of("root"), null)));
    Interpreter engine = new Interpreter();
    assertThrows(IllegalArgumentException.class, () -> engine.replay(d, List.of(), Map.of()));
    for (Event bad :
        List.of(
            event(2, EventType.STARTED, ""),
            event(2, EventType.COMPLETED, "unknown"),
            event(2, EventType.COMPLETED, "comp-root"),
            event(2, EventType.SIGNAL, "root"),
            event(2, EventType.TIMER_FIRED, "root"),
            event(2, EventType.OPERATOR_RESOLVED, "root"),
            event(2, EventType.TIMER_FIRED, "wait"))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> engine.replay(d, List.of(event(1, EventType.STARTED, ""), bad), Map.of()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    event(2, EventType.COMPLETED, "root"),
                    event(3, EventType.COMPLETED, "wait")),
                Map.of()));
    assertEquals(
        State.COMPLETED,
        engine
            .replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    event(2, EventType.COMPLETED, "root"),
                    event(3, EventType.TIMER_FIRED, "wait")),
                Map.of())
            .state());
  }

  @Test
  void duplicateTerminalAndWrongActivityEventsAreRejected() {
    Definition d = new Definition("one", 1, List.of(node("root", Kind.ACTIVITY, List.of(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interpreter()
                .replay(
                    d,
                    List.of(
                        event(1, EventType.STARTED, ""),
                        event(2, EventType.COMPLETED, "root"),
                        event(3, EventType.COMPLETED, "root")),
                    Map.of()));
    Definition timer =
        new Definition("timer", 1, List.of(node("wait", Kind.TIMER, List.of(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Interpreter()
                .replay(
                    timer,
                    List.of(
                        event(1, EventType.STARTED, ""), event(2, EventType.DISPATCHED, "wait")),
                    Map.of()));
  }

  @Test
  void derivedJoinPreservesDurableTimerAnchor() {
    Definition d =
        new Definition(
            "join",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), null),
                node("left", Kind.ACTIVITY, List.of("root"), null),
                node("right", Kind.ACTIVITY, List.of("root"), null),
                node("joined", Kind.PARALLEL_ALL, List.of("left", "right"), null),
                node("wait", Kind.TIMER, List.of("joined"), null)));
    Result result =
        new Interpreter()
            .replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    new Event(2, EventType.COMPLETED, "root", "root", Instant.ofEpochSecond(80)),
                    new Event(3, EventType.COMPLETED, "left", "left", Instant.ofEpochSecond(90)),
                    new Event(
                        4, EventType.COMPLETED, "right", "right", Instant.ofEpochSecond(100))),
                Map.of());
    assertEquals(Instant.ofEpochSecond(101), result.decisions().getFirst().due());
  }
}
