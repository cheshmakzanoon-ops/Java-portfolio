package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceParserTest {
  @TempDir Path directory;

  @Test void actualSyntaxSeparatesMembersReceiversStringsAndRepeatedCalls()throws Exception {
    var unit=SourceJavaParser.parse("""
        package sample;
        final class Container {
          private static int bounded(int n) { if(n>10) throw new IllegalArgumentException(); return n; }
          int read(int x) { bounded(x); bounded(x); return 0; }
          int other(int x) { return bounded(x); }
          String decoy() { return "MessageDigest.getInstance(x).digest(y)"; }
        }
        """);
    assertEquals(SourceFacts.Status.OBSERVED,unit.status());
    var read=unit.members().stream().filter(m->m.name().equals("read")).findFirst().orElseThrow();
    assertEquals(List.of("bounded","bounded"),read.calls().stream().map(c->c.expression().text()).toList());
    var helper=unit.members().stream().filter(m->m.name().equals("bounded")).findFirst().orElseThrow();
    assertTrue(helper.privateMember());assertTrue(helper.staticMember());assertEquals(1,helper.guards().size());
    var decoy=unit.members().stream().filter(m->m.name().equals("decoy")).findFirst().orElseThrow();
    assertTrue(decoy.calls().isEmpty());assertFalse(decoy.identifiers().contains("MessageDigest"));
    byte[] encoded=SourceWire.encode(unit);assertEquals(unit,SourceWire.decode(encoded));
    assertArrayEquals(encoded,SourceWire.encode(unit));
    encoded[encoded.length-1]^=1;assertThrows(IOException.class,()->SourceWire.decode(encoded));
  }

  @Test void localAnonymousDeclarationsAndInitializerCallsRemainInInventory()throws Exception {
    var unit=SourceJavaParser.parse("""
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
    assertTrue(unit.members().stream().anyMatch(m->m.name().equals("bounded")));
    assertTrue(unit.members().stream().anyMatch(m->m.name().equals("bytes")));
    assertTrue(unit.members().stream().anyMatch(m->m.name().equals("<field:field>")&&m.calls().size()==1));
    assertTrue(unit.members().stream().anyMatch(m->m.name().equals("<initializer>")&&m.calls().size()==1));
    assertEquals(unit.members().size(),unit.members().stream().map(SourceFacts.Member::signature).distinct().count());
  }

  @Test void sourceNestingTokenUnicodeAndMalformedQuotasAbstainAsWholeUnits()throws Exception {
    assertEquals(SourceFacts.Status.SOURCE_QUOTA,SourcePreflight.check(" ".repeat(65537)));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA,SourcePreflight.check("class "+"a".repeat(257)+" {}"));
    assertEquals(SourceFacts.Status.SOURCE_QUOTA,SourcePreflight.check("{".repeat(33)+"}".repeat(33)));
    assertEquals(SourceFacts.Status.SYNTAX,SourcePreflight.check("class A { String x=\"unterminated; }"));
    assertEquals(SourceFacts.Status.UNSUPPORTED,SourcePreflight.check("class A { String x=\"\\u0061\"; }"));
    var bad=SourceJavaParser.parse("class A { void broken( {} }");
    assertFalse(bad.declarationInventoryKnown());assertTrue(bad.members().isEmpty());
    assertThrows(IOException.class,()->SourceWire.decode(new byte[16385]));
  }

  @Test void packagedWorkerProtocolMatchesTrustedKernelAndRecyclesAfter64Units()throws Exception {
    String source="final class T { int read(int x) { return x; } }";
    var expected=SourceJavaParser.parse(source);
    assertEquals(SourceFacts.Status.OBSERVED,expected.status());
    try(var worker=new SourceParserClient(directory)) {
      for(int i=0;i<65;i++)assertEquals(expected,worker.parse(source));
      assertEquals(SourceFacts.Status.SOURCE_QUOTA,worker.parse("x".repeat(65537)).status());
    }
    assertArrayEquals(SourceParserClient.bundle(),SourceParserClient.bundle());
    try(var files=Files.list(directory)) {
      Path bundle=files.findFirst().orElseThrow();Files.write(bundle,new byte[]{1});
    }
    assertThrows(IOException.class,()->new SourceParserClient(directory));
  }
}
