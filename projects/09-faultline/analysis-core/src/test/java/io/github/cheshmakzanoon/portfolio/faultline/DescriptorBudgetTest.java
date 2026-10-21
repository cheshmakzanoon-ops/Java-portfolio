package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.cfg.*;
import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class DescriptorBudgetTest implements Opcodes {
  @Test
  void rejectsOversizedParametersBeforeAllocatingEntrySymbols() {
    String oversized = "(" + "[I".repeat(32760) + ")V";
    assertThrows(
        IllegalArgumentException.class,
        () -> new MethodIr("Bad", null, method(ACC_STATIC, "run", oversized, 32760)));
    assertThrows(
        IllegalArgumentException.class,
        () -> MethodDescriptor.parameterSlots("(" + "I".repeat(255) + ")V", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> MethodDescriptor.parameterSlots("(" + "J".repeat(128) + ")V", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> MethodDescriptor.parameterSlots("(" + "D".repeat(127) + "I)V", false));
    assertEquals(255, MethodDescriptor.parameterSlots("(" + "J".repeat(127) + "I)V", true));
    assertEquals(255, MethodDescriptor.parameterSlots("(" + "D".repeat(127) + ")V", false));
    assertEquals(255, MethodDescriptor.parameterSlots("(" + "[J".repeat(255) + ")V", true));
  }

  @Test
  void validatesDescriptorGrammarAndArrayBound() {
    for (String descriptor :
        List.of(
            "",
            "()",
            "(V)V",
            "(Q)V",
            "(L;)V",
            "(Lx)V",
            "()VV",
            "([V)V",
            "()Q",
            "(" + "[".repeat(256) + "I)V",
            "(La.b;)V",
            "(L[x;)V",
            "(L/x;)V",
            "(Lx/;)V",
            "(Lx//y;)V"))
      assertThrows(
          IllegalArgumentException.class,
          () -> MethodDescriptor.parameterSlots(descriptor, true),
          descriptor);
    assertThrows(IllegalArgumentException.class, () -> MethodDescriptor.parameterSlots(null, true));
    assertEquals(1, MethodDescriptor.parameterSlots("(" + "[".repeat(255) + "I)V", true));
    assertEquals(0, MethodDescriptor.parameterSlots("()Ljava/lang/String;", true));
    assertEquals(1, MethodDescriptor.parameterSlots("(Lpkg/(legal)/Name;)V", true));
  }

  @Test
  void maximumLegalLongIdentitiesHaveCompactDistinctSymbolsAndCorrectFrames() {
    String descriptor = "(L" + "A".repeat(60000) + ";" + "[I".repeat(254) + ")V";
    MethodIr method =
        new MethodIr("Legal", null, method(ACC_STATIC, "m".repeat(60000), descriptor, 255));
    var result = solve(method, AnalysisBudget.defaults());
    var entry = result.frames().getFirst();
    Set<String> labels = new HashSet<>(), refs = new HashSet<>();
    for (int i = 0; i < 255; i++) {
      assertEquals(AbstractValue.Kind.REF, entry.locals[i].kind());
      Taint symbolic = entry.locals[i].taint();
      assertEquals(Taint.parameter(method.id, i), symbolic);
      assertEquals(
          Taint.source("actual"),
          symbolic.substitute(Collections.nCopies(255, Taint.source("actual")), method.id));
      labels.addAll(symbolic.labels().keySet());
      refs.addAll(entry.locals[i].references());
    }
    assertEquals(255, labels.size());
    assertEquals(255, refs.size());
    assertTrue(labels.stream().allMatch(s -> s.length() <= 70));
    assertTrue(refs.stream().allMatch(s -> s.length() <= 78));
    assertNotEquals(method.scope, MethodScope.of(method.id + "other"));
    assertEquals(120519, method.id.length());
  }

  @Test
  void compactScopesPreserveUnpairedUtf16CodeUnitIdentities() {
    String high = "owner.\uD800()V";
    String low = "owner.\uDC00()V";
    String question = "owner.?()V";
    assertNotEquals(MethodScope.of(high), MethodScope.of(question));
    assertNotEquals(MethodScope.of(low), MethodScope.of(question));
    assertNotEquals(MethodScope.of(high), MethodScope.of(low));
    assertEquals(MethodScope.of(high), MethodScope.of(high));
  }

  @Test
  void entrySetupConsumesWorkBeforeExecutingInstructionsAndRejectsShortLocals() {
    MethodIr method =
        new MethodIr(
            "Budgeted", null, method(ACC_STATIC, "run", "(" + "[I".repeat(255) + ")V", 255));
    AnalysisBudget budget = new AnalysisBudget(2, 120000, 32, 5);
    assertThrows(AnalysisBudget.Exhausted.class, () -> solve(method, budget));
    assertEquals(3, budget.work());
    MethodIr bad = new MethodIr("BadLocals", null, method(0, "run", "(J)V", 2));
    assertThrows(IllegalArgumentException.class, () -> solve(bad, AnalysisBudget.defaults()));
  }

  private MethodNode method(int access, String name, String descriptor, int locals) {
    MethodNode node = new MethodNode(access, name, descriptor, null, null);
    node.maxLocals = locals;
    node.instructions.add(new InsnNode(RETURN));
    return node;
  }

  private WorklistSolver.Result solve(MethodIr method, AnalysisBudget budget) {
    var transfer =
        new TransferFunction(
            method,
            new HeapSummary(),
            new RuleModels(),
            new CallResolver(new TypeHierarchy(Map.of())),
            Map.of(),
            budget,
            new TreeSet<>());
    return new WorklistSolver()
        .solve(method, new ControlFlowBuilder().build(method), transfer, budget, false);
  }
}
