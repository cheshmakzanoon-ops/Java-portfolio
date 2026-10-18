package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.durableflow.LegacyDefinitionV1.*;
import io.github.cheshmakzanoon.portfolio.durableflow.LegacyInterpreterV1.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LegacyInterpreterV1Test {
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
      LegacyDefinitionV1 definition = new LegacyDefinitionV1("chain", 1, nodes);
      Result result = new LegacyInterpreterV1().replay(definition, history, Map.of());
      assertEquals(done == length ? State.COMPLETED : State.RUNNING, result.state());
      assertEquals(
          done == length ? List.of() : List.of("n" + done),
          result.decisions().stream().map(Decision::id).toList());
      assertEquals(
          result, new LegacyInterpreterV1().replay(definition, List.copyOf(history), Map.of()));
    }
  }

  @Test
  void reverseCompletionCompensationAndVisibleFailure() {
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1(
            "parallel",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("a", Kind.ACTIVITY, List.of("root"), "refund"),
                node("b", Kind.ACTIVITY, List.of("root"), "unpublish")));
    List<Event> h =
        new ArrayList<>(
            List.of(
                event(1, EventType.STARTED, ""),
                event(2, EventType.COMPLETED, "root"),
                event(3, EventType.COMPLETED, "b"),
                event(4, EventType.COMPLETED, "a"),
                event(5, EventType.CANCELLED, "")));
    LegacyInterpreterV1 i = new LegacyInterpreterV1();
    assertEquals("comp-a", i.replay(d, h, Map.of()).decisions().getFirst().id());
    h.add(event(6, EventType.COMPLETED, "comp-a"));
    assertEquals("comp-b", i.replay(d, h, Map.of()).decisions().getFirst().id());
    h.add(event(7, EventType.FAILED, "comp-b"));
    assertEquals(State.COMPENSATION_FAILED, i.replay(d, h, Map.of()).state());
  }

  @Test
  void unknownRequiresRecordedOperatorDecision() {
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1("one", 1, List.of(node("root", Kind.ACTIVITY, List.of(), null)));
    List<Event> h =
        new ArrayList<>(
            List.of(event(1, EventType.STARTED, ""), event(2, EventType.UNKNOWN_OUTCOME, "root")));
    assertEquals(State.MANUAL_REVIEW, new LegacyInterpreterV1().replay(d, h, Map.of()).state());
    h.add(new Event(3, EventType.OPERATOR_RESOLVED, "root", "APPLIED", Instant.EPOCH));
    assertEquals(State.COMPLETED, new LegacyInterpreterV1().replay(d, h, Map.of()).state());
  }

  @Test
  void rejectedAndDispatchEventsDoNotChangeLogicalDecisions() {
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1("one", 1, List.of(node("root", Kind.TIMER, List.of(), null)));
    List<Event> h =
        List.of(
            event(1, EventType.STARTED, ""),
            event(2, EventType.LATE_EVENT, "root"),
            event(3, EventType.LATE_EVENT, "root"));
    assertEquals(State.WAITING, new LegacyInterpreterV1().replay(d, h, Map.of()).state());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV1()
                .replay(d, List.of(event(2, EventType.STARTED, "")), Map.of()));
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
            "pure",
            Safety.PURE,
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
            "pure",
            Safety.PURE,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "choice==false");
    Node join = node("join", Kind.PARALLEL_ALL, List.of("yes", "no"), null);
    LegacyDefinitionV1 d = new LegacyDefinitionV1("branch", 1, List.of(root, yes, no, join));
    LegacyInterpreterV1 i = new LegacyInterpreterV1();
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
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1(
            "one", 1, List.of(node("root", Kind.ACTIVITY, List.of(), "release")));
    LegacyInterpreterV1 i = new LegacyInterpreterV1();
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
  void corruptAndOutOfOrderHistoriesAreRejected() {
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1(
            "guarded",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), "release"),
                node("wait", Kind.TIMER, List.of("root"), null)));
    LegacyInterpreterV1 engine = new LegacyInterpreterV1();
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
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1("one", 1, List.of(node("root", Kind.ACTIVITY, List.of(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV1()
                .replay(
                    d,
                    List.of(
                        event(1, EventType.STARTED, ""),
                        event(2, EventType.COMPLETED, "root"),
                        event(3, EventType.COMPLETED, "root")),
                    Map.of()));
    LegacyDefinitionV1 timer =
        new LegacyDefinitionV1("timer", 1, List.of(node("wait", Kind.TIMER, List.of(), null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LegacyInterpreterV1()
                .replay(
                    timer,
                    List.of(
                        event(1, EventType.STARTED, ""), event(2, EventType.DISPATCHED, "wait")),
                    Map.of()));
  }

  @Test
  void derivedJoinPreservesDurableTimerAnchor() {
    LegacyDefinitionV1 d =
        new LegacyDefinitionV1(
            "join",
            1,
            List.of(
                node("root", Kind.ACTIVITY, List.of(), null),
                node("joined", Kind.PARALLEL_ALL, List.of("root"), null),
                node("wait", Kind.TIMER, List.of("joined"), null)));
    Result result =
        new LegacyInterpreterV1()
            .replay(
                d,
                List.of(
                    event(1, EventType.STARTED, ""),
                    new Event(2, EventType.COMPLETED, "root", "done", Instant.ofEpochSecond(100))),
                Map.of());
    assertEquals(Instant.ofEpochSecond(101), result.decisions().getFirst().due());
  }
}
