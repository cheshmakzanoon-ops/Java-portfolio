package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkflowReplayTest {
  private final ObjectMapper json = new ObjectMapper();
  private final WorkflowReplay replay = new WorkflowReplay(json);
  private final Instant time = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void persistedVersionsRetainTheirOriginalInputsDecisionShapeAndFinalValues() throws Exception {
    Definition definition =
        new Definition(
            "timer",
            1,
            List.of(
                new Node(
                    "wait",
                    Kind.TIMER,
                    List.of(),
                    null,
                    null,
                    null,
                    Type.TEXT,
                    Type.TEXT,
                    5,
                    null,
                    null,
                    null)));
    String typedDefinition = json.writeValueAsString(definition);
    var legacyTree = json.valueToTree(definition);
    ((com.fasterxml.jackson.databind.node.ObjectNode) legacyTree).remove("variables");
    List<Event> start = List.of(new Event(1, EventType.STARTED, "", "started", time, List.of()));
    var typedInput = new ExecutionInput(Value.text("recorded-input"), Map.of());
    for (String version : List.of("1", "2", "3")) {
      String body = version.equals("1") ? legacyTree.toString() : typedDefinition;
      String input = version.equals("1") ? "{}" : json.writeValueAsString(typedInput);
      var waiting = replay.replay(version, body, start, input);
      assertEquals(State.WAITING, waiting.state());
      assertEquals(1, waiting.decisions().size());
      var decision = waiting.decisions().getFirst();
      assertEquals("wait", decision.id());
      assertEquals(Kind.TIMER, decision.kind());
      assertEquals(time.plusSeconds(5), decision.due());
      assertEquals(Type.TEXT, decision.outputType());
      assertEquals(
          List.of(Value.text(version.equals("1") ? "legacy" : "recorded-input")),
          decision.input().arguments());
      var finished =
          replay.replay(
              version,
              body,
              List.of(
                  start.getFirst(),
                  new Event(
                      2, EventType.TIMER_FIRED, "wait", "timeout", time.plusSeconds(5), List.of())),
              input);
      assertEquals(State.COMPLETED, finished.state());
      assertTrue(finished.decisions().isEmpty());
      assertEquals(
          Map.of(
              "wait",
              Output.of(List.of(Value.text(version.equals("1") ? "timeout" : "recorded-input")))),
          finished.values());
    }
  }

  @Test
  void unknownSemanticVersionIsNeverDecodedAsCurrent() {
    assertThrows(IllegalStateException.class, () -> replay.replay("99", "{}", List.of(), "{}"));
  }
}
