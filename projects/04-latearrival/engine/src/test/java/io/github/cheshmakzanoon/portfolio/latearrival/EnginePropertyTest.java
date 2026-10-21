package io.github.cheshmakzanoon.portfolio.latearrival;

import static io.github.cheshmakzanoon.portfolio.latearrival.EngineTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class EnginePropertyTest {
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
}
