package io.github.cheshmakzanoon.portfolio.byteguard.dataflow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.InstructionId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class InvocationEffectsTest {
  @Test
  void opaqueMutationExportsEveryReferenceWriteForCallerSummarySubstitution() {
    HeapSummary heap = new HeapSummary();
    Taint source = Taint.source("S:external");
    AbstractValue receiver = AbstractValue.reference(Taint.BOTTOM, "receiver");
    AbstractValue argument = AbstractValue.reference(Taint.BOTTOM, "argument");
    List<AbstractValue> operands =
        List.of(receiver, argument, AbstractValue.of(AbstractValue.Kind.INT, source));
    Map<AbstractValue, Taint> exported = new HashMap<>();
    Taint result = InvocationEffects.opaque(heap, operands, exported::put);
    Taint expected = source.join(Taint.unknownValue());
    assertEquals(expected, result);
    assertEquals(Map.of(receiver, expected, argument, expected), exported);
    assertEquals(expected, heap.elements(receiver));
    assertEquals(expected, heap.elements(argument));
  }

  @Test
  void returnedMutableIdentityRetainsContentsWhenReloadedWithoutTheOriginalValue() {
    HeapSummary heap = new HeapSummary();
    Taint source = Taint.source("S:external");
    AbstractValue argument = AbstractValue.reference(Taint.BOTTOM, "operand");
    AbstractValue result =
        InvocationEffects.returned(
            heap,
            Type.getType("[Ljava/lang/String;"),
            source,
            Set.of("modeled-result"),
            List.of(argument),
            true,
            "caller",
            new InstructionId("C.run()V", 8));
    assertTrue(result.references().contains("modeled-result"));
    assertEquals(
        2,
        result.references().size(),
        "opaque return adds a call identity alongside known alternatives");
    String fresh =
        result.references().stream()
            .filter(r -> !r.equals("modeled-result"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        source,
        heap.elements(AbstractValue.reference(Taint.BOTTOM, fresh)),
        "a later clean reload must recover seeded return contents from heap identity");
    heap.addElement(argument, Taint.source("S:later-store"));
    assertTrue(
        heap.elements(result).labels().containsKey("S:later-store"),
        "opaque result may alias its operand");
  }

  @Test
  void scalarAndImmutableResultsDoNotInventMutableHeapObjects() {
    for (Type type : List.of(Type.INT_TYPE, Type.getType(String.class))) {
      AbstractValue result =
          InvocationEffects.returned(
              new HeapSummary(),
              type,
              Taint.BOTTOM,
              Set.of(),
              List.of(),
              true,
              "caller",
              new InstructionId("C.run()V", 1));
      assertTrue(
          result.references().isEmpty(),
          "opaque scalar/immutable return has no mutable object identity");
      assertEquals(AbstractValue.kind(type), result.kind());
    }
    AbstractValue known =
        InvocationEffects.returned(
            new HeapSummary(),
            Type.getType("[I"),
            Taint.BOTTOM,
            Set.of("known-array"),
            List.of(),
            false,
            "caller",
            new InstructionId("C.run()V", 1));
    assertEquals(
        Set.of("known-array"),
        known.references(),
        "known concrete result retains its modeled alias only");
  }
}
