package io.github.cheshmakzanoon.portfolio.faultline.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.input.InputLimits;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.AnalysisBudget;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.Analyzer;
import io.github.cheshmakzanoon.portfolio.faultline.report.Finding;
import io.github.cheshmakzanoon.portfolio.faultline.report.FindingReporter;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates one tiny class per built-in sink descriptor, feeds the sink's documented argument from
 * {@code System.getenv}, and expects exactly one finding under the sink's own rule. This both
 * proves every table entry is live and, because each descriptor is resolved reflectively against
 * the running JDK, proves none of them is a typo for a method that does not exist.
 */
class BuiltInSinkCoverageTest implements Opcodes {
  @TempDir Path temp;

  private static boolean exists(String owner, String name, String descriptor) throws Exception {
    Class<?> type = Class.forName(owner.replace('/', '.'));
    if (name.equals("<init>")) {
      for (Constructor<?> c : type.getDeclaredConstructors())
        if (Type.getConstructorDescriptor(c).equals(descriptor)) return true;
      return false;
    }
    for (Method m : type.getDeclaredMethods())
      if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(descriptor)) return true;
    return false;
  }

  private static boolean isStatic(String owner, String name, String descriptor) throws Exception {
    for (Method m : Class.forName(owner.replace('/', '.')).getDeclaredMethods())
      if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(descriptor))
        return Modifier.isStatic(m.getModifiers());
    throw new AssertionError(owner + "." + name + descriptor);
  }

  private static void defaultValue(MethodVisitor m, Type type) {
    switch (type.getSort()) {
      case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> m.visitInsn(ICONST_0);
      case Type.LONG -> m.visitInsn(LCONST_0);
      case Type.FLOAT -> m.visitInsn(FCONST_0);
      case Type.DOUBLE -> m.visitInsn(DCONST_0);
      default -> m.visitInsn(ACONST_NULL);
    }
  }

  private static byte[] generate(String className, String sink, RuleModels.Sink contract)
      throws Exception {
    int paren = sink.indexOf('(');
    int dot = sink.lastIndexOf('.', paren);
    String owner = sink.substring(0, dot), name = sink.substring(dot + 1, paren);
    String descriptor = sink.substring(paren);
    boolean constructor = name.equals("<init>");
    boolean isStatic = !constructor && isStatic(owner, name, descriptor);
    boolean isInterface = Class.forName(owner.replace('/', '.')).isInterface();

    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(V25, ACC_PUBLIC, className, null, "java/lang/Object", null);
    MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
    m.visitCode();
    if (constructor) {
      m.visitTypeInsn(NEW, owner);
      m.visitInsn(DUP);
    } else if (!isStatic) m.visitInsn(ACONST_NULL);
    Type[] arguments = Type.getArgumentTypes(descriptor);
    for (int i = 0; i < arguments.length; i++) {
      if (i != contract.argument()) {
        defaultValue(m, arguments[i]);
        continue;
      }
      m.visitLdcInsn("EXTERNAL");
      m.visitMethodInsn(
          INVOKESTATIC,
          "java/lang/System",
          "getenv",
          "(Ljava/lang/String;)Ljava/lang/String;",
          false);
      if (arguments[i].getDescriptor().equals("Ljava/net/URI;"))
        m.visitMethodInsn(
            INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;", false);
      else if (arguments[i].getDescriptor().equals("Ljava/nio/file/Path;")) {
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/String");
        m.visitMethodInsn(
            INVOKESTATIC,
            "java/nio/file/Path",
            "of",
            "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
            true);
      } else
        assertEquals("Ljava/lang/String;", arguments[i].getDescriptor(), sink + " argument type");
    }
    m.visitMethodInsn(
        constructor
            ? INVOKESPECIAL
            : isStatic ? INVOKESTATIC : isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL,
        owner,
        name,
        descriptor,
        isInterface);
    Type result = constructor ? Type.VOID_TYPE : Type.getReturnType(descriptor);
    if (result.getSize() == 1) m.visitInsn(POP);
    else if (result.getSize() == 2) m.visitInsn(POP2);
    m.visitInsn(RETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  @Test
  void everyBuiltInSinkDescriptorIsARealJdkMethodAndReportsUnderItsOwnRule() throws Exception {
    RuleModels models = new RuleModels();
    Map<String, String> classBySink = new TreeMap<>();
    int index = 0;
    for (var entry : models.sinks().entrySet()) {
      String sink = entry.getKey();
      int paren = sink.indexOf('(');
      int dot = sink.lastIndexOf('.', paren);
      assertTrue(
          exists(sink.substring(0, dot), sink.substring(dot + 1, paren), sink.substring(paren)),
          "not a JDK method: " + sink);
      String className = String.format("generated/Sink%03d", index++);
      Path file = temp.resolve(className + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, generate(className, sink, entry.getValue()));
      classBySink.put(sink, className);
    }
    assertEquals(29, classBySink.size());

    Analyzer.Result analysis =
        new Analyzer()
            .scan(List.of(temp), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
    assertFalse(analysis.analysis().partial(), analysis.analysis().diagnostics().toString());
    List<Finding> findings = new FindingReporter().findings(analysis);

    List<String> silent = new ArrayList<>(), wrongRule = new ArrayList<>();
    for (var entry : classBySink.entrySet()) {
      RuleModels.Sink contract = models.sink(entry.getKey());
      List<Finding> mine =
          findings.stream()
              .filter(f -> f.sink().method().startsWith(entry.getValue() + "."))
              .toList();
      if (mine.isEmpty()) silent.add(entry.getKey());
      else if (mine.stream().anyMatch(f -> !f.rule().equals(contract.id())))
        wrongRule.add(entry.getKey());
    }
    assertEquals(List.of(), silent, "sinks that did not report their tainted argument");
    assertEquals(List.of(), wrongRule);
    Set<String> rules = new TreeSet<>();
    findings.forEach(f -> rules.add(f.rule()));
    Set<String> expected = new TreeSet<>(RuleModels.CONTEXTS.keySet());
    expected.remove("BG-SHELL-COMMAND");
    assertEquals(expected, rules);
  }
}
