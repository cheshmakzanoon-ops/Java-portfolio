package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.handoff.Data.*;
import io.github.cheshmakzanoon.portfolio.handoff.Definition.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DataTest {
  Node node(String activity, Type input, Type output) {
    return new Node(
        "root",
        Kind.ACTIVITY,
        List.of(),
        activity,
        Safety.PURE,
        null,
        input,
        output,
        0,
        null,
        null,
        null);
  }

  @Test
  void scalarTypesAreActualValuesAndNeverCoerced() {
    assertEquals(Value.integer(7), new Value(Type.INTEGER, 7));
    assertEquals(Long.class, Value.integer(7).data().getClass());
    assertEquals(Value.bool(true), new Value(Type.BOOLEAN, true));
    assertEquals("7", Value.text("7").data());
    for (Object wrong : List.of("7", 7.0, new java.math.BigInteger("7"), true, Map.of("value", 7)))
      assertThrows(IllegalArgumentException.class, () -> new Value(Type.INTEGER, wrong));
    for (Object wrong : List.of("true", 1, List.of(true)))
      assertThrows(IllegalArgumentException.class, () -> new Value(Type.BOOLEAN, wrong));
    assertThrows(IllegalArgumentException.class, () -> new Value(Type.TEXT, 7));
    assertThrows(IllegalArgumentException.class, () -> new Value(null, "x"));
    assertThrows(IllegalArgumentException.class, () -> new Value(Type.TEXT, null));
    assertThrows(IllegalArgumentException.class, () -> Value.text("x".repeat(1025)));
    assertThrows(IllegalArgumentException.class, () -> Value.text("Bearer credential"));
    assertEquals(
        Value.integer(Long.MIN_VALUE), Value.literal(Type.INTEGER, Long.toString(Long.MIN_VALUE)));
    assertEquals(
        Value.integer(Long.MAX_VALUE), Value.literal(Type.INTEGER, Long.toString(Long.MAX_VALUE)));
  }

  @Test
  void credentialMarkersRespectUnicodeCaseAndLineSeparators() {
    for (String marker : List.of("password", "SECRET", "Bearer", "ToKeN", "to\u212Aen")) {
      for (String separator : List.of("\n", "\r", "\r\n", "\u2028", "\u2029")) {
        assertThrows(
            IllegalArgumentException.class,
            () -> Value.text("public" + separator + marker + separator + "private"));
      }
    }
    assertEquals("first\nsecond", Value.text("first\nsecond").data());
    assertEquals("café\nrésumé", Value.text("café\nrésumé").data());
  }

  @Test
  void literalGrammarUsesItsDeclaredType() {
    assertEquals(Value.text("yes"), Value.literal(Type.TEXT, "yes"));
    assertEquals(Value.bool(false), Value.literal(Type.BOOLEAN, "false"));
    assertEquals(Value.bool(true), Value.literal(Type.BOOLEAN, "true"));
    assertThrows(IllegalArgumentException.class, () -> Value.literal(Type.BOOLEAN, "yes"));
    for (String wrong : List.of("07", "7.0", "1e2", "+1", "9223372036854775808"))
      assertThrows(IllegalArgumentException.class, () -> Value.literal(Type.INTEGER, wrong));
  }

  @Test
  void inputSchemaIsExactAndImmutable() {
    Map<String, Value> context = new HashMap<>();
    context.put("limit", Value.integer(7));
    ExecutionInput input = new ExecutionInput(Value.integer(3), context);
    context.put("limit", Value.integer(9));
    assertEquals(Value.integer(7), input.context().get("limit"));
    assertThrows(UnsupportedOperationException.class, () -> input.context().clear());
    Definition definition =
        new Definition(
            "sum",
            1,
            List.of(node("sum", Type.INTEGER, Type.INTEGER)),
            Map.of("limit", Type.INTEGER));
    input.validate(definition);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionInput(Value.text("3"), input.context()).validate(definition));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionInput(Value.integer(3), Map.of()).validate(definition));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionInput(Value.integer(3), Map.of("limit", Value.text("7")))
                .validate(definition));
    assertEquals(
        Type.TEXT, ExecutionInput.textContext(Map.of("limit", "7")).context().get("limit").type());
    assertThrows(IllegalArgumentException.class, () -> ExecutionInput.textContext(null));
  }

  @Test
  void inputNamesAndShapeAreBounded() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutionInput(null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new ExecutionInput(Value.text("x"), null));
    Map<String, Value> tooMany = new HashMap<>();
    for (int n = 0; n < 33; n++) tooMany.put("n" + n, Value.text("x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionInput(Value.text("x"), tooMany));
    for (String bad : List.of("!", "password", "payload"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ExecutionInput(Value.text("x"), Map.of(bad, Value.text("x"))));
    Map<String, Value> nullKey = new HashMap<>();
    nullKey.put(null, Value.text("x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionInput(Value.text("x"), nullKey));
    Map<String, Value> nullValue = new HashMap<>();
    nullValue.put("x", null);
    assertThrows(
        IllegalArgumentException.class, () -> new ExecutionInput(Value.text("x"), nullValue));
  }

  @Test
  void outcomesCannotInventValuesForSkippedOrTimeout() {
    Output value = Output.of(List.of(Value.integer(1), Value.integer(2)));
    value.require(Type.INTEGER);
    assertThrows(IllegalArgumentException.class, () -> value.require(Type.TEXT));
    assertEquals(List.of(), Output.skipped().values());
    assertEquals(Outcome.TIMED_OUT, Output.timedOut().outcome());
    assertThrows(IllegalArgumentException.class, () -> Output.skipped().require(Type.TEXT));
    assertThrows(IllegalArgumentException.class, () -> Output.of(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Output(null, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Output(Outcome.VALUE, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Output(Outcome.TIMED_OUT, List.of(Value.text("x"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Output.of(List.of(Value.text("x"), Value.bool(true))));
    assertThrows(
        IllegalArgumentException.class, () -> Output.of(Collections.nCopies(129, Value.text("x"))));
  }

  @Test
  void taskArgumentsPreserveActualPredecessorValues() {
    ExecutionInput execution = new ExecutionInput(Value.text("original"), Map.of());
    Output predecessor = Output.of(List.of(Value.integer(9)));
    List<Value> args = new ArrayList<>(predecessor.values());
    Map<String, Output> previous = new HashMap<>(Map.of("sum", predecessor));
    TaskInput task = new TaskInput(execution, args, previous);
    args.clear();
    previous.clear();
    assertEquals(List.of(Value.integer(9)), task.arguments());
    assertEquals(predecessor, task.predecessors().get("sum"));
    assertThrows(UnsupportedOperationException.class, () -> task.predecessors().clear());
    assertThrows(UnsupportedOperationException.class, () -> task.arguments().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskInput(null, List.of(Value.text("x")), Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new TaskInput(execution, null, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TaskInput(execution, List.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskInput(execution, List.of(Value.text("x")), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskInput(execution, Collections.nCopies(129, Value.text("x")), Map.of()));
    Map<String, Output> many = new HashMap<>();
    for (int n = 0; n < 17; n++) many.put("n" + n, predecessor);
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskInput(execution, List.of(Value.text("x")), many));
  }

  @Test
  void compiledActivitySignaturesAreEnforced() {
    assertNotNull(
        new Definition("sum", 1, List.of(node("sum", Type.INTEGER, Type.INTEGER))).validate());
    assertNotNull(
        new Definition("not", 1, List.of(node("not", Type.BOOLEAN, Type.BOOLEAN))).validate());
    for (Node wrong :
        List.of(
            node("sum", Type.TEXT, Type.INTEGER),
            node("not", Type.BOOLEAN, Type.TEXT),
            node("pure", Type.INTEGER, Type.TEXT),
            node("reserve", Type.TEXT, Type.TEXT)))
      assertThrows(
          IllegalArgumentException.class,
          () -> new Definition("wrong", 1, List.of(wrong)).validate());
  }

  @Test
  void vectorAmplificationIsRejectedBeforeActivation() {
    List<Node> nodes = new ArrayList<>();
    nodes.add(
        new Node(
            "root",
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
            null));
    String previous = "root";
    for (int layer = 0; layer < 8; layer++) {
      String left = "left" + layer, right = "right" + layer, join = "join" + layer;
      nodes.add(
          new Node(
              left,
              Kind.ACTIVITY,
              List.of(previous),
              "pure",
              Safety.PURE,
              null,
              Type.INTEGER,
              Type.INTEGER,
              0,
              null,
              null,
              null));
      nodes.add(
          new Node(
              right,
              Kind.ACTIVITY,
              List.of(previous),
              "pure",
              Safety.PURE,
              null,
              Type.INTEGER,
              Type.INTEGER,
              0,
              null,
              null,
              null));
      nodes.add(
          new Node(
              join,
              Kind.PARALLEL_ALL,
              List.of(left, right),
              null,
              null,
              null,
              Type.INTEGER,
              Type.INTEGER,
              0,
              null,
              null,
              null));
      previous = join;
    }
    assertThrows(
        IllegalArgumentException.class, () -> new Definition("amplification", 1, nodes).validate());
  }

  @Test
  void compiledActivityResultCardinalityPreservesTheGraphBound() {
    var text = List.of(Value.text("one"));
    var pair = List.of(Value.text("one"), Value.text("two"));
    assertEquals(pair, Data.activityResult("pure", Type.TEXT, 2, pair).values());
    assertEquals(text, Data.activityResult("reserve", Type.TEXT, 2, text).values());
    assertEquals(
        List.of(Value.integer(3)),
        Data.activityResult("sum", Type.INTEGER, 2, List.of(Value.integer(3))).values());
    assertEquals(
        List.of(Value.bool(false)),
        Data.activityResult("not", Type.BOOLEAN, 1, List.of(Value.bool(false))).values());
    assertEquals(
        128,
        Data.activityResult(
                "pure", Type.TEXT, 128, java.util.Collections.nCopies(128, Value.text("bounded")))
            .values()
            .size());
    assertThrows(
        IllegalArgumentException.class, () -> Data.activityResult("pure", Type.TEXT, 1, pair));
    assertThrows(
        IllegalArgumentException.class, () -> Data.activityResult("pure", Type.TEXT, 2, text));
    for (String activity : Definition.ACTIVITIES)
      if (!activity.equals("pure"))
        assertThrows(
            IllegalArgumentException.class,
            () -> Data.activityResult(activity, Type.TEXT, 2, pair));
    for (int size : new int[] {0, 129})
      assertThrows(
          IllegalArgumentException.class, () -> Data.activityResult("pure", Type.TEXT, size, text));
    for (String activity : new String[] {null, "shell"})
      assertThrows(
          IllegalArgumentException.class, () -> Data.activityResult(activity, Type.TEXT, 1, text));
    assertThrows(
        IllegalArgumentException.class,
        () -> Data.activityResult("reserve", Type.INTEGER, 1, text));
    assertThrows(
        IllegalArgumentException.class,
        () -> Data.activityResult("reserve", Type.TEXT, 1, List.of()));
  }

  @Test
  void cancellationEvidenceIsAnActualBoundedInteger() {
    assertEquals(0, Data.cancelledForwardLeases(List.of(Value.integer(0))));
    assertEquals(128, Data.cancelledForwardLeases(List.of(Value.integer(128))));
    for (long count : new long[] {-1, 129})
      assertThrows(
          IllegalArgumentException.class,
          () -> Data.cancelledForwardLeases(List.of(Value.integer(count))));
    assertThrows(IllegalArgumentException.class, () -> Data.cancelledForwardLeases(null));
    assertThrows(IllegalArgumentException.class, () -> Data.cancelledForwardLeases(List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> Data.cancelledForwardLeases(Arrays.asList((Value) null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> Data.cancelledForwardLeases(List.of(Value.text("0"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> Data.cancelledForwardLeases(List.of(Value.integer(0), Value.integer(1))));
  }
}
