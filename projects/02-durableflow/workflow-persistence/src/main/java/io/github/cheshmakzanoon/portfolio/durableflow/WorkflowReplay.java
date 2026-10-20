package io.github.cheshmakzanoon.portfolio.durableflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.*;
import java.util.*;

/** Adapts persisted versions without changing their frozen interpreter or serialized input. */
final class WorkflowReplay {
  private final ObjectMapper json;
  private final Interpreter interpreter = new Interpreter();

  WorkflowReplay(ObjectMapper json) {
    this.json = json;
  }

  Result replay(String semantics, String definitionBody, List<Event> history, String input)
      throws Exception {
    Result result;
    if (Interpreter.SEMANTICS.equals(semantics))
      result =
          interpreter.replay(
              json.readValue(definitionBody, Definition.class),
              history,
              json.readValue(input, ExecutionInput.class));
    else if (semantics.equals("2")) {
      var old =
          new LegacyInterpreterV2()
              .replay(
                  json.readValue(definitionBody, Definition.class),
                  history.stream()
                      .map(
                          e ->
                              new LegacyInterpreterV2.Event(
                                  e.sequence(),
                                  LegacyInterpreterV2.EventType.valueOf(e.type().name()),
                                  e.task(),
                                  e.value(),
                                  e.at(),
                                  e.result()))
                      .toList(),
                  json.readValue(input, ExecutionInput.class));
      result =
          new Result(
              State.valueOf(old.state().name()),
              old.decisions().stream()
                  .map(
                      d ->
                          new Decision(
                              d.id(),
                              d.kind(),
                              d.activity(),
                              d.safety(),
                              d.compensation(),
                              d.due(),
                              d.signal(),
                              d.input(),
                              d.outputType()))
                  .toList(),
              old.values());
    } else if (semantics.equals("1")) {
      LegacyDefinitionV1 legacy = json.readValue(definitionBody, LegacyDefinitionV1.class);
      List<LegacyInterpreterV1.Event> events =
          history.stream()
              .map(
                  e ->
                      new LegacyInterpreterV1.Event(
                          e.sequence(),
                          LegacyInterpreterV1.EventType.valueOf(e.type().name()),
                          e.task(),
                          e.value(),
                          e.at()))
              .toList();
      var old =
          new LegacyInterpreterV1()
              .replay(
                  legacy,
                  events,
                  json.readValue(input, new TypeReference<Map<String, String>>() {}));
      Map<String, Output> values = new LinkedHashMap<>();
      old.values().forEach((key, value) -> values.put(key, Output.of(List.of(Value.text(value)))));
      TaskInput parameters =
          new TaskInput(
              ExecutionInput.textContext(Map.of()), List.of(Value.text("legacy")), Map.of());
      List<Decision> decisions =
          old.decisions().stream()
              .map(
                  d ->
                      new Decision(
                          d.id(),
                          Kind.valueOf(d.kind().name()),
                          d.activity(),
                          d.safety() == null ? null : Safety.valueOf(d.safety().name()),
                          d.compensation(),
                          d.due(),
                          d.signal(),
                          parameters,
                          Type.TEXT))
              .toList();
      result = new Result(State.valueOf(old.state().name()), decisions, values);
    } else throw new IllegalStateException("unsupported pinned semantics");
    return result;
  }
}
