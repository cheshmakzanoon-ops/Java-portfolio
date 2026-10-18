package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EngineTest {
  static Engine.State state(int partitions) {
    return new Engine.State(
        "r", new Engine.Limits(partitions, 50_000, 100_000, 1_000_000, 1_000_000), 0);
  }

  static Engine.Event event(String id, long time, long value) {
    return new Engine.Event(1, "r", id, "t", "a", time, value, false);
  }

  static Engine.Event progress(long time) {
    return new Engine.Event(1, "r", "p" + time, "t", "a", time, 0, true);
  }

  static Engine.State add(Engine.State s, int p, Engine.Event e, long now) {
    if (e.progress())
      e =
          new Engine.Event(
              e.schemaVersion(),
              e.runId(),
              e.eventId() + "p" + p,
              e.tenantId(),
              e.sensorId(),
              e.eventTimeEpochMillis(),
              e.valueMilliUnits(),
              true);
    return Engine.transition(
        s, List.of(Engine.Input.of(p, s.offsets.getOrDefault(p, 0L), e)), List.of(), now);
  }

  @Test
  void exactDemoLateAndDuplicateWithIndependentSlidingOutcomes() {
    Engine.State s = state(2);
    s = add(s, 0, event("10", 0, 10), 0);
    s = add(s, 0, event("20", 20_000, 20), 20_000);
    s = add(s, 1, progress(65_000), 65_000);
    s = add(s, 0, progress(65_000), 65_000);
    var w = new Engine.Window(new Engine.Key("t", "a"), "TUMBLING", 0);
    assertEquals(30, s.results.get(w).aggregate().sum());
    assertFalse(s.results.get(w).finalized());
    s = add(s, 0, event("5", 50_000, 5), 65_000);
    assertEquals(35, s.results.get(w).aggregate().sum());
    assertEquals(2, s.results.get(w).revision());
    s = s.afterRestart(65_000);
    assertEquals(60_000, s.watermark);
    assertFalse(s.partitions.get(0).idle());
    s = Engine.transition(s, List.of(), List.of(new Engine.Observation(0, false, 0, 0)), 95_000);
    assertTrue(s.partitions.get(0).idle());
    s = add(s, 1, progress(105_000), 100_000);
    assertEquals(100_000, s.watermark);
    assertTrue(s.results.get(w).finalized());
    assertEquals(3, s.results.get(w).revision());
    assertEquals(35, s.results.get(w).aggregate().sum());
    s = add(s, 0, event("resume", 40_000, 9), 100_000);
    assertEquals(100_000, s.watermark);
    assertEquals(2, s.late.size()); // tumbling and oldest sliding window
    assertTrue(s.late.stream().anyMatch(r -> r.windowId().contains("TUMBLING")));
    var sliding = new Engine.Window(new Engine.Key("t", "a"), "SLIDING", 0);
    assertEquals(44, s.panes.values().stream().mapToLong(Engine.Aggregate::sum).sum());
    s = add(s, 0, event("20", 20_000, 20), 100_000);
    assertEquals(1, s.duplicates);
    s = add(s, 1, progress(400_000), 400_000);
    s = add(s, 0, progress(400_000), 400_000);
    assertEquals(44, s.results.get(sliding).aggregate().sum());
  }

  @Test
  void sourceSilenceRequiresEligibleNoBacklogAndAllIdleHolds() {
    var s = add(state(2), 0, progress(65_000), 65_000);
    assertEquals(Long.MIN_VALUE, s.watermark);
    for (var o :
        List.of(
            new Engine.Observation(1, true, 0, 0),
            new Engine.Observation(1, false, 2, 0),
            new Engine.Observation(1, false, 0, 1))) {
      s = Engine.transition(s, List.of(), List.of(o), 100_000);
      assertFalse(s.partitions.get(1).idle());
      s = Engine.transition(s, List.of(), List.of(new Engine.Observation(1, false, 0, 0)), 110_000);
      assertFalse(s.partitions.get(1).idle());
    }
    s = Engine.transition(s, List.of(), List.of(new Engine.Observation(1, false, 0, 0)), 140_000);
    assertEquals(60_000, s.watermark);
    s = Engine.transition(s, List.of(), List.of(new Engine.Observation(0, false, 0, 0)), 150_000);
    assertTrue(s.partitions.values().stream().allMatch(Engine.Partition::idle));
    assertEquals(60_000, s.watermark);
    s = s.afterRestart(150_000);
    assertFalse(s.partitions.values().stream().anyMatch(Engine.Partition::idle));
    assertEquals(60_000, s.watermark);
  }

  @Test
  void invalidRecordsConflictOverflowOffsetsAndQuotasAreAtomic() {
    var s = state(1);
    var before = s;
    s = add(s, 0, event("huge", 0, Long.MAX_VALUE), 0);
    assertTrue(before.panes.isEmpty());
    s = add(s, 0, event("overflow", 0, 1), 0);
    assertEquals("ARITHMETIC_OVERFLOW", s.quarantine.getFirst().reason());
    assertEquals(Long.MAX_VALUE, s.panes.values().iterator().next().sum());
    s = add(s, 0, event("huge", 0, 4), 0);
    assertEquals("CONFLICTING_EVENT_ID", s.quarantine.getFirst().reason());
    s =
        Engine.transition(
            s, List.of(new Engine.Input(0, 10, 14, "bad", null, "json")), List.of(), 0);
    assertEquals(14, s.offsets.get(0));
    final var copy = s;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Engine.transition(
                copy, List.of(Engine.Input.of(0, 1, event("e", 0, 1))), List.of(), 0));
    s = add(s, 0, event("future", 2_000_000, 4), 0);
    assertEquals("INVALID_RECORD", s.quarantine.getFirst().reason());
    var small = new Engine.State("r", new Engine.Limits(1, 1, 1, 10, 0), 0);
    small = add(small, 0, event("e1", 0, 1), 0);
    small = add(small, 0, new Engine.Event(1, "r", "e2", "t", "b", 0, 1, false), 0);
    assertEquals("KEY_QUOTA", small.quarantine.getFirst().reason());
    small = add(small, 0, event("e3", 60_000, 1), 60_000);
    assertEquals("PANE_QUOTA", small.quarantine.getFirst().reason());
    var quota = new Engine.State("r", new Engine.Limits(1, 1, 1, 1, 0), 0);
    quota = add(quota, 0, event("a", 0, 1), 0);
    quota = add(quota, 0, event("b", 0, 1), 0);
    assertEquals("RUN_EVENT_QUOTA", quota.quarantine.getFirst().reason());
    assertThrows(IllegalArgumentException.class, () -> new Engine.Limits(0, 1, 1, 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Engine.State("bad:", new Engine.Limits(1, 1, 1, 1, 0), 0));
    assertThrows(IllegalArgumentException.class, () -> new Engine.Input(0, 1, 1, "", null, null));
    assertThrows(IllegalArgumentException.class, () -> new Engine.Observation(0, false, -1, 0));
    assertEquals(
        new Engine.Aggregate(0, 0, Long.MAX_VALUE, Long.MIN_VALUE), Engine.Aggregate.empty());
  }

  @Test
  void slidingMergeOverflowAndClockBoundsPreserveState() {
    var s = add(state(2), 0, event("max", 0, Long.MAX_VALUE), 0);
    var before = s;
    s = add(s, 0, event("cross", 60_000, 1), 60_000);
    assertEquals("ARITHMETIC_OVERFLOW", s.quarantine.getFirst().reason());
    assertEquals(before.panes, s.panes);
    s = add(s, 0, event("wayfuture", Long.MAX_VALUE, 1), 0);
    assertEquals("INVALID_RECORD", s.quarantine.getFirst().reason());
    s = add(s, 0, new Engine.Event(1, "r", "badprogress", "t", "a", 0, 1, true), 0);
    assertEquals("INVALID_RECORD", s.quarantine.getFirst().reason());
    final var saved = s;
    assertThrows(
        IllegalArgumentException.class, () -> Engine.transition(saved, List.of(), List.of(), -1));
  }

  @Test
  void malformedAndRoutingValidationDoNotChangeAggregation() {
    var s = state(1);
    List<Engine.Event> bad =
        List.of(
            new Engine.Event(2, "r", "x", "t", "a", 0, 1, false),
            new Engine.Event(1, "wrong", "x", "t", "a", 0, 1, false),
            new Engine.Event(1, "r", "x", "bad:t", "a", 0, 1, false),
            event("x", -1, 1));
    for (var e : bad) {
      s = add(s, 0, e, 0);
      assertTrue(s.panes.isEmpty());
      assertEquals(1, s.quarantine.size());
    }
    s =
        Engine.transition(
            s, List.of(new Engine.Input(0, 4, 5, "wrong", event("ok", 0, 1), null)), List.of(), 0);
    assertTrue(s.panes.isEmpty());
    s = add(s, 0, new Engine.Event(1, "r", "x".repeat(200), null, "a", 0, 1, false), 0);
    assertEquals("unknown", s.quarantine.getFirst().eventId());
    assertEquals("!unattributed", s.quarantine.getFirst().tenant());
    final var copy = s;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Engine.transition(
                copy, List.of(Engine.Input.of(2, 0, event("x", 0, 1))), List.of(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Engine.transition(copy, List.of(), List.of(new Engine.Observation(2, false, 0, 0)), 0));
  }

  @Test
  void exactSourceAndRetentionEdgesHaveExplicitOutcomes() {
    var future = new Engine.State("r", new Engine.Limits(1, 10, 100, 100, 1), 0);
    future = add(future, 0, event("at-skew", 101, 7), 100);
    assertTrue(future.quarantine.isEmpty());
    assertEquals(7, future.panes.values().iterator().next().sum());
    future = add(future, 0, event("beyond-skew", 102, 7), 100);
    assertEquals("INVALID_RECORD", future.quarantine.getFirst().reason());
    var extreme = new Engine.State("r", new Engine.Limits(1, 10, 100, 100, Long.MAX_VALUE), 0);
    extreme = add(extreme, 0, event("last-safe", Long.MAX_VALUE - 6 * Engine.MINUTE, 1), 0);
    assertTrue(extreme.quarantine.isEmpty());
    assertTrue(extreme.seen.containsKey("t:last-safe"));
    var retained = add(state(1), 0, event("pane", 0, 1), 0);
    retained = add(retained, 0, progress(334_999), 334_999);
    assertEquals(329_999, retained.watermark);
    assertEquals(1, retained.panes.size());
    retained = add(retained, 0, progress(335_000), 335_000);
    assertEquals(330_000, retained.watermark);
    assertTrue(retained.panes.isEmpty());
    assertEquals("t:a", event("wire", 0, 1).key());
    var malformed =
        Engine.transition(
            state(1), List.of(new Engine.Input(0, 0, 1, "key", null, null)), List.of(), 0);
    assertEquals("MALFORMED", malformed.quarantine.getFirst().reason());
    assertTrue(malformed.panes.isEmpty());
  }

  @Test
  void progressInsideAtomicBatchFinalizesBeforeLaterArrivalAtExactLatenessEdge() {
    var s = add(state(1), 0, event("first", 0, 10), 0);
    s =
        Engine.transition(
            s,
            List.of(
                Engine.Input.of(0, 1, progress(95_000)),
                Engine.Input.of(0, 2, event("late", 50_000, 5))),
            List.of(),
            95_000);
    var w = new Engine.Window(new Engine.Key("t", "a"), "TUMBLING", 0);
    assertEquals(90_000, s.watermark);
    assertTrue(s.results.get(w).finalized());
    assertEquals(10, s.results.get(w).aggregate().sum());
    assertEquals(1, s.late.stream().filter(r -> r.windowId().contains("TUMBLING")).count());
    assertEquals(15, s.panes.values().stream().mapToLong(Engine.Aggregate::sum).sum());
  }

  @Test
  void fullyLateRecordDoesNotConsumePaneQuotaOrMutateRetainedFinals() {
    var s = new Engine.State("r", new Engine.Limits(1, 2, 1, 100, 1_000_000), 0);
    s = add(s, 0, event("old", 0, 10), 0);
    s = add(s, 0, progress(335_000), 335_000);
    s = add(s, 0, new Engine.Event(1, "r", "current", "t", "b", 400_000, 8, false), 400_000);
    assertEquals(1, s.panes.size());
    s = add(s, 0, event("fully-late", 0, 9), 400_000);
    assertTrue(s.quarantine.isEmpty());
    assertEquals(6, s.late.size());
    assertEquals(1, s.panes.size());
    assertEquals(8, s.panes.values().iterator().next().sum());
  }

  @Test
  void firstLateWindowPublishesUnderUnchangedWatermarkAndFinalizesOnce() {
    var s = add(state(1), 0, progress(65_000), 65_000);
    assertEquals(60_000, s.watermark);
    s = add(s, 0, event("first-late", 50_000, 5), 65_000);
    var w = new Engine.Window(new Engine.Key("t", "a"), "TUMBLING", 0);
    assertEquals(60_000, s.watermark);
    assertEquals(1, s.results.get(w).revision());
    assertEquals(5, s.results.get(w).aggregate().sum());
    assertFalse(s.results.get(w).finalized());
    assertEquals(2, s.outbox.size(), "tumbling and oldest sliding windows are already due");
    s = add(s, 0, event("second-late", 55_000, 7), 65_000);
    assertEquals(2, s.results.get(w).revision());
    assertEquals(12, s.results.get(w).aggregate().sum());
    s = add(s, 0, progress(95_000), 95_000);
    assertTrue(s.results.get(w).finalized());
    assertEquals(3, s.results.get(w).revision());
    assertEquals(12, s.results.get(w).aggregate().sum());
    s = add(s, 0, event("after-final", 40_000, 99), 95_000);
    assertTrue(s.late.stream().anyMatch(r -> r.windowId().equals(w.id())));
    assertFalse(s.outbox.stream().anyMatch(r -> r.window().equals(w)));
  }
}
