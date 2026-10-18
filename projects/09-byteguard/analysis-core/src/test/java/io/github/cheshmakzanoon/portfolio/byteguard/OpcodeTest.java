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
      d = AbstractValue.of(AbstractValue.Kind.DOUBLE, Taint.source("src")),
      r = AbstractValue.reference(Taint.source("src"), "allocation");

  FrameState apply(AbstractInsnNode node, AbstractValue... stack) {
    MethodNode n = new MethodNode(ACC_STATIC, "test", "()V", null, null);
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

  @Test
  void arithmeticPreservesDataDependencies() {
    for (int op :
        new int[] {
          IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR, LCMP, FCMPL, FCMPG,
          DCMPL, DCMPG
        }) assertEquals(i.taint(), apply(new InsnNode(op), i, i).pop().taint());
    for (int op : new int[] {LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR})
      assertEquals(AbstractValue.Kind.LONG, apply(new InsnNode(op), l, l).pop().kind());
    for (int op : new int[] {FADD, FSUB, FMUL, FDIV, FREM})
      assertEquals(AbstractValue.Kind.FLOAT, apply(new InsnNode(op), i, i).pop().kind());
    for (int op : new int[] {DADD, DSUB, DMUL, DDIV, DREM})
      assertEquals(AbstractValue.Kind.DOUBLE, apply(new InsnNode(op), d, d).pop().kind());
    for (int op :
        new int[] {
          INEG, I2B, I2C, I2S, L2I, F2I, D2I, LNEG, I2L, F2L, D2L, FNEG, I2F, L2F, D2F, DNEG, I2D,
          L2D, F2D
        }) assertEquals(i.taint(), apply(new InsnNode(op), i).pop().taint());
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
      assertTrue(apply(new InsnNode(op), r, i, i).stack.isEmpty());
    assertEquals(Taint.BOTTOM, apply(new InsnNode(ARRAYLENGTH), r).pop().taint());
    assertEquals(r, apply(new TypeInsnNode(CHECKCAST, "Foo"), r).pop());
    assertEquals(
        AbstractValue.Kind.INT, apply(new TypeInsnNode(INSTANCEOF, "Foo"), r).pop().kind());
  }

  @Test
  void controlAndReturns() {
    for (int op :
        new int[] {
          IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL, TABLESWITCH, LOOKUPSWITCH
        }) assertTrue(apply(new InsnNode(op), i).stack.isEmpty());
    for (int op :
        new int[] {
          IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE
        }) assertTrue(apply(new InsnNode(op), i, i).stack.isEmpty());
    for (int op :
        new int[] {IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, ATHROW, MONITORENTER, MONITOREXIT})
      assertTrue(apply(new InsnNode(op), i).stack.isEmpty());
    for (int op : new int[] {NOP, GOTO, RETURN})
      assertTrue(apply(new InsnNode(op)).stack.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(JSR)));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(RET)));
    assertThrows(IllegalArgumentException.class, () -> apply(new InsnNode(255)));
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
