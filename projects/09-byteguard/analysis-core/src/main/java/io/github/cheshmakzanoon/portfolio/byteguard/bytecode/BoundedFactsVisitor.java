package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;

import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.AnalysisBudget;
import org.objectweb.asm.*;

/** Charges retained visitor facts before ASM tree allocation, including parser-stage work. */
final class BoundedFactsVisitor extends ClassVisitor {
  private final AnalysisBudget budget;

  private record Member(String name, String descriptor) {}

  private final java.util.Set<Member> fields = new java.util.HashSet<>();
  private final java.util.Set<Member> methods = new java.util.HashSet<>();

  BoundedFactsVisitor(ClassVisitor delegate, AnalysisBudget budget) {
    super(Opcodes.ASM9, delegate);
    this.budget = budget;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    budget.reserveFact();
    if (interfaces != null) for (String ignored : interfaces) budget.reserveFact();
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public FieldVisitor visitField(
      int access, String name, String descriptor, String signature, Object value) {
    budget.reserveFact();
    MethodDescriptor.field(descriptor);
    if (!fields.add(new Member(name, descriptor)))
      throw new IllegalArgumentException("duplicate field declaration");
    FieldVisitor delegate = super.visitField(access, name, descriptor, signature, value);
    return new FieldVisitor(Opcodes.ASM9, delegate) {
      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(
          int typeRef, TypePath path, String descriptor, boolean visible) {
        return null;
      }

      @Override
      public void visitAttribute(Attribute attribute) {}
    };
  }

  @Override
  public void visitSource(String source, String debug) {
    budget.reserveFact();
    super.visitSource(source, null);
  }

  @Override
  public ModuleVisitor visitModule(String name, int access, String version) {
    budget.reserveFact();
    return null;
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    budget.reserveFact();
    super.visitInnerClass(name, outerName, innerName, access);
  }

  @Override
  public void visitNestMember(String member) {
    budget.reserveFact();
    super.visitNestMember(member);
  }

  @Override
  public void visitPermittedSubclass(String member) {
    budget.reserveFact();
    super.visitPermittedSubclass(member);
  }

  @Override
  public RecordComponentVisitor visitRecordComponent(
      String name, String descriptor, String signature) {
    budget.reserveFact();
    return null;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
    return null;
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      int typeRef, TypePath path, String descriptor, boolean visible) {
    return null;
  }

  @Override
  public void visitAttribute(Attribute attribute) {}

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String descriptor, String signature, String[] exceptions) {
    budget.reserveFact();
    if (exceptions != null) for (String ignored : exceptions) budget.reserveFact();
    if (!methods.add(new Member(name, descriptor)))
      throw new IllegalArgumentException("duplicate method declaration");
    MethodDescriptor.parameterSlots(descriptor, (access & Opcodes.ACC_STATIC) != 0);
    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
    return new MethodVisitor(Opcodes.ASM9, delegate) {
      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitParameterAnnotation(
          int parameter, String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitAnnotationDefault() {
        return null;
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(
          int typeRef, TypePath path, String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitInsnAnnotation(
          int typeRef, TypePath path, String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitTryCatchAnnotation(
          int typeRef, TypePath path, String descriptor, boolean visible) {
        return null;
      }

      @Override
      public AnnotationVisitor visitLocalVariableAnnotation(
          int typeRef,
          TypePath path,
          Label[] start,
          Label[] end,
          int[] index,
          String descriptor,
          boolean visible) {
        return null;
      }

      @Override
      public void visitAttribute(Attribute attribute) {}

      @Override
      public void visitInsn(int opcode) {
        budget.reserveFact();
        super.visitInsn(opcode);
      }

      @Override
      public void visitIntInsn(int opcode, int operand) {
        budget.reserveFact();
        super.visitIntInsn(opcode, operand);
      }

      @Override
      public void visitVarInsn(int opcode, int varIndex) {
        budget.reserveFact();
        super.visitVarInsn(opcode, varIndex);
      }

      @Override
      public void visitTypeInsn(int opcode, String type) {
        budget.reserveFact();
        super.visitTypeInsn(opcode, type);
      }

      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        budget.reserveFact();
        MethodDescriptor.field(descriptor);
        super.visitFieldInsn(opcode, owner, name, descriptor);
      }

      @Override
      public void visitMethodInsn(
          int opcode, String owner, String name, String descriptor, boolean isInterface) {
        budget.reserveFact();
        MethodDescriptor.parameterSlots(descriptor, opcode == Opcodes.INVOKESTATIC);
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }

      @Override
      public void visitInvokeDynamicInsn(
          String name, String descriptor, Handle bootstrap, Object... arguments) {
        budget.reserveFact();
        MethodDescriptor.parameterSlots(descriptor, true);
        for (Object ignored : arguments) budget.reserveFact();
        super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
      }

      @Override
      public void visitJumpInsn(int opcode, Label label) {
        budget.reserveFact();
        super.visitJumpInsn(opcode, label);
      }

      @Override
      public void visitLabel(Label label) {
        budget.reserveFact();
        super.visitLabel(label);
      }

      @Override
      public void visitLdcInsn(Object value) {
        budget.reserveFact();
        if (value instanceof ConstantDynamic dynamic)
          MethodDescriptor.field(dynamic.getDescriptor());
        super.visitLdcInsn(value);
      }

      @Override
      public void visitIincInsn(int variable, int increment) {
        budget.reserveFact();
        super.visitIincInsn(variable, increment);
      }

      @Override
      public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        budget.reserveFact();
        for (Label ignored : labels) budget.reserveFact();
        super.visitTableSwitchInsn(min, max, dflt, labels);
      }

      @Override
      public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        budget.reserveFact();
        for (Label ignored : labels) budget.reserveFact();
        super.visitLookupSwitchInsn(dflt, keys, labels);
      }

      @Override
      public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
        budget.reserveFact();
        super.visitMultiANewArrayInsn(descriptor, dimensions);
      }

      @Override
      public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        budget.reserveFact();
        super.visitTryCatchBlock(start, end, handler, type);
      }

      @Override
      public void visitLocalVariable(
          String name, String descriptor, String signature, Label start, Label end, int index) {
        budget.reserveFact();
        super.visitLocalVariable(name, descriptor, signature, start, end, index);
      }

      @Override
      public void visitLineNumber(int line, Label start) {
        budget.reserveFact();
        super.visitLineNumber(line, start);
      }
    };
  }
}
