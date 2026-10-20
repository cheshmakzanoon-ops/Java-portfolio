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
  void structuredHashesRetainIndependentPublishedTupleVectors() {
    // Generated independently with Python struct: NUL, u16 ASCII domain length,
    // domain bytes, i32 field count, then i32 UTF-16 unit length + UTF-16BE fields.
    assertEquals(
        "40730ca2450575b0704ac85c8dd0a7fc41a1b0657eb40d248c34fb8a9ce55f86",
        StableIdentity.method("C.read\uD800()V"));
    assertEquals(
        "5762c2571ac1d196f835befe03d04fcb6fbc126260abc4379f34a1b3d5cf54d9",
        StableIdentity.finding(
            "BG-SQL-TEXT", new InstructionId("C.s()I@0|X()V", 0), new InstructionId("Y.t()V", 0)));
  }

  @Test
  void loneUtf16CodeUnitsRemainDistinctInFindingAndExplanationNames() {
    String supplementary = "C.read\uD83D\uDE00()V";
    assertEquals(
        BoundedJarReader.sha(supplementary.getBytes(StandardCharsets.UTF_8)),
        StableIdentity.method(supplementary));
    assertNotEquals(
        StableIdentity.method("C.read\uDC00()V"), StableIdentity.method("C.read\uD800()V"));
    assertNotEquals(StableIdentity.method("\uD800"), StableIdentity.method("\uD801"));
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
