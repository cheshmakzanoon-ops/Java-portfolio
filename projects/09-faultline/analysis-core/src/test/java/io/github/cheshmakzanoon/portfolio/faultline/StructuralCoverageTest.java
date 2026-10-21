package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.*;
import io.github.cheshmakzanoon.portfolio.faultline.bytecode.MethodDescriptor;
import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class StructuralCoverageTest implements Opcodes {
  @TempDir Path temp;

  @Test
  void duplicateMembersFailInsteadOfReplacingAnEarlierBody() throws Exception {
    for (boolean field : List.of(false, true)) {
      ClassWriter writer = new ClassWriter(0);
      writer.visit(V25, ACC_PUBLIC, "Duplicate", null, "java/lang/Object", null);
      for (int i = 0; i < 2; i++) {
        if (field) writer.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        else {
          MethodVisitor method = writer.visitMethod(ACC_STATIC, "body", "()V", null, null);
          method.visitCode();
          method.visitInsn(RETURN);
          method.visitMaxs(0, 0);
          method.visitEnd();
        }
      }
      writer.visitEnd();
      Path file = temp.resolve("Duplicate.class");
      Files.write(file, writer.toByteArray());
      assertThrows(
          InputFailure.class,
          () ->
              new Analyzer()
                  .scan(
                      List.of(file),
                      List.of(),
                      25,
                      InputLimits.defaults(),
                      AnalysisBudget.defaults()));
    }
  }

  @Test
  void parserExhaustionIncludesUnanalyzedMethodsFromCompletedClasses() throws Exception {
    Path first = temp.resolve("A.class"), second = temp.resolve("B.class");
    Files.write(first, EngineTest.simple("A", V25));
    Files.write(second, EngineTest.simple("B", V25));
    AnalysisBudget measurement = AnalysisBudget.defaults();
    InputManifest manifest =
        new BoundedJarReader(InputLimits.defaults(), 25).read(List.of(first), List.of());
    new ClassFactsReader().read(manifest, measurement);
    var result =
        new Analyzer()
            .scan(
                List.of(first, second),
                List.of(),
                25,
                InputLimits.defaults(),
                new AnalysisBudget(measurement.work(), 120000, 32, 5));
    assertTrue(result.analysis().partial());
    assertEquals(Set.of("A"), result.classes().keySet());
    assertTrue(result.analysis().summaries().isEmpty());
    assertEquals(
        List.of("A.id(Ljava/lang/String;)Ljava/lang/String;", "parser:B.class"),
        result.analysis().unfinished());
  }

  @Test
  void indexedTargetsDoNotRescanMetadataForRepeatedSwitchAndRangeLookups() {
    java.util.concurrent.atomic.AtomicInteger forwardReads =
        new java.util.concurrent.atomic.AtomicInteger();
    LabelNode label =
        new LabelNode() {
          @Override
          public AbstractInsnNode getNext() {
            forwardReads.incrementAndGet();
            return super.getNext();
          }
        };
    MethodNode node = new MethodNode(ACC_STATIC, "target", "()V", null, null);
    node.instructions.add(label);
    for (int i = 0; i < 4096; i++) node.instructions.add(new LineNumberNode(1, label));
    node.instructions.add(new InsnNode(RETURN));
    LabelNode end = new LabelNode();
    node.instructions.add(end);
    MethodIr method = new MethodIr("Indexed", null, node);
    forwardReads.set(0);
    for (int i = 0; i < 16384; i++) assertEquals(0, method.target(label));
    assertEquals(
        0, forwardReads.get(), "lookups must use the immutable index, not a metadata walk");
    assertEquals(1, method.target(end));
    assertThrows(IllegalArgumentException.class, () -> method.target(new LabelNode()));
  }

  @Test
  void fieldDescriptorsAreValidatedAsWholeTypesBeforeAnalysis() {
    for (String valid : List.of("I", "J", "Ljava/lang/String;", "[[Ljava/lang/Object;", "[Z"))
      assertDoesNotThrow(() -> MethodDescriptor.field(valid));
    for (String invalid :
        List.of("", "II", "V", "()V", "[V", "L;", "Ljava/lang/String", "Ljava//lang/String;"))
      assertThrows(IllegalArgumentException.class, () -> MethodDescriptor.field(invalid), invalid);
    assertThrows(IllegalArgumentException.class, () -> MethodDescriptor.field(null));
  }

  @Test
  void provenanceJoinIsCommutativeForDelimiterBearingPathSteps() {
    List<String> left = List.of("A", "B|C"), right = List.of("A|B", "C");
    InstructionId sink = new InstructionId("Sink.run()V", 0);
    MethodSummary a = summary(sink, left), b = summary(sink, right);
    assertEquals(a.join(b), b.join(a));
    assertEquals(left, a.join(b).flows().get("sink").callPath());
    assertEquals(a.join(b), a.join(b).join(a), "fixed-point replay keeps the chosen witness");
  }

  private MethodSummary summary(InstructionId sink, List<String> path) {
    return new MethodSummary(
        Taint.BOTTOM,
        new TreeMap<>(
            Map.of(
                "sink",
                new MethodSummary.Flow(
                    "BG-SQL-TEXT", 1, sink, "sink", Taint.BOTTOM, path, false))));
  }
}
