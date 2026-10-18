package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;
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

  @Property(tries = 120, seed = "4404")
  void panesAgreeWithIndependentMembershipOracle(@ForAll long seed) {
    Random random = new Random(seed);
    Engine.State s = state(1);
    // Keep progress fixed while exploring reordered, exact-edge and overlapping assignments.
    List<Engine.Event> events = new ArrayList<>();
    for (int i = 0; i < 80; i++)
      events.add(event("e" + i, random.nextInt(10) * 30_000L, random.nextInt(201) - 100));
    Collections.shuffle(events, random);
    // A second unseen source deliberately blocks watermark while this history is accepted.
    s = state(2);
    for (var e : events) s = add(s, 0, e, 400_000);
    s = add(s, 1, progress(900_000), 900_000);
    Map<Engine.Window, Engine.Result> output = new HashMap<>(s.results);
    s = add(s, 0, progress(900_000), 900_000);
    output.putAll(s.results);
    Map<String, long[]> expected = new HashMap<>();
    for (var e : events) {
      long minute = e.eventTimeEpochMillis() / 60_000 * 60_000;
      for (String type : List.of("TUMBLING", "SLIDING")) {
        long width = type.equals("TUMBLING") ? 60_000 : 300_000;
        for (long start = -240_000; start <= 540_000; start += 60_000) {
          if (type.equals("TUMBLING") && start != minute) continue;
          if (e.eventTimeEpochMillis() >= start && e.eventTimeEpochMillis() < start + width) {
            long[] a =
                expected.computeIfAbsent(
                    type + ":" + start, k -> new long[] {0, 0, Long.MAX_VALUE, Long.MIN_VALUE});
            a[0]++;
            a[1] += e.valueMilliUnits();
            a[2] = Math.min(a[2], e.valueMilliUnits());
            a[3] = Math.max(a[3], e.valueMilliUnits());
          }
        }
      }
    }
    assertEquals(expected.size(), output.size());
    output.forEach(
        (w, r) -> {
          long[] a = expected.get(w.type() + ":" + w.start());
          assertNotNull(a);
          assertEquals(a[0], r.aggregate().count());
          assertEquals(a[1], r.aggregate().sum());
          assertEquals(a[2], r.aggregate().minimum());
          assertEquals(a[3], r.aggregate().maximum());
          assertTrue(r.finalized());
        });
    assertTrue(s.panes.isEmpty());
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
}
