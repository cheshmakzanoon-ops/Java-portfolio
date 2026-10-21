package io.github.cheshmakzanoon.portfolio.faultline.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

class ExplanationOwnershipTest implements Opcodes {
  @TempDir Path temp;

  private Analyzer.Result fixture(Path input) throws Exception {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(V25, ACC_PUBLIC, "Protected", null, "java/lang/Object", null);
    MethodVisitor method = writer.visitMethod(ACC_STATIC, "run", "()V", null, null);
    method.visitCode();
    method.visitInsn(RETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    writer.visitEnd();
    Files.write(input, writer.toByteArray());
    return new Analyzer()
        .scan(List.of(input), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
  }

  @Test
  void explanationSymlinkCannotTruncateTheScannedClass() throws Exception {
    Path input = temp.resolve("Protected.class"),
        directory = Files.createDirectory(temp.resolve("explain"));
    var analysis = fixture(input);
    byte[] original = Files.readAllBytes(input);
    Path output = directory.resolve(StableIdentity.method("Protected.run()V") + ".dot");
    Files.createSymbolicLink(output, input);
    assertThrows(IOException.class, () -> new AnalysisExplainer().write(analysis, directory, null));
    assertArrayEquals(original, Files.readAllBytes(input));
    assertTrue(Files.isSymbolicLink(output));
  }

  @Test
  void explanationHardlinkIsReplacedWithoutChangingTheScannedInode() throws Exception {
    Path input = temp.resolve("Protected.class"),
        directory = Files.createDirectory(temp.resolve("explain"));
    var analysis = fixture(input);
    byte[] original = Files.readAllBytes(input);
    Path output = directory.resolve(StableIdentity.method("Protected.run()V") + ".dot");
    Files.createLink(output, input);
    assertTrue(Files.isSameFile(output, input));
    new AnalysisExplainer().write(analysis, directory, null);
    assertArrayEquals(original, Files.readAllBytes(input));
    assertFalse(Files.isSameFile(output, input));
    assertTrue(Files.readString(output).startsWith("digraph"));
    assertTrue(Files.isRegularFile(directory.resolve("analysis.json")));
  }

  @Test
  void baselineNamedPipeIsRejectedBeforeAnyBlockingJsonRead() throws Exception {
    Path fifo = temp.resolve("baseline.sarif");
    Process create = new ProcessBuilder("mkfifo", fifo.toString()).start();
    assertEquals(0, create.waitFor());
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> assertThrows(IOException.class, () -> new SarifExporter().baseline(fifo)));
  }
}
