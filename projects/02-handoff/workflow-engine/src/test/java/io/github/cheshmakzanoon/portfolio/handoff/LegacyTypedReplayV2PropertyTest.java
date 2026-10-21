package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import io.github.cheshmakzanoon.portfolio.handoff.LegacyInterpreterV2.*;
import java.time.*;
import java.util.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

class LegacyTypedReplayV2PropertyTest {
  @Property(tries = 500, seed = "20261018")
  void prefixModel(
      @ForAll @IntRange(min = 1, max = 64) int size,
      @ForAll @IntRange(min = 0, max = 64) int completionCount) {
    int completed = Math.min(size, completionCount);
    List<Node> nodes = new ArrayList<>();
    List<Event> events = new ArrayList<>();
    events.add(new Event(1, EventType.STARTED, "", "", Instant.EPOCH));
    for (int n = 0; n < size; n++) {
      nodes.add(
          new Node(
              "a" + n,
              Kind.ACTIVITY,
              n == 0 ? List.of() : List.of("a" + (n - 1)),
              "pure",
              Safety.PURE,
              null,
              Type.TEXT,
              Type.TEXT,
              0,
              null,
              null,
              null));
      if (n < completed)
        events.add(
            new Event(n + 2, EventType.COMPLETED, "a" + n, "result", Instant.ofEpochSecond(n)));
    }
    Definition d = new Definition("property", 1, nodes);
    Result result = new LegacyInterpreterV2().replay(d, events, Map.of());
    assertEquals(completed == size ? State.COMPLETED : State.RUNNING, result.state());
    assertEquals(
        completed == size ? List.of() : List.of("a" + completed),
        result.decisions().stream().map(Decision::id).toList());
    assertEquals(result, new LegacyInterpreterV2().replay(d, List.copyOf(events), Map.of()));
  }
}
