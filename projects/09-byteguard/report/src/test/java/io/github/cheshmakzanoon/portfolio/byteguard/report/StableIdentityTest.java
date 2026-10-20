package io.github.cheshmakzanoon.portfolio.byteguard.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.InstructionId;
import io.github.cheshmakzanoon.portfolio.byteguard.input.BoundedJarReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StableIdentityTest {
  @Test
  void ordinaryFindingAndMethodHashesKeepTheirEstablishedBytes() {
    InstructionId source = new InstructionId("A.source()I", 2),
        sink = new InstructionId("B.sink(I)V", 9);
    assertEquals(
        BoundedJarReader.sha(
            ("BG-SQL-TEXT|" + source + "|" + sink).getBytes(StandardCharsets.UTF_8)),
        StableIdentity.finding("BG-SQL-TEXT", source, sink));
    assertEquals(
        BoundedJarReader.sha(source.method().getBytes(StandardCharsets.UTF_8)),
        StableIdentity.method(source.method()));
  }

  @Test
  void tupleBoundariesAndOrdinalsCannotBeConfusedWithJvmPunctuation() {
    InstructionId sourceA = new InstructionId("C.s()I@0|X()V", 0),
        sinkA = new InstructionId("Y.t()V", 0);
    InstructionId sourceB = new InstructionId("C.s()I", 0),
        sinkB = new InstructionId("X()V@0|Y.t()V", 0);
    assertEquals(
        sourceA + "|" + sinkA, sourceB + "|" + sinkB, "legacy delimiter collision control");
    assertNotEquals(
        StableIdentity.finding("BG-SQL-TEXT", sourceA, sinkA),
        StableIdentity.finding("BG-SQL-TEXT", sourceB, sinkB));
    assertNotEquals(
        StableIdentity.finding("BG-SQL-TEXT", sourceA, sinkA),
        StableIdentity.finding("BG-SQL-TEXT", new InstructionId(sourceA.method(), 1), sinkA));
  }

  @Test
  void loneUtf16CodeUnitsRemainDistinctInFindingAndExplanationNames() {
    String first = "C.read\uD800()V", second = "C.read\uD801()V";
    assertArrayEquals(
        first.getBytes(StandardCharsets.UTF_8),
        second.getBytes(StandardCharsets.UTF_8),
        "replacement UTF-8 is an independent collision control");
    assertNotEquals(StableIdentity.method(first), StableIdentity.method(second));
    InstructionId sink = new InstructionId("C.sink()V", 4);
    assertNotEquals(
        StableIdentity.finding("BG-SQL-TEXT", new InstructionId(first, 0), sink),
        StableIdentity.finding("BG-SQL-TEXT", new InstructionId(second, 0), sink));
    assertNotEquals(
        StableIdentity.method(first),
        StableIdentity.finding("method", new InstructionId(first, 0), sink));
  }
}
