package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.DefinitionAnalyzer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DefinitionAnalyzerTest {
  static Node effect(
      String id, List<String> requires, String activity, String compensation, Safety safety) {
    return new Node(
        id,
        Kind.ACTIVITY,
        requires,
        activity,
        safety,
        compensation,
        Type.TEXT,
        Type.TEXT,
        0,
        null,
        null,
        null);
  }

  static Node pure(String id, List<String> requires) {
    return new Node(
        id,
        Kind.ACTIVITY,
        requires,
        "pure",
        Safety.PURE,
        null,
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

  static Node timer(String id, List<String> requires, long delay) {
    return new Node(
        id, Kind.TIMER, requires, null, null, null, Type.TEXT, Type.TEXT, delay, null, null, null);
  }

  static Definition order() {
    return new Definition(
        "order-fulfilment",
        1,
        List.of(
            effect("reserve", List.of(), "reserve", "release", Safety.IDEMPOTENT_WITH_KEY),
            signal("approval", List.of("reserve"), 3600),
            effect("ship", List.of("approval"), "ship", "refund", Safety.IDEMPOTENT_WITH_KEY)),
        Map.of("order", Type.TEXT));
  }

  private static Set<String> codes(Analysis analysis) {
    Set<String> codes = new TreeSet<>();
    analysis.findings().forEach(f -> codes.add(f.code()));
    return codes;
  }

  private static Finding only(Analysis analysis, String code) {
    List<Finding> matching =
        analysis.findings().stream().filter(f -> f.code().equals(code)).toList();
    assertEquals(1, matching.size(), code + " in " + analysis.findings());
    return matching.getFirst();
  }

  @Test
  void anInvalidDefinitionIsReportedAsOneErrorWithTheEnginesOwnReason() {
    Analysis analysis =
        DefinitionAnalyzer.analyze(new Definition("!", 1, List.of(pure("a", List.of()))));
    assertFalse(analysis.valid());
    assertEquals(1, analysis.findings().size());
    Finding finding = analysis.findings().getFirst();
    assertEquals(Severity.ERROR, finding.severity());
    assertEquals("INVALID_DEFINITION", finding.code());
    assertNull(finding.node());
    assertEquals("the engine would refuse this definition: definition bounds", finding.message());
    assertEquals(0, analysis.metrics().nodes());
    assertEquals(1, analysis.count(Severity.ERROR));
    assertEquals(0, analysis.count(Severity.WARNING));
  }

  @Test
  void aTimeoutThatSkipsTheShipmentLeavesTheReservationAppliedAndIsFlagged() {
    Analysis analysis = DefinitionAnalyzer.analyze(order());
    assertTrue(analysis.valid());
    assertEquals(Set.of("TIMEOUT_LEAVES_EFFECT_APPLIED"), codes(analysis));
    Finding finding = only(analysis, "TIMEOUT_LEAVES_EFFECT_APPLIED");
    assertEquals(Severity.WARNING, finding.severity());
    assertEquals("approval", finding.node());
    assertTrue(finding.message().contains("'reserve'"), finding.message());
    assertTrue(finding.message().contains("1 dependant"), finding.message());
    assertTrue(finding.message().contains("status.approval==TIMED_OUT"), finding.message());
    assertEquals(new Metrics(3, 2, 2, 2, 2, 1, 0, 0, 0, 3, 1, 1, 3600), analysis.metrics());
  }

  @Test
  void aBranchOnTheTimeoutOutcomeIsAcceptedAsHandlingIt() {
    Node decide =
        new Node(
            "timed-out",
            Kind.BRANCH,
            List.of("approval"),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "status.approval==TIMED_OUT",
            null);
    Node release =
        new Node(
            "release",
            Kind.ACTIVITY,
            List.of("timed-out"),
            "release",
            Safety.IDEMPOTENT_WITH_KEY,
            null,
            Type.BOOLEAN,
            Type.TEXT,
            0,
            null,
            null,
            "timed-out==true");
    Definition handled =
        new Definition(
            "order-fulfilment",
            2,
            List.of(
                effect("reserve", List.of(), "reserve", "release", Safety.IDEMPOTENT_WITH_KEY),
                signal("approval", List.of("reserve"), 3600),
                decide,
                release));
    Analysis analysis = DefinitionAnalyzer.analyze(handled);
    assertTrue(analysis.valid(), analysis.findings().toString());
    assertFalse(codes(analysis).contains("TIMEOUT_LEAVES_EFFECT_APPLIED"));
    assertFalse(codes(analysis).contains("UNUSED_BRANCH"), "the branch is required by 'release'");
  }

  @Test
  void aTimeoutWithNothingToUndoIsOnlyInformational() {
    Definition definition =
        new Definition(
            "notify",
            1,
            List.of(
                pure("start", List.of()),
                signal("wait", List.of("start"), 60),
                effect("send", List.of("wait"), "publish", null, Safety.IDEMPOTENT_WITH_KEY)));
    Finding finding = only(DefinitionAnalyzer.analyze(definition), "TIMEOUT_SKIPS_DEPENDANTS");
    assertEquals(Severity.INFO, finding.severity());
    assertEquals("wait", finding.node());
    assertTrue(finding.message().contains("1 dependant node(s)"));
    Definition last =
        new Definition(
            "tail", 1, List.of(pure("start", List.of()), signal("wait", List.of("start"), 60)));
    assertFalse(codes(DefinitionAnalyzer.analyze(last)).contains("TIMEOUT_SKIPS_DEPENDANTS"));
  }

  @Test
  void aZeroTimeoutSignalIsFlaggedAndNotAlsoReportedAsASkippingTimeout() {
    Definition definition =
        new Definition(
            "race",
            1,
            List.of(
                effect("hold", List.of(), "reserve", "release", Safety.IDEMPOTENT_WITH_KEY),
                signal("go", List.of("hold"), 0),
                effect("act", List.of("go"), "ship", "refund", Safety.IDEMPOTENT_WITH_KEY)));
    Analysis analysis = DefinitionAnalyzer.analyze(definition);
    assertEquals(Set.of("SIGNAL_TIMES_OUT_IMMEDIATELY"), codes(analysis));
    assertEquals("go", only(analysis, "SIGNAL_TIMES_OUT_IMMEDIATELY").node());
    assertEquals(Severity.WARNING, only(analysis, "SIGNAL_TIMES_OUT_IMMEDIATELY").severity());
  }

  @Test
  void anEffectThatLaterFailuresCannotUndoIsFlaggedOnlyWhenALaterEffectExists() {
    Definition definition =
        new Definition(
            "chain",
            1,
            List.of(
                effect("first", List.of(), "reserve", null, Safety.IDEMPOTENT_WITH_KEY),
                effect("second", List.of("first"), "charge", null, Safety.IDEMPOTENT_WITH_KEY),
                pure("tidy", List.of("second"))));
    Analysis analysis = DefinitionAnalyzer.analyze(definition);
    Finding finding = only(analysis, "EFFECT_NEVER_UNDONE");
    assertEquals("first", finding.node());
    assertTrue(finding.message().contains("'second'"));
    assertEquals(
        1,
        analysis.findings().stream().filter(f -> f.code().equals("EFFECT_NEVER_UNDONE")).count());
  }

  @Test
  void manyLaterEffectsAreSummarisedRatherThanListedInFull() {
    List<Node> nodes = new ArrayList<>();
    nodes.add(effect("root", List.of(), "reserve", null, Safety.IDEMPOTENT_WITH_KEY));
    for (int i = 0; i < 5; i++)
      nodes.add(
          effect("later-" + i, List.of("root"), "charge", "refund", Safety.IDEMPOTENT_WITH_KEY));
    Finding finding =
        only(DefinitionAnalyzer.analyze(new Definition("fan", 1, nodes)), "EFFECT_NEVER_UNDONE");
    assertTrue(
        finding.message().contains("'later-0', 'later-1', 'later-2' or 2 more"), finding.message());
    List<Node> two = List.of(nodes.get(0), nodes.get(1), nodes.get(2));
    assertTrue(
        only(DefinitionAnalyzer.analyze(new Definition("two", 1, two)), "EFFECT_NEVER_UNDONE")
            .message()
            .contains("'later-0' or 'later-1'"));
  }

  @Test
  void nonIdempotentEffectsCarryTheOperatorReconciliationNote() {
    Definition definition =
        new Definition(
            "once",
            1,
            List.of(effect("charge", List.of(), "charge", "refund", Safety.NON_IDEMPOTENT)));
    Finding finding = only(DefinitionAnalyzer.analyze(definition), "NON_IDEMPOTENT_EFFECT");
    assertEquals(Severity.INFO, finding.severity());
    assertEquals("charge", finding.node());
    assertTrue(finding.message().contains("UNKNOWN_OUTCOME"));
  }

  @Test
  void unusedBranchesAndDelaylessTimersAreReported() {
    Node flag =
        new Node(
            "flag",
            Kind.BRANCH,
            List.of("start"),
            null,
            null,
            null,
            Type.TEXT,
            Type.BOOLEAN,
            0,
            null,
            "input.enabled==true",
            null);
    Definition definition =
        new Definition(
            "idle",
            1,
            List.of(pure("start", List.of()), flag, timer("pause", List.of("start"), 0)),
            Map.of("enabled", Type.BOOLEAN));
    Analysis analysis = DefinitionAnalyzer.analyze(definition);
    assertTrue(analysis.valid(), analysis.findings().toString());
    assertEquals(Severity.WARNING, only(analysis, "UNUSED_BRANCH").severity());
    assertEquals("flag", only(analysis, "UNUSED_BRANCH").node());
    assertEquals("pause", only(analysis, "TIMER_WITHOUT_DELAY").node());
    assertEquals(Severity.INFO, only(analysis, "TIMER_WITHOUT_DELAY").severity());
  }

  @Test
  void aGuardCountsAsUsingItsBranch() {
    Node flag =
        new Node(
            "flag",
            Kind.BRANCH,
            List.of("start"),
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
            "guarded",
            1,
            List.of(pure("start", List.of()), flag, guarded),
            Map.of("enabled", Type.BOOLEAN));
    assertFalse(codes(DefinitionAnalyzer.analyze(definition)).contains("UNUSED_BRANCH"));
  }

  @Test
  void shapeMetricsCoverEveryNodeKindAndTheLongestWait() {
    Node join =
        new Node(
            "join",
            Kind.PARALLEL_ALL,
            List.of("left", "right"),
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null);
    Definition definition =
        new Definition(
            "diamond",
            1,
            List.of(
                pure("start", List.of()),
                timer("left", List.of("start"), 100),
                signal("right", List.of("start"), 250),
                join,
                timer("after", List.of("join"), 7)));
    Metrics m = DefinitionAnalyzer.analyze(definition).metrics();
    assertEquals(5, m.nodes());
    assertEquals(5, m.edges());
    assertEquals(1, m.activities());
    assertEquals(0, m.effects());
    assertEquals(0, m.compensable());
    assertEquals(1, m.signals());
    assertEquals(2, m.timers());
    assertEquals(0, m.branches());
    assertEquals(1, m.joins());
    assertEquals(4, m.longestChain());
    assertEquals(2, m.maxFanOut());
    assertEquals(2, m.maxFanIn());
    assertEquals(257, m.longestWaitSeconds(), "the join waits for the slower of the two, then 7 s");
  }

  @Test
  void definitionsNearTheEnginesBoundsAreWarnedAboutBeforeTheyFailAtDeploy() {
    List<Node> chain = new ArrayList<>();
    chain.add(pure("n0", List.of()));
    for (int i = 1; i < 96; i++) chain.add(pure("n" + i, List.of("n" + (i - 1))));
    assertEquals(
        Set.of("NEAR_NODE_LIMIT"),
        codes(DefinitionAnalyzer.analyze(new Definition("long", 1, chain))));
    assertEquals(
        Set.of(),
        codes(DefinitionAnalyzer.analyze(new Definition("short", 1, chain.subList(0, 95)))));

    List<Node> wide = new ArrayList<>();
    wide.add(pure("root", List.of()));
    List<String> leaves = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      wide.add(pure("leaf-" + i, List.of("root")));
      leaves.add("leaf-" + i);
    }
    wide.add(
        new Node(
            "join",
            Kind.PARALLEL_ALL,
            leaves,
            null,
            null,
            null,
            Type.TEXT,
            Type.TEXT,
            0,
            null,
            null,
            null));
    Analysis analysis = DefinitionAnalyzer.analyze(new Definition("wide", 1, wide));
    assertEquals(Set.of("NEAR_FAN_OUT_LIMIT", "NEAR_REQUIRES_LIMIT"), codes(analysis));
    assertTrue(only(analysis, "NEAR_FAN_OUT_LIMIT").message().contains("12 dependants"));
    assertTrue(only(analysis, "NEAR_REQUIRES_LIMIT").message().contains("12 predecessors"));
  }

  @Test
  void findingsAreOrderedBySeverityThenNodeThenCodeAndRepeatable() {
    Definition definition =
        new Definition(
            "mixed",
            1,
            List.of(
                effect("zeta", List.of(), "reserve", null, Safety.NON_IDEMPOTENT),
                effect("alpha", List.of("zeta"), "charge", null, Safety.IDEMPOTENT_WITH_KEY),
                signal("gate", List.of("alpha"), 0)));
    Analysis analysis = DefinitionAnalyzer.analyze(definition);
    List<String> order =
        analysis.findings().stream()
            .map(f -> f.severity() + ":" + f.node() + ":" + f.code())
            .toList();
    assertEquals(
        List.of(
            "WARNING:gate:SIGNAL_TIMES_OUT_IMMEDIATELY",
            "WARNING:zeta:EFFECT_NEVER_UNDONE",
            "INFO:zeta:NON_IDEMPOTENT_EFFECT"),
        order.stream().filter(s -> !s.contains("alpha")).toList());
    assertTrue(
        order.contains("WARNING:alpha:EFFECT_NEVER_UNDONE") == false, "alpha has no later effect");
    assertEquals(analysis, DefinitionAnalyzer.analyze(definition));
  }
}
