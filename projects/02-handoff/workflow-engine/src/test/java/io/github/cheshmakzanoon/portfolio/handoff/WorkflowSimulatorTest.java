package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.Interpreter.*;
import io.github.cheshmakzanoon.portfolio.handoff.WorkflowSimulator.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkflowSimulatorTest {
  private final WorkflowSimulator simulator = new WorkflowSimulator();

  static Node effect(String id, List<String> requires, String activity, String compensation) {
    return new Node(
        id,
        Kind.ACTIVITY,
        requires,
        activity,
        Safety.IDEMPOTENT_WITH_KEY,
        compensation,
        Type.TEXT,
        Type.TEXT,
        0,
        null,
        null,
        null);
  }

  static Node signal(String id, List<String> requires, long timeout) {
    return new Node(
        id, Kind.SIGNAL, requires, null, null, null, Type.TEXT, Type.TEXT, timeout, id, null, null);
  }

  static Definition order() {
    return new Definition(
        "order-fulfilment",
        1,
        List.of(
            effect("reserve", List.of(), "reserve", "release"),
            signal("approval", List.of("reserve"), 3600),
            effect("ship", List.of("approval"), "ship", "refund")),
        Map.of("order", Type.TEXT));
  }

  private static Scenario scenario(
      Set<String> failures,
      Map<String, String> unknown,
      Map<String, String> signals,
      Integer cancelAfter) {
    return new Scenario(
        null, Map.of("order", "synthetic-1"), failures, unknown, signals, cancelAfter);
  }

  private static List<String> script(Run run) {
    return run.steps().stream()
        .map(s -> s.type() + (s.task().isEmpty() ? "" : " " + s.task()))
        .collect(Collectors.toList());
  }

  @Test
  void anUnansweredSignalTimesOutSkipsTheShipmentAndLeavesTheReservationApplied() {
    Run run = simulator.simulate(order(), scenario(Set.of(), Map.of(), Map.of(), null));
    assertEquals(State.COMPLETED, run.state());
    assertEquals(
        List.of("STARTED", "DISPATCHED reserve", "COMPLETED reserve", "TIMER_FIRED approval"),
        script(run));
    assertEquals(Duration.ofSeconds(3600), run.steps().getLast().at());
    assertEquals(Duration.ZERO, run.steps().get(2).at());
    assertEquals("timeout", run.steps().getLast().value());
    assertEquals(
        Map.of("reserve", "VALUE", "approval", "TIMED_OUT", "ship", "SKIPPED"), run.outcomes());
    assertEquals(
        List.of("reserve"), run.uncompensatedEffects(), "completion without undo: the trap");
    assertEquals(List.of(), run.compensationOrder());
    assertEquals(List.of(), run.blockedOn());
  }

  @Test
  void aDeliveredSignalLetsTheShipmentRun() {
    Run run =
        simulator.simulate(order(), scenario(Set.of(), Map.of(), Map.of("approval", "go"), null));
    assertEquals(State.COMPLETED, run.state());
    assertEquals(
        List.of(
            "STARTED",
            "DISPATCHED reserve",
            "COMPLETED reserve",
            "SIGNAL approval",
            "DISPATCHED ship",
            "COMPLETED ship"),
        script(run));
    assertEquals("go", run.steps().get(3).value());
    assertEquals(Map.of("reserve", "VALUE", "approval", "VALUE", "ship", "VALUE"), run.outcomes());
    assertEquals(List.of("reserve", "ship"), run.uncompensatedEffects());
  }

  @Test
  void aFailedShipmentCompensatesTheReservationInReverseCompletionOrder() {
    Run run =
        simulator.simulate(
            order(), scenario(Set.of("ship"), Map.of(), Map.of("approval", "go"), null));
    assertEquals(State.FAILED, run.state());
    assertEquals(
        List.of(
            "STARTED",
            "DISPATCHED reserve",
            "COMPLETED reserve",
            "SIGNAL approval",
            "DISPATCHED ship",
            "FAILED ship",
            "DISPATCHED comp-reserve",
            "COMPLETED comp-reserve"),
        script(run));
    assertEquals(List.of("reserve"), run.compensationOrder());
    assertEquals(List.of(), run.uncompensatedEffects());
    assertEquals(Map.of("reserve", "VALUE", "approval", "VALUE", "ship", "FAILED"), run.outcomes());
  }

  @Test
  void aFailedCompensationStopsInCompensationFailed() {
    Run run =
        simulator.simulate(
            order(),
            scenario(Set.of("ship", "comp-reserve"), Map.of(), Map.of("approval", "go"), null));
    assertEquals(State.COMPENSATION_FAILED, run.state());
    assertEquals(List.of(), run.compensationOrder());
    assertEquals(List.of("reserve"), run.uncompensatedEffects());
    assertEquals("FAILED comp-reserve", script(run).getLast());
  }

  @Test
  void anUnresolvedUnknownOutcomeHandsTheInstanceToAnOperator() {
    Run run =
        simulator.simulate(
            order(), scenario(Set.of(), Map.of("reserve", "UNRESOLVED"), Map.of(), null));
    assertEquals(State.MANUAL_REVIEW, run.state());
    assertEquals(List.of("reserve"), run.blockedOn());
    assertEquals(List.of("STARTED", "DISPATCHED reserve", "UNKNOWN_OUTCOME reserve"), script(run));
    assertEquals("UNKNOWN_OUTCOME", run.outcomes().get("reserve"));
    assertEquals("NOT_REACHED", run.outcomes().get("ship"));
  }

  @Test
  void anOperatorWhoFindsTheEffectAppliedLetsTheWorkflowContinue() {
    Run run =
        simulator.simulate(
            order(),
            scenario(Set.of(), Map.of("reserve", "APPLIED"), Map.of("approval", "go"), null));
    assertEquals(State.COMPLETED, run.state());
    assertEquals(
        List.of(
            "STARTED",
            "DISPATCHED reserve",
            "UNKNOWN_OUTCOME reserve",
            "OPERATOR_RESOLVED reserve",
            "SIGNAL approval",
            "DISPATCHED ship",
            "COMPLETED ship"),
        script(run));
    assertEquals("APPLIED", run.steps().get(3).value());
    assertEquals(List.of(), run.blockedOn());
  }

  @Test
  void anOperatorWhoFindsTheEffectNotAppliedFailsTheWorkflowWithNothingToUndo() {
    Run run =
        simulator.simulate(
            order(), scenario(Set.of(), Map.of("reserve", "NOT_APPLIED"), Map.of(), null));
    assertEquals(State.FAILED, run.state());
    assertEquals("NOT_APPLIED", run.steps().getLast().value());
    assertEquals("FAILED", run.outcomes().get("reserve"));
    assertEquals(List.of(), run.compensationOrder());
    assertEquals(List.of(), run.uncompensatedEffects());
  }

  @Test
  void cancellationAfterTheReservationRunsItsCompensation() {
    // STARTED, DISPATCHED reserve, COMPLETED reserve: cancel as soon as three events exist.
    Run run = simulator.simulate(order(), scenario(Set.of(), Map.of(), Map.of(), 3));
    assertEquals(State.CANCELLED, run.state());
    assertEquals(
        List.of(
            "STARTED",
            "DISPATCHED reserve",
            "COMPLETED reserve",
            "CANCELLED",
            "DISPATCHED comp-reserve",
            "COMPLETED comp-reserve"),
        script(run));
    assertEquals(List.of("reserve"), run.compensationOrder());
    assertEquals(
        Map.of("reserve", "VALUE", "approval", "NOT_REACHED", "ship", "NOT_REACHED"),
        run.outcomes());
  }

  @Test
  void parallelBranchesRunInNodeOrderAndJoinWithoutAnyUndoNeeded() {
    Node upload =
        new Node(
            "upload",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Definition media =
        new Definition(
            "media-processing",
            1,
            List.of(
                upload,
                effect("transcode", List.of("upload"), "transcode", "unpublish"),
                effect("thumbnail", List.of("upload"), "thumbnail", null),
                new Node(
                    "join",
                    Kind.PARALLEL_ALL,
                    List.of("transcode", "thumbnail"),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    0,
                    null,
                    null,
                    null),
                effect("publish", List.of("join"), "publish", "unpublish")));
    Run run = simulator.simulate(media, Scenario.happyPath());
    assertEquals(State.COMPLETED, run.state());
    assertEquals(
        List.of(
            "STARTED",
            "COMPLETED upload",
            "DISPATCHED transcode",
            "COMPLETED transcode",
            "DISPATCHED thumbnail",
            "COMPLETED thumbnail",
            "DISPATCHED publish",
            "COMPLETED publish"),
        script(run));
    assertEquals(List.of("transcode", "publish"), run.uncompensatedEffects());
    assertEquals(Collections.nCopies(5, "VALUE"), new ArrayList<>(run.outcomes().values()));
  }

  @Test
  void aFailedBranchCompensatesWhatAlreadyCompletedAndSkipsTheJoin() {
    Node upload =
        new Node(
            "upload",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Definition media =
        new Definition(
            "media-processing",
            1,
            List.of(
                upload,
                effect("transcode", List.of("upload"), "transcode", "unpublish"),
                effect("thumbnail", List.of("upload"), "thumbnail", null)));
    Run run = simulator.simulate(media, scenario0(Set.of("thumbnail")));
    assertEquals(State.FAILED, run.state());
    assertEquals(List.of("transcode"), run.compensationOrder());
    assertEquals(
        List.of(
            "STARTED",
            "COMPLETED upload",
            "DISPATCHED transcode",
            "COMPLETED transcode",
            "DISPATCHED thumbnail",
            "FAILED thumbnail",
            "DISPATCHED comp-transcode",
            "COMPLETED comp-transcode"),
        script(run));
  }

  private static Scenario scenario0(Set<String> failures) {
    return new Scenario(null, Map.of(), failures, Map.of(), Map.of(), null);
  }

  @Test
  void timersFireAtTheirDueTimeAndInputBranchesFollowTheScenarioContext() {
    Node start =
        new Node(
            "start",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Node wait =
        new Node(
            "wait",
            Kind.TIMER,
            List.of("start"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            90,
            null,
            null,
            null);
    Node flag =
        new Node(
            "flag",
            Kind.BRANCH,
            List.of("wait"),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "input.enabled==true",
            null);
    Node guarded =
        new Node(
            "work",
            Kind.ACTIVITY,
            List.of("flag"),
            "pure",
            Safety.PURE,
            null,
            Type.BOOLEAN,
            Type.BOOLEAN,
            0,
            null,
            null,
            "flag==true");
    Definition definition =
        new Definition(
            "gated", 1, List.of(start, wait, flag, guarded), Map.of("enabled", Type.BOOLEAN));

    Run on =
        simulator.simulate(
            definition, new Scenario(null, Map.of("enabled", "true"), null, null, null, null));
    assertEquals(State.COMPLETED, on.state());
    assertEquals(
        List.of("STARTED", "COMPLETED start", "TIMER_FIRED wait", "COMPLETED work"), script(on));
    assertEquals(Duration.ofSeconds(90), on.steps().get(2).at());
    assertEquals("VALUE", on.outcomes().get("work"));

    Run off =
        simulator.simulate(
            definition, new Scenario(null, Map.of("enabled", "false"), null, null, null, null));
    assertEquals(State.COMPLETED, off.state());
    assertEquals(List.of("STARTED", "COMPLETED start", "TIMER_FIRED wait"), script(off));
    assertEquals("SKIPPED", off.outcomes().get("work"));
    assertEquals("VALUE", off.outcomes().get("flag"));
  }

  @Test
  void typedPureActivitiesComputeTheirOwnResults() {
    Node number =
        new Node(
            "number",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.INTEGER,
            Type.INTEGER,
            0,
            null,
            null,
            null);
    Node total =
        new Node(
            "total",
            Kind.ACTIVITY,
            List.of("number"),
            "sum",
            Safety.PURE,
            null,
            Type.INTEGER,
            Type.INTEGER,
            0,
            null,
            null,
            null);
    Run sum =
        simulator.simulate(
            new Definition("sums", 1, List.of(number, total)),
            new Scenario("41", null, null, null, null, null));
    assertEquals(State.COMPLETED, sum.state());
    assertEquals(List.of("STARTED", "COMPLETED number", "COMPLETED total"), script(sum));
    Node truth =
        new Node(
            "truth",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.BOOLEAN,
            Type.BOOLEAN,
            0,
            null,
            null,
            null);
    Node inverse =
        new Node(
            "inverse",
            Kind.ACTIVITY,
            List.of("truth"),
            "not",
            Safety.PURE,
            null,
            Type.BOOLEAN,
            Type.BOOLEAN,
            0,
            null,
            null,
            null);
    assertEquals(
        State.COMPLETED,
        simulator
            .simulate(new Definition("logic", 1, List.of(truth, inverse)), Scenario.happyPath())
            .state());
  }

  @Test
  void aBooleanSignalValueIsParsedAsItsDeclaredType() {
    Node start =
        new Node(
            "start",
            Kind.ACTIVITY,
            List.of(),
            "pure",
            Safety.PURE,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Node vote =
        new Node(
            "vote",
            Kind.SIGNAL,
            List.of("start"),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            60,
            "vote",
            null,
            null);
    Definition definition = new Definition("ballot", 1, List.of(start, vote));
    Run run =
        simulator.simulate(
            definition, new Scenario(null, null, null, null, Map.of("vote", "true"), null));
    assertEquals(State.COMPLETED, run.state());
    assertEquals("SIGNAL vote", script(run).getLast());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            simulator.simulate(
                definition, new Scenario(null, null, null, null, Map.of("vote", "yes"), null)));
  }

  @Test
  void simulationsAreDeterministicAndEveryHistoryIsAcceptedByTheInterpreter() {
    Scenario scenario = scenario(Set.of("ship"), Map.of(), Map.of("approval", "go"), null);
    assertEquals(simulator.simulate(order(), scenario), simulator.simulate(order(), scenario));
    // Replaying the run's own events reproduces its state: the simulator adds nothing of its own.
    Run run = simulator.simulate(order(), scenario);
    List<Event> history = new ArrayList<>();
    for (Step step : run.steps())
      history.add(
          new Event(
              step.sequence(),
              step.type(),
              step.task(),
              step.value(),
              WorkflowSimulator.START.plus(step.at()),
              step.type() == EventType.COMPLETED || step.type() == EventType.SIGNAL
                  ? List.of(
                      Data.Value.text(
                          step.type() == EventType.SIGNAL ? step.value() : "ok:" + step.task()))
                  : List.of()));
    Result replayed = new Interpreter().replay(order(), history, Map.of("order", "synthetic-1"));
    assertEquals(run.state(), replayed.state());
  }

  @Test
  void invalidScenariosAndDefinitionsAreRefusedBeforeAnythingRuns() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Scenario(null, null, null, Map.of("a", "MAYBE"), null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new Scenario(null, null, null, null, null, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            simulator.simulate(
                order(), new Scenario(null, Map.of("other", "x"), null, null, null, null)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            simulator.simulate(
                new Definition("!", 1, order().nodes(), Map.of()), Scenario.happyPath()));
    // The data algebra's own rule still applies to scripted values.
    assertThrows(
        IllegalArgumentException.class,
        () -> simulator.simulate(order(), new Scenario("my-secret", null, null, null, null, null)));
  }
}
