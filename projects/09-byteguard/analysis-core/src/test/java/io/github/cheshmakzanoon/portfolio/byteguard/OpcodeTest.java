package io.github.cheshmakzanoon.portfolio.byteguard;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.cfg.*;
import io.github.cheshmakzanoon.portfolio.byteguard.dataflow.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.rules.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class OpcodeTest implements Opcodes {
  AbstractValue i = AbstractValue.of(AbstractValue.Kind.INT, Taint.source("src")),
      l = AbstractValue.of(AbstractValue.Kind.LONG, Taint.source("src")),
      fl = AbstractValue.of(AbstractValue.Kind.FLOAT, Taint.source("src")),
      d = AbstractValue.of(AbstractValue.Kind.DOUBLE, Taint.source("src")),
      r = AbstractValue.reference(Taint.source("src"), "allocation");

  FrameState apply(AbstractInsnNode node, AbstractValue... stack) {
    return apply("()V", node, stack);
  }

  FrameState apply(String descriptor, AbstractInsnNode node, AbstractValue... stack) {
    MethodNode n = new MethodNode(ACC_STATIC, "test", descriptor, null, null);
    n.maxLocals = 4;
    n.maxStack = 12;
    n.instructions.add(node);
    MethodIr ir = new MethodIr("Test", null, n);
    FrameState frame = new FrameState(4);
    for (AbstractValue v : stack) frame.push(v);
    TransferFunction t =
        new TransferFunction(
            ir,
            new HeapSummary(),
            new RuleModels(),
            new CallResolver(new TypeHierarchy(Map.of())),
            Map.of(),
            AnalysisBudget.defaults(),
            new TreeSet<>());
    t.apply(0, frame);
    return frame;
  }

  @Test
  void allDuplicationFormsRetainCategories() {
    assertEquals(List.of(i, i), apply(new InsnNode(DUP), i).stack);
    assertEquals(List.of(i, i, i), apply(new InsnNode(DUP_X1), i, i).stack);
    assertEquals(List.of(i, l, i), apply(new InsnNode(DUP_X2), l, i).stack);
    assertEquals(4, apply(new InsnNode(DUP_X2), i, i, i).stack.size());
    assertEquals(List.of(l, l), apply(new InsnNode(DUP2), l).stack);
    assertEquals(4, apply(new InsnNode(DUP2), i, i).stack.size());
    assertEquals(List.of(l, i, l), apply(new InsnNode(DUP2_X1), i, l).stack);
    assertEquals(5, apply(new InsnNode(DUP2_X1), i, i, i).stack.size());
    assertEquals(List.of(l, d, l), apply(new InsnNode(DUP2_X2), d, l).stack);
    assertEquals(List.of(l, i, i, l), apply(new InsnNode(DUP2_X2), i, i, l).stack);
    assertEquals(List.of(i, i, l, i, i), apply(new InsnNode(DUP2_X2), l, i, i).stack);
    assertEquals(6, apply(new InsnNode(DUP2_X2), i, i, i, i).stack.size());
    assertEquals(List.of(r, i), apply(new InsnNode(SWAP), i, r).stack);
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(DUP), l));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(POP), l));
    assertEquals(0, apply(new InsnNode(POP2), l).stack.size());
    assertEquals(0, apply(new InsnNode(POP2), i, i).stack.size());
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(POP2), l, i));
  }

  private void binary(
      int[] opcodes, AbstractValue left, AbstractValue right, AbstractValue.Kind result) {
    for (int opcode : opcodes) {
      AbstractValue actual = apply(new InsnNode(opcode), left, right).pop();
      assertEquals(result, actual.kind(), "opcode " + opcode);
      assertEquals(left.taint().join(right.taint()), actual.taint(), "opcode " + opcode);
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), r, right));
    }
  }

  @Test
  void arithmeticPreservesDataDependenciesWithValidComputationalKinds() {
    binary(
        new int[] {IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR},
        i,
        i,
        AbstractValue.Kind.INT);
    binary(
        new int[] {LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR}, l, l, AbstractValue.Kind.LONG);
    binary(new int[] {LSHL, LSHR, LUSHR}, l, i, AbstractValue.Kind.LONG);
    binary(new int[] {FADD, FSUB, FMUL, FDIV, FREM}, fl, fl, AbstractValue.Kind.FLOAT);
    binary(new int[] {DADD, DSUB, DMUL, DDIV, DREM}, d, d, AbstractValue.Kind.DOUBLE);
    binary(new int[] {LCMP}, l, l, AbstractValue.Kind.INT);
    binary(new int[] {FCMPL, FCMPG}, fl, fl, AbstractValue.Kind.INT);
    binary(new int[] {DCMPL, DCMPG}, d, d, AbstractValue.Kind.INT);
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(LSHL), l, l));
  }

  @Test
  void numericConversionsCheckSourceAndResultKinds() {
    int[][] conversions = {
      {INEG, 0, 0},
      {I2B, 0, 0},
      {I2C, 0, 0},
      {I2S, 0, 0},
      {LNEG, 1, 1},
      {FNEG, 2, 2},
      {DNEG, 3, 3},
      {L2I, 1, 0},
      {F2I, 2, 0},
      {D2I, 3, 0},
      {I2L, 0, 1},
      {F2L, 2, 1},
      {D2L, 3, 1},
      {I2F, 0, 2},
      {L2F, 1, 2},
      {D2F, 3, 2},
      {I2D, 0, 3},
      {L2D, 1, 3},
      {F2D, 2, 3}
    };
    AbstractValue[] values = {i, l, fl, d};
    for (int[] conversion : conversions) {
      AbstractValue actual = apply(new InsnNode(conversion[0]), values[conversion[1]]).pop();
      assertEquals(values[conversion[2]].kind(), actual.kind());
      assertEquals(i.taint(), actual.taint());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(conversion[0]), r));
    }
  }

  @Test
  void allocationsArraysAndConstants() {
    for (int op :
        new int[] {
          ACONST_NULL,
          ICONST_M1,
          ICONST_0,
          ICONST_1,
          ICONST_2,
          ICONST_3,
          ICONST_4,
          ICONST_5,
          LCONST_0,
          LCONST_1,
          FCONST_0,
          FCONST_1,
          FCONST_2,
          DCONST_0,
          DCONST_1
        }) assertEquals(1, apply(new InsnNode(op)).stack.size());
    assertEquals("42", apply(new IntInsnNode(BIPUSH, 42)).pop().constant());
    assertEquals("1024", apply(new IntInsnNode(SIPUSH, 1024)).pop().constant());
    for (Object c :
        new Object[] {42, 42L, 2.5, 1.5f, "literal", Type.getType("Ljava/lang/String;")})
      assertNotNull(apply(new LdcInsnNode(c)).pop());
    assertFalse(apply(new TypeInsnNode(NEW, "Foo")).pop().references().isEmpty());
    assertEquals(AbstractValue.Kind.REF, apply(new IntInsnNode(NEWARRAY, T_INT), i).pop().kind());
    assertEquals(AbstractValue.Kind.REF, apply(new TypeInsnNode(ANEWARRAY, "Foo"), i).pop().kind());
    assertEquals(i.taint(), apply(new MultiANewArrayInsnNode("[[I", 2), i, i).pop().taint());
    for (int op : new int[] {IALOAD, LALOAD, FALOAD, DALOAD, AALOAD, BALOAD, CALOAD, SALOAD})
      assertEquals(i.taint(), apply(new InsnNode(op), r, i).pop().taint());
    for (int op :
        new int[] {IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE})
      assertTrue(
          apply(
                  new InsnNode(op),
                  r,
                  i,
                  switch (op) {
                    case LASTORE -> l;
                    case FASTORE -> fl;
                    case DASTORE -> d;
                    case AASTORE -> r;
                    default -> i;
                  })
              .stack
              .isEmpty());
    assertEquals(r.taint(), apply(new InsnNode(ARRAYLENGTH), r).pop().taint());
    assertEquals(r, apply(new TypeInsnNode(CHECKCAST, "Foo"), r).pop());
    assertEquals(
        AbstractValue.Kind.INT, apply(new TypeInsnNode(INSTANCEOF, "Foo"), r).pop().kind());
  }

  @Test
  void controlAndReturnsUseDeclaredKinds() {
    for (int opcode : new int[] {IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, TABLESWITCH, LOOKUPSWITCH}) {
      assertTrue(apply(new InsnNode(opcode), i).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), r));
    }
    for (int opcode : new int[] {IFNULL, IFNONNULL}) {
      assertTrue(apply(new InsnNode(opcode), r).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), i));
    }
    for (int opcode :
        new int[] {IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE}) {
      assertTrue(apply(new InsnNode(opcode), i, i).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), i, r));
    }
    for (int opcode : new int[] {IF_ACMPEQ, IF_ACMPNE}) {
      assertTrue(apply(new InsnNode(opcode), r, r).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), i, r));
    }
    int[] returns = {IRETURN, LRETURN, FRETURN, DRETURN, ARETURN};
    String[] descriptors = {"()I", "()J", "()F", "()D", "()Ljava/lang/Object;"};
    AbstractValue[] operands = {i, l, fl, d, r};
    for (int index = 0; index < returns.length; index++) {
      int opcode = returns[index];
      String descriptor = descriptors[index];
      assertTrue(apply(descriptor, new InsnNode(opcode), operands[index]).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply("()V", new InsnNode(opcode), i));
    }
    for (int opcode : new int[] {ATHROW, MONITORENTER, MONITOREXIT}) {
      assertTrue(apply(new InsnNode(opcode), r).stack.isEmpty());
      assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(opcode), i));
    }
    for (int op : new int[] {NOP, GOTO, RETURN})
      assertTrue(apply(new InsnNode(op)).stack.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> apply("()I", new InsnNode(RETURN)));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(JSR)));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(RET)));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(255)));
  }

  @Test
  void arrayFieldAndReceiverKindsRejectMalformedOperands() {
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(IALOAD), i, i));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(IALOAD), r, r));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(LASTORE), r, i, i));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(ARRAYLENGTH), i));
    assertThrows(IllegalArgumentException.class, () -> apply(new IntInsnNode(NEWARRAY, T_INT), r));
    assertThrows(IllegalArgumentException.class, () -> apply(new IntInsnNode(NEWARRAY, 255), i));
    assertThrows(
        IllegalArgumentException.class, () -> apply(new MultiANewArrayInsnNode("[I", 2), i, i));
    assertThrows(
        IllegalArgumentException.class, () -> apply(new TypeInsnNode(CHECKCAST, "Foo"), i));
    assertThrows(
        IllegalArgumentException.class, () -> apply(new TypeInsnNode(INSTANCEOF, "Foo"), i));
    assertThrows(
        IllegalArgumentException.class,
        () -> apply(new FieldInsnNode(GETFIELD, "Foo", "x", "I"), i));
    assertThrows(
        IllegalArgumentException.class,
        () -> apply(new FieldInsnNode(PUTFIELD, "Foo", "x", "I"), r, r));
    assertTrue(apply(new FieldInsnNode(PUTFIELD, "Foo", "x", "I"), r, i).stack.isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> apply(new FieldInsnNode(PUTSTATIC, "Foo", "x", "J"), i));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false), i));
    assertEquals(
        AbstractValue.Kind.INT,
        apply(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false), r)
            .pop()
            .kind());
  }

  @Test
  void heapMonotoneAndUnknownModel() {
    HeapSummary h = new HeapSummary();
    assertFalse(h.consumeChanged());
    h.write("A.x", i.taint());
    assertTrue(h.consumeChanged());
    h.write("A.x", Taint.BOTTOM);
    assertFalse(h.consumeChanged());
    h.addElement(r, Taint.source("new"));
    assertTrue(h.elements(r).labels().containsKey("new"));
    assertThrows(UnsupportedOperationException.class, () -> h.snapshot().clear());
    FrameState f =
        apply(
            new MethodInsnNode(
                INVOKESTATIC,
                "Missing",
                "identity",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false),
            r);
    assertTrue(f.pop().taint().unknown());
  }

  @Test
  void invalidCallAndLocalKindsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                new MethodInsnNode(
                    INVOKESTATIC,
                    "Missing",
                    "identity",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false),
                i));
    assertThrows(IllegalArgumentException.class, () -> apply(new VarInsnNode(ILOAD, 99)));
    assertThrows(IllegalArgumentException.class, () -> apply(new VarInsnNode(LSTORE, 0), i));
  }
}
