package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.cfg.*;
import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reads and solves crafted class bytes; target classes are never loaded or executed. */
class VerifierFramesTest implements Opcodes {
  private MethodNode method(String descriptor, int locals, int stack, AbstractInsnNode... code) {
    MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "subject", descriptor, null, null);
    method.maxLocals = locals;
    method.maxStack = stack;
    for (AbstractInsnNode instruction : code) method.instructions.add(instruction);
    return method;
  }

  private MethodIr read(MethodNode node) throws Exception {
    ClassWriter writer = new ClassWriter(0);
    // Version49 uses inferred verification frames, so the legal branch controls need no
    // supplied StackMapTable and are independent of the analyzer's own frame computation.
    writer.visit(
        V1_5,
        ACC_PUBLIC | (node.access & ACC_ABSTRACT),
        "FrameBoundary",
        null,
        "java/lang/Object",
        null);
    node.accept(writer);
    writer.visitEnd();
    byte[] bytes = writer.toByteArray();
    var entries = new TreeMap<String, InputManifest.Entry>();
    entries.put(
        "FrameBoundary.class",
        new InputManifest.Entry("FrameBoundary.class", bytes, BoundedJarReader.sha(bytes), true));
    var facts = new ClassFactsReader().read(new InputManifest(entries, List.of(), bytes.length));
    return facts.get("FrameBoundary").methods().get("FrameBoundary.subject" + node.desc);
  }

  private WorklistSolver.Result solve(MethodNode node, boolean reverse) throws Exception {
    MethodIr method = read(node);
    var transfer =
        new TransferFunction(
            method,
            new HeapSummary(),
            new RuleModels(),
            new CallResolver(new TypeHierarchy(Map.of())),
            Map.of(),
            AnalysisBudget.defaults(),
            new TreeSet<>());
    return new WorklistSolver()
        .solve(
            method,
            new ControlFlowBuilder().build(method),
            transfer,
            AnalysisBudget.defaults(),
            reverse);
  }

  @Test
  void malformedArithmeticReceiverAndReturnCannotBecomeCompleteSummaries() throws Exception {
    MethodNode arithmetic =
        method(
            "()I",
            0,
            2,
            new InsnNode(ACONST_NULL),
            new InsnNode(ICONST_1),
            new InsnNode(IADD),
            new InsnNode(IRETURN));
    assertThrows(IllegalArgumentException.class, () -> solve(arithmetic, false));
    MethodNode receiver =
        method(
            "()I",
            0,
            1,
            new InsnNode(ICONST_0),
            new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false),
            new InsnNode(IRETURN));
    assertThrows(IllegalArgumentException.class, () -> solve(receiver, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> solve(method("()I", 0, 1, new InsnNode(ACONST_NULL), new InsnNode(ARETURN)), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> solve(method("()V", 0, 1, new InsnNode(ICONST_0), new InsnNode(IRETURN)), false));
    assertEquals(
        2,
        solve(method("()I", 0, 1, new InsnNode(ICONST_1), new InsnNode(IRETURN)), false)
            .reachable());
  }

  private MethodNode reusedLocal(boolean loadAfterMerge, boolean wide) {
    MethodNode node = method("(Z)V", wide ? 3 : 2, wide ? 2 : 1);
    LabelNode alternate = new LabelNode(), join = new LabelNode();
    node.instructions.add(new VarInsnNode(ILOAD, 0));
    node.instructions.add(new JumpInsnNode(IFEQ, alternate));
    node.instructions.add(new InsnNode(wide ? LCONST_0 : ICONST_1));
    node.instructions.add(new VarInsnNode(wide ? LSTORE : ISTORE, 1));
    node.instructions.add(new JumpInsnNode(GOTO, join));
    node.instructions.add(alternate);
    node.instructions.add(new InsnNode(wide ? ICONST_1 : ACONST_NULL));
    node.instructions.add(new VarInsnNode(wide ? ISTORE : ASTORE, wide ? 2 : 1));
    node.instructions.add(join);
    if (loadAfterMerge) {
      node.instructions.add(new VarInsnNode(wide ? LLOAD : ILOAD, 1));
      node.instructions.add(new InsnNode(wide ? POP2 : POP));
    }
    node.instructions.add(new InsnNode(RETURN));
    return node;
  }

  @Test
  void disjointBranchLocalKindsMergeToUnusableTopInBothSchedules() throws Exception {
    for (boolean reverse : new boolean[] {false, true})
      for (boolean wide : new boolean[] {false, true}) {
        var result = solve(reusedLocal(false, wide), reverse);
        assertEquals(8, result.reachable());
        FrameState join = result.frames().getLast();
        assertEquals(AbstractValue.TOP, join.locals[1]);
        if (wide) assertEquals(AbstractValue.TOP, join.locals[2]);
        assertThrows(IllegalArgumentException.class, () -> solve(reusedLocal(true, wide), reverse));
      }
  }

  @Test
  void incompatibleOperandStacksStillFailAtJoin() {
    MethodNode node = method("(Z)V", 1, 1);
    LabelNode alternate = new LabelNode(), join = new LabelNode();
    node.instructions.add(new VarInsnNode(ILOAD, 0));
    node.instructions.add(new JumpInsnNode(IFEQ, alternate));
    node.instructions.add(new InsnNode(ICONST_0));
    node.instructions.add(new JumpInsnNode(GOTO, join));
    node.instructions.add(alternate);
    node.instructions.add(new InsnNode(ACONST_NULL));
    node.instructions.add(join);
    node.instructions.add(new InsnNode(POP));
    node.instructions.add(new InsnNode(RETURN));
    for (boolean reverse : new boolean[] {false, true})
      assertThrows(IllegalArgumentException.class, () -> solve(node, reverse));
  }

  @Test
  void concreteBodiesAndReachableFallthroughAreRequired() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> solve(method("()V", 0, 0), false));
    assertThrows(
        IllegalArgumentException.class, () -> solve(method("()V", 0, 0, new InsnNode(NOP)), false));
    MethodNode conditional = method("()V", 0, 1);
    LabelNode again = new LabelNode();
    conditional.instructions.add(again);
    conditional.instructions.add(new InsnNode(ICONST_0));
    conditional.instructions.add(new JumpInsnNode(IFEQ, again));
    assertThrows(IllegalArgumentException.class, () -> solve(conditional, false));
    MethodNode loop = method("()V", 0, 0);
    LabelNode head = new LabelNode();
    loop.instructions.add(head);
    loop.instructions.add(new JumpInsnNode(GOTO, head));
    assertEquals(1, solve(loop, false).reachable());
    for (int modifier : new int[] {ACC_NATIVE, ACC_ABSTRACT}) {
      MethodNode bodyless = method("()V", 0, 0);
      if (modifier == ACC_ABSTRACT) {
        bodyless.access &= ~ACC_STATIC;
        bodyless.maxLocals = 1;
      }
      bodyless.access |= modifier;
      assertEquals(0, solve(bodyless, false).reachable());
      bodyless.instructions.add(new InsnNode(RETURN));
      assertThrows(IllegalArgumentException.class, () -> solve(bodyless, false));
    }
  }

  private MethodNode handler(int maxStack) {
    MethodNode node = method("()V", 0, maxStack);
    LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
    node.instructions.add(start);
    node.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "gc", "()V", false));
    node.instructions.add(end);
    node.instructions.add(new InsnNode(RETURN));
    node.instructions.add(handler);
    node.instructions.add(new InsnNode(POP));
    node.instructions.add(new InsnNode(RETURN));
    node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    return node;
  }

  @Test
  void exceptionEntryRespectsMaxStackBeforeHandlerPopsTheException() throws Exception {
    var valid = solve(handler(1), false);
    assertEquals(1, valid.frames().get(2).slots());
    assertEquals(AbstractValue.Kind.REF, valid.frames().get(2).stack.getFirst().kind());
    assertThrows(IllegalArgumentException.class, () -> solve(handler(0), false));
  }

  @Test
  void exceptionTableShapeIsCheckedEvenWhenProtectedInstructionsCannotThrow() {
    for (int shape = 0; shape < 3; shape++) {
      MethodNode node = method("()V", 0, 1);
      LabelNode start = new LabelNode(),
          end = new LabelNode(),
          handler = new LabelNode(),
          tail = new LabelNode();
      node.instructions.add(start);
      node.instructions.add(new InsnNode(RETURN));
      node.instructions.add(end);
      node.instructions.add(handler);
      node.instructions.add(new InsnNode(POP));
      node.instructions.add(new InsnNode(RETURN));
      node.instructions.add(tail);
      node.tryCatchBlocks.add(
          new TryCatchBlockNode(
              shape == 1 ? end : start,
              shape == 0 ? start : shape == 1 ? start : end,
              shape == 2 ? tail : handler,
              null));
      assertThrows(
          IllegalArgumentException.class,
          () -> new ControlFlowBuilder().build(new MethodIr("FrameBoundary", null, node)));
    }
    MethodNode valid = handler(1);
    assertDoesNotThrow(
        () -> new ControlFlowBuilder().build(new MethodIr("FrameBoundary", null, valid)));
  }
}
