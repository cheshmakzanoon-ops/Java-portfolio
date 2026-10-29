package io.github.cheshmakzanoon.portfolio.faultline.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.bytecode.InstructionId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextReportTest {
  private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);

  private static Finding finding(
      String rule, String fingerprint, String uri, int line, String confidence, List<String> path) {
    InstructionId source = new InstructionId("S.read()Ljava/lang/String;", 1);
    InstructionId sink = new InstructionId("K.use(Ljava/lang/String;)V", 3);
    return new Finding(
        rule,
        "1.1.0",
        "warning",
        confidence,
        fingerprint,
        "java/lang/System.getenv(Ljava/lang/String;)Ljava/lang/String;",
        "java/sql/Statement.execute(Ljava/lang/String;)Z",
        source,
        sink,
        path,
        false,
        new Finding.Location("S.java", 7, source.method(), 1),
        new Finding.Location(uri, line, sink.method(), 3),
        List.of());
  }

  @Test
  void ordersByRuleThenLocationAndMarksBaselineMembership() {
    Finding late = finding("BG-SQL-TEXT", B, "K.java", 30, "medium", List.of());
    Finding early = finding("BG-SQL-TEXT", A, "K.java", 9, "medium", List.of());
    Finding other = finding("BG-PATH-ACCESS", C, "Z.java", 1, "medium", List.of());
    String text = TextReport.render(List.of(late, early, other), Set.of(B), false, List.of(), 4);
    assertTrue(
        text.startsWith(
            "Faultline 1.1.0 COMPLETE: 3 findings (2 new, 1 unchanged), 4 diagnostics\n"));
    int path = text.indexOf("BG-PATH-ACCESS"),
        first = text.indexOf("K.java:9"),
        second = text.indexOf("K.java:30");
    assertTrue(0 < path && path < first && first < second, text);
    assertTrue(text.contains("[unchanged] BG-SQL-TEXT  warning/medium  " + B.substring(0, 12)));
    assertTrue(text.contains("[new] BG-SQL-TEXT  warning/medium  " + A.substring(0, 12)));
    assertEquals(
        text, TextReport.render(List.of(other, early, late), Set.of(B), false, List.of(), 4));
  }

  @Test
  void showsWitnessSourceAndSinkWithoutInventingMissingLines() {
    Finding withoutLine =
        finding("BG-SQL-TEXT", A, "K.class", -1, "low", List.of("M.a()V@1", "M.b()V@2"));
    String text = TextReport.render(List.of(withoutLine), Set.of(), false, List.of(), 0);
    assertTrue(text.contains("  source  S.java:7  java/lang/System.getenv"));
    assertTrue(text.contains("  sink    K.class  java/sql/Statement.execute"), text);
    assertFalse(text.contains("K.class:"), "a missing debug line is not fabricated");
    assertTrue(text.contains("  via     M.a()V@1 > M.b()V@2\n"));
    assertTrue(text.contains("passes through code the model treats as unknown"));
    assertTrue(text.contains("1 finding (1 new, 0 unchanged)"));
  }

  @Test
  void truncatedWitnessesAndPartialCoverageAreSaidPlainly() {
    InstructionId source = new InstructionId("S.read()Ljava/lang/String;", 1);
    Finding truncated =
        new Finding(
            "BG-SQL-TEXT",
            "1.1.0",
            "warning",
            "medium",
            A,
            "src",
            "snk",
            source,
            source,
            List.of("M.a()V@1"),
            true,
            new Finding.Location("S.java", 1, "S.read()", 1),
            new Finding.Location("S.java", 2, "S.read()", 1),
            List.of());
    String text = TextReport.render(List.of(truncated), Set.of(), true, List.of("class X"), 2);
    assertTrue(text.contains("PARTIAL: 1 finding"));
    assertTrue(text.contains("M.a()V@1 > ...\n"));
    assertTrue(text.contains("Coverage is incomplete; absence of findings below proves nothing."));
    assertTrue(text.contains("  unfinished: class X\n"));
    String clean = TextReport.render(List.of(), Set.of(), false, List.of(), 0);
    assertEquals(
        "Faultline 1.1.0 COMPLETE: 0 findings (0 new, 0 unchanged), 0 diagnostics\n", clean);
    assertEquals("Faultline INVALID/FAILED: nope\n", TextReport.failure("nope", false));
    assertEquals(
        "Faultline PARTIAL: deadline exceeded\n", TextReport.failure("deadline exceeded", true));
  }
}
