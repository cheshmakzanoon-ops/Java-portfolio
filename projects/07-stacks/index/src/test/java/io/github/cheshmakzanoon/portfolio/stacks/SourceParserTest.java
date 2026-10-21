package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceParserTest {
  @TempDir Path directory;

  @Test
  void actualSyntaxSeparatesMembersReceiversStringsAndRepeatedCalls() throws Exception {
    var unit =
        SourceJavaParser.parse(
            """
        package sample;
        final class Container {
          private static int bounded(int n) { if(n>10) throw new IllegalArgumentException(); return n; }
          int read(int x) { bounded(x); bounded(x); return 0; }
          int other(int x) { return bounded(x); }
          String decoy() { return "MessageDigest.getInstance(x).digest(y)"; }
        }
        """);
    assertEquals(SourceFacts.Status.OBSERVED, unit.status());
    var read =
        unit.members().stream().filter(m -> m.name().equals("read")).findFirst().orElseThrow();
    assertEquals(
        List.of("bounded", "bounded"),
        read.calls().stream().map(c -> c.expression().text()).toList());
    var helper =
        unit.members().stream().filter(m -> m.name().equals("bounded")).findFirst().orElseThrow();
    assertTrue(helper.privateMember());
    assertTrue(helper.staticMember());
    assertEquals(1, helper.guards().size());
    var decoy =
        unit.members().stream().filter(m -> m.name().equals("decoy")).findFirst().orElseThrow();
    assertTrue(decoy.calls().isEmpty());
    assertFalse(decoy.identifiers().contains("MessageDigest"));
    byte[] encoded = SourceWire.encode(unit);
    assertEquals(unit, SourceWire.decode(encoded));
    assertArrayEquals(encoded, SourceWire.encode(unit));
    encoded[encoded.length - 1] ^= 1;
    assertThrows(IOException.class, () -> SourceWire.decode(encoded));
  }

  @Test
  void localAnonymousDeclarationsAndInitializerCallsRemainInInventory() throws Exception {
    var unit =
        SourceJavaParser.parse(
            """
        class Outer {
          int field = init();
          { init(); }
          int init() { return 1; }
          Object factory() {
            class Local { private int bounded(int n) { return n; } }
            return new Object() { int bytes() { return 0; } };
          }
        }
        """);
    assertTrue(unit.declarationInventoryKnown());
    assertTrue(unit.members().stream().anyMatch(m -> m.name().equals("bounded")));
    assertTrue(unit.members().stream().anyMatch(m -> m.name().equals("bytes")));
    assertTrue(
        unit.members().stream()
            .anyMatch(m -> m.name().equals("<field:field>") && m.calls().size() == 1));
    assertTrue(
        unit.members().stream()
            .anyMatch(m -> m.name().equals("<initializer>") && m.calls().size() == 1));
    assertEquals(
        unit.members().size(),
        unit.members().stream().map(SourceFacts.Member::signature).distinct().count());
  }

  @Test
  void sourceNestingTokenUnicodeAndMalformedQuotasAbstainAsWholeUnits() throws Exception {
    assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check(" ".repeat(65537)));
    assertEquals(
        SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check("class " + "a".repeat(257) + " {}"));
    assertEquals(
        SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check("{".repeat(33) + "}".repeat(33)));
    assertEquals(
        SourceFacts.Status.SYNTAX, SourcePreflight.check("class A { String x=\"unterminated; }"));
    assertEquals(
        SourceFacts.Status.UNSUPPORTED, SourcePreflight.check("class A { String x=\"\\u0061\"; }"));
    var bad = SourceJavaParser.parse("class A { void broken( {} }");
    assertFalse(bad.declarationInventoryKnown());
    assertTrue(bad.members().isEmpty());
    assertThrows(IOException.class, () -> SourceWire.decode(new byte[16385]));
  }

  @Test
  void supplementaryIdentifiersUseTheSameUtf16QuotaBeforeAndAfterParsing() throws Exception {
    String letter = new String(Character.toChars(0x10400));
    String maximum = letter.repeat(128);
    String accepted = "class Glyphs { int " + maximum + "() { return 1; } }";
    assertEquals(SourceFacts.Status.OBSERVED, SourcePreflight.check(accepted));
    var parsed = SourceJavaParser.parse(accepted);
    assertEquals(SourceFacts.Status.OBSERVED, parsed.status());
    assertEquals(maximum, parsed.members().getFirst().name());
    assertEquals(parsed, SourceWire.decode(SourceWire.encode(parsed)));

    for (String oversized : List.of(maximum + "a", "a" + maximum, letter.repeat(129))) {
      String source = "class Glyphs { int " + oversized + "() { return 1; } }";
      assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourcePreflight.check(source));
      assertEquals(SourceFacts.Status.SOURCE_QUOTA, SourceJavaParser.parse(source).status());
    }
  }

  @Test
  void packagedWorkerProtocolMatchesTrustedKernelAndRecyclesAfter64Units() throws Exception {
    String source = "final class T { int read(int x) { return x; } }";
    var expected = SourceJavaParser.parse(source);
    assertEquals(SourceFacts.Status.OBSERVED, expected.status());
    try (var worker = new SourceParserClient(directory)) {
      for (int i = 0; i < 65; i++) assertEquals(expected, worker.parse(source));
      assertEquals(2, worker.workerStarts());
      assertTrue(worker.maxStartupNanos() > 0 && worker.maxStartupNanos() <= 5_000_000_000L);
      assertTrue(worker.maxUnitNanos() > 0 && worker.maxUnitNanos() <= 1_000_000_000L);
      System.out.println(
          "parserControls starts="
              + worker.workerStarts()
              + " maxStartupNanos="
              + worker.maxStartupNanos()
              + " maxUnitNanos="
              + worker.maxUnitNanos());
      assertEquals(SourceFacts.Status.SOURCE_QUOTA, worker.parse("x".repeat(65537)).status());
    }
    assertArrayEquals(SourceParserClient.bundle(), SourceParserClient.bundle());
    try (var files = Files.list(directory)) {
      Path bundle = files.findFirst().orElseThrow();
      Files.write(bundle, new byte[] {1});
    }
    assertThrows(IOException.class, () -> new SourceParserClient(directory));
  }

  @Test
  void startupAndUnitWatchdogsActuallyReapTheirOwnedChild() throws Exception {
    try (var startup = new SourceParserClient(directory.resolve("startup"), 1, 1_000_000_000L)) {
      assertTrue(
          assertThrows(IOException.class, () -> startup.parse("final class Stopped {} "))
              .getMessage()
              .contains("startup"));
      assertEquals(1, startup.workerStarts());
      assertTrue(startup.lastWorkerPid() > 0);
      assertFalse(
          ProcessHandle.of(startup.lastWorkerPid()).map(ProcessHandle::isAlive).orElse(false));
    }
    try (var unit = new SourceParserClient(directory.resolve("unit"), 5_000_000_000L, 1)) {
      assertThrows(IOException.class, () -> unit.parse("final class Stopped {} "));
      assertEquals(1, unit.workerStarts());
      assertTrue(unit.maxStartupNanos() > 0);
      assertFalse(ProcessHandle.of(unit.lastWorkerPid()).map(ProcessHandle::isAlive).orElse(false));
    }
    assertThrows(IllegalArgumentException.class, () -> new SourceParserClient(directory, 0, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new SourceParserClient(directory, 5_000_000_001L, 1));
  }

  @Test
  void readinessRequiresBoundedExactVersionSchemaAndCompleteFrame() throws Exception {
    byte[] body = SourceParserWorker.ready();
    byte[] valid = readyFrame(body);
    SourceParserWorker.readReady(new DataInputStream(new ByteArrayInputStream(valid)));
    byte[] corrupt = body.clone();
    corrupt[0] ^= 1;
    assertThrows(
        IOException.class,
        () ->
            SourceParserWorker.readReady(
                new DataInputStream(new ByteArrayInputStream(readyFrame(corrupt)))));
    assertThrows(
        IOException.class,
        () ->
            SourceParserWorker.readReady(
                new DataInputStream(
                    new ByteArrayInputStream(Arrays.copyOf(valid, valid.length - 1)))));
    assertThrows(
        IOException.class,
        () ->
            SourceParserWorker.readReady(
                new DataInputStream(new ByteArrayInputStream(readyFrame(new byte[257])))));
    assertThrows(
        IOException.class,
        () ->
            SourceParserWorker.readReady(
                new DataInputStream(
                    new ByteArrayInputStream(readyFrame(Arrays.copyOf(body, body.length + 1))))));
  }

  private static byte[] readyFrame(byte[] body) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(body.length);
      out.write(body);
    }
    return bytes.toByteArray();
  }

  @Test
  void onlyOneActualWorkerCanConsumeTheSharedParentHeapBudget() throws Exception {
    try (var first = new SourceParserClient(directory.resolve("first"));
        var second = new SourceParserClient(directory.resolve("second"))) {
      assertTrue(first.parse("final class First {}").declarationInventoryKnown());
      assertTrue(
          assertThrows(IOException.class, () -> second.parse("final class Second {}"))
              .getMessage()
              .contains("admission"));
      assertEquals(0, second.workerStarts());
      first.close();
      assertTrue(second.parse("final class Second {}").declarationInventoryKnown());
      assertEquals(1, second.workerStarts());
    }
  }
}
