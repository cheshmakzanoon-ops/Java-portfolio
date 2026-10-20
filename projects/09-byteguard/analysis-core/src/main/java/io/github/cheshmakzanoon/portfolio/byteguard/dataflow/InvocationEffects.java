package io.github.cheshmakzanoon.portfolio.byteguard.dataflow;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.InstructionId;
import java.util.*;
import java.util.function.BiConsumer;
import org.objectweb.asm.Type;

/** Operand-based conservative effects and stable heap identities for opaque/library calls. */
final class InvocationEffects {
  private InvocationEffects() {}

  static Taint opaque(
      HeapSummary heap,
      List<AbstractValue> operands,
      BiConsumer<AbstractValue, Taint> recordEffect) {
    Taint uncertain = Taint.unknownValue();
    for (AbstractValue operand : operands) uncertain = uncertain.join(heap.elements(operand));
    for (AbstractValue operand : operands)
      if (operand.kind() == AbstractValue.Kind.REF) {
        heap.addElement(operand, uncertain);
        recordEffect.accept(operand, uncertain);
      }
    return uncertain;
  }

  static AbstractValue returned(
      HeapSummary heap,
      Type type,
      Taint taint,
      Collection<String> modeledReferences,
      List<AbstractValue> operands,
      boolean opaque,
      String callerScope,
      InstructionId site) {
    TreeSet<String> references = new TreeSet<>(modeledReferences);
    if (mutableReference(type) && (opaque || references.isEmpty())) {
      String identity = "call-result:" + callerScope + ":" + site.ordinal();
      references.add(identity);
      if (opaque)
        for (AbstractValue operand : operands)
          if (operand.kind() == AbstractValue.Kind.REF) heap.alias(identity, operand.references());
      heap.addElement(AbstractValue.reference(taint, identity), taint);
    }
    return new AbstractValue(AbstractValue.kind(type), taint, references, null);
  }

  static boolean mutableReference(Type type) {
    if (type.getSort() == Type.ARRAY) return true;
    if (type.getSort() != Type.OBJECT) return false;
    String name = type.getInternalName();
    return !Set.of(
                "java/lang/String",
                "java/lang/Class",
                "java/lang/Integer",
                "java/lang/Long",
                "java/lang/Double",
                "java/lang/Float",
                "java/lang/Boolean",
                "java/lang/Character",
                "java/lang/Byte",
                "java/lang/Short",
                "java/nio/file/Path")
            .contains(name)
        && !name.startsWith("java/sql/");
  }
}
