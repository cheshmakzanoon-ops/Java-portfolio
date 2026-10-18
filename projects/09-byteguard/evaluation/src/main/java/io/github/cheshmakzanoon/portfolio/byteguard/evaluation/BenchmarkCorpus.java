package io.github.cheshmakzanoon.portfolio.byteguard.evaluation;

import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Trusted deterministic workload generator. Never invokes generated target bytecode. */
public final class BenchmarkCorpus implements Opcodes {
  public static void main(String[] args) throws Exception {
    int count = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
    if (count != 10_000)
      throw new IllegalArgumentException("acceptance corpus is fixed at 10,000 classes");
    Path output = Path.of(args[0]);
    Files.createDirectories(output.toAbsolutePath().getParent());
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
      for (int i = 0; i < count; i++) {
        String name = String.format("benchmark/Case%05d", i);
        byte[] bytes = generate(name, i);
        ZipEntry entry = new ZipEntry(name + ".class");
        entry.setTime(0);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
      }
    }
  }

  static byte[] generate(String name, int seed) {
    ClassWriter c = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
    c.visit(V25, ACC_PUBLIC, name, null, "java/lang/Object", null);
    MethodVisitor source = c.visitMethod(ACC_STATIC, "source", "()Ljava/lang/String;", null, null);
    source.visitCode();
    source.visitLdcInsn("INPUT_" + seed);
    source.visitMethodInsn(
        INVOKESTATIC,
        "java/lang/System",
        "getenv",
        "(Ljava/lang/String;)Ljava/lang/String;",
        false);
    source.visitInsn(ARETURN);
    source.visitMaxs(0, 0);
    source.visitEnd();
    MethodVisitor sink =
        c.visitMethod(
            ACC_STATIC,
            "sink",
            "(Ljava/sql/Statement;Ljava/lang/String;)V",
            null,
            new String[] {"java/lang/Exception"});
    sink.visitCode();
    sink.visitVarInsn(ALOAD, 0);
    sink.visitVarInsn(ALOAD, 1);
    sink.visitMethodInsn(
        INVOKEINTERFACE, "java/sql/Statement", "execute", "(Ljava/lang/String;)Z", true);
    sink.visitInsn(POP);
    sink.visitInsn(RETURN);
    sink.visitMaxs(0, 0);
    sink.visitEnd();
    MethodVisitor run =
        c.visitMethod(
            ACC_PUBLIC | ACC_STATIC,
            "run",
            "(Ljava/sql/Statement;)V",
            null,
            new String[] {"java/lang/Exception"});
    run.visitCode();
    run.visitMethodInsn(INVOKESTATIC, name, "source", "()Ljava/lang/String;", false);
    run.visitVarInsn(ASTORE, 1);
    run.visitInsn(ICONST_0);
    run.visitVarInsn(ISTORE, 2);
    Label loop = new Label(), end = new Label();
    run.visitLabel(loop);
    run.visitVarInsn(ILOAD, 2);
    run.visitIntInsn(BIPUSH, 5);
    run.visitJumpInsn(IF_ICMPGE, end);
    run.visitIincInsn(2, 1);
    run.visitJumpInsn(GOTO, loop);
    run.visitLabel(end);
    run.visitVarInsn(ALOAD, 0);
    run.visitVarInsn(ALOAD, 1);
    run.visitMethodInsn(
        INVOKESTATIC, name, "sink", "(Ljava/sql/Statement;Ljava/lang/String;)V", false);
    run.visitInsn(RETURN);
    run.visitMaxs(0, 0);
    run.visitEnd();
    MethodVisitor wide = c.visitMethod(ACC_STATIC, "wide", "(JD)J", null, null);
    wide.visitCode();
    wide.visitVarInsn(LLOAD, 0);
    wide.visitVarInsn(DLOAD, 2);
    wide.visitInsn(D2L);
    wide.visitInsn(LADD);
    wide.visitInsn(LRETURN);
    wide.visitMaxs(0, 0);
    wide.visitEnd();
    c.visitEnd();
    return c.toByteArray();
  }
}
