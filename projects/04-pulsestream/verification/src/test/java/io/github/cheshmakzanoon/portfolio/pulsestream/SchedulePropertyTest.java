package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.jqwik.api.*;

class SchedulePropertyTest {
  @Property(tries = 80, seed = "4411")
  void recordedLateArrivalHistoriesMatchIndependentOracle(@ForAll long seed) {
    Random random = new Random(seed);
    String run = "property";
    Engine.State s = new Engine.State(run, new Engine.Limits(2, 100, 10000, 10000, 1000000), 0);
    BatchOracle oracle = new BatchOracle(2, 0);
    Map<Engine.Window, Engine.Result> durable = new HashMap<>();
    List<Engine.Event> history = new ArrayList<>();
    long[] offsets = {0, 0};
    long clock = 0;
    for (int i = 0; i < 200; i++) {
      clock += random.nextInt(40001);
      int partition = random.nextInt(2);
      Engine.Event e;
      if (i % 7 == 0 && !history.isEmpty()) e = history.get(random.nextInt(history.size()));
      else {
        boolean progress = i % 9 == 0;
        long time = Math.max(0, clock - random.nextInt(120001));
        e =
            ReplayWorker.event(
                run,
                "e" + i,
                "s" + random.nextInt(4),
                time,
                progress ? 0 : random.nextInt(100),
                progress);
        history.add(e);
      }
      long offset = offsets[partition];
      offsets[partition] += 3;
      boolean malformed = i % 17 == 0;
      if (!malformed) oracle.record(e, partition, clock);
      var input =
          new Engine.Input(
              partition,
              offset,
              offsets[partition],
              e.key(),
              malformed ? null : e,
              malformed ? "MALFORMED_JSON" : null);
      s = Engine.transition(s, List.of(input), List.of(), clock);
      assertEquals(oracle.watermark(), s.watermark, "independent event progress");
      durable.putAll(s.results);
      var observation =
          new Engine.Observation(
              random.nextInt(2), i % 13 == 0, i % 11 == 0 ? 3 : 0, i % 19 == 0 ? 1 : 0);
      oracle.observe(
          observation.partition(),
          observation.paused(),
          observation.brokerLag(),
          observation.queued(),
          clock);
      s = Engine.transition(s, List.of(), List.of(observation), clock);
      durable.putAll(s.results);
      assertEquals(oracle.watermark(), s.watermark, "independent silence eligibility");
      if (i == 100) {
        s = s.afterRestart(clock);
        oracle.restart(clock);
        assertEquals(oracle.watermark(), s.watermark);
      }
    }
    clock += 1_000_000;
    for (int p = 0; p < 2; p++) {
      var progress = ReplayWorker.event(run, "end" + p, "s0", clock, 0, true);
      oracle.record(progress, p, clock);
      s =
          Engine.transition(
              s,
              List.of(
                  new Engine.Input(p, offsets[p], offsets[p] + 9, progress.key(), progress, null)),
              List.of(),
              clock);
      durable.putAll(s.results);
      assertEquals(oracle.watermark(), s.watermark);
    }
    var expected = oracle.totals();
    assertEquals(expected.size(), durable.size());
    durable.forEach(
        (w, r) -> {
          var v =
              expected.get(
                  new BatchOracle.Identity(
                      w.key().tenant(), w.key().sensor(), w.type(), w.start(), w.end()));
          assertNotNull(v);
          assertEquals(v.count(), r.aggregate().count());
          assertEquals(v.sum(), r.aggregate().sum());
          assertEquals(v.minimum(), r.aggregate().minimum());
          assertEquals(v.maximum(), r.aggregate().maximum());
          assertTrue(r.finalized());
        });
    assertEquals(oracle.duplicates(), s.duplicates);
  }
}
