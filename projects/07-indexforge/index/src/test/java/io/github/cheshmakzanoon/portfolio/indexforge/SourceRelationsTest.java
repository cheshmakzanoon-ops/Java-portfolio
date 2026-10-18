package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SourceRelationsTest {
  private static SourceApiCatalog catalog() throws Exception {
    var entries = new ArrayList<SourceApiCatalog.Entry>();
    for (String primitive :
        List.of("boolean", "byte", "char", "short", "int", "long", "float", "double"))
      entries.add(
          new SourceApiCatalog.Entry(
              "java.util.Arrays",
              "hashCode",
              List.of(primitive + "[]"),
              "int",
              Set.of("hash", "comput"),
              false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.io.DataInputStream", "readInt", List.of(), "int", Set.of("read"), false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.io.DataOutputStream",
            "writeInt",
            List.of("int"),
            "void",
            Set.of("write"),
            false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.io.DataOutputStream", "flush", List.of(), "void", Set.of("flush"), false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.nio.charset.CodingErrorAction",
            "REPORT",
            List.of(),
            "java.nio.charset.CodingErrorAction",
            Set.of("reject"),
            true));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.nio.charset.CodingErrorAction",
            "REPLACE",
            List.of(),
            "java.nio.charset.CodingErrorAction",
            Set.of("substitut"),
            true));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.lang.Integer", "MAX_VALUE", List.of(), "int", Set.of("maximum"), true));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.lang.Math", "abs", List.of("int"), "int", Set.of("absolut"), false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.nio.charset.CharsetDecoder",
            "onMalformedInput",
            List.of("java.nio.charset.CodingErrorAction"),
            "java.nio.charset.CharsetDecoder",
            Set.of("configur"),
            false));
    entries.add(
        new SourceApiCatalog.Entry(
            "java.nio.charset.CharsetDecoder",
            "decode",
            List.of("java.nio.ByteBuffer"),
            "java.nio.CharBuffer",
            Set.of("decod"),
            false));
    return new SourceApiCatalog(
        "a".repeat(64),
        "b".repeat(64),
        "synthetic-test-signatures",
        "test",
        Set.of(
            "java.util.Arrays",
            "java.io.DataInputStream",
            "java.io.DataOutputStream",
            "java.nio.charset.CodingErrorAction",
            "java.nio.charset.CharsetDecoder",
            "java.nio.ByteBuffer",
            "java.nio.CharBuffer",
            "java.lang.Integer",
            "java.lang.Math"),
        entries);
  }

  private static double[] features(
      String source, Set<String> query, SourceRelations.Options options) throws Exception {
    var unit = SourceJavaParser.parse(source);
    assertTrue(unit.declarationInventoryKnown());
    var graph =
        new SourceSymbolGraph(
            "tenant",
            "repo",
            List.of(new SourceSymbolGraph.Document("tenant/repo/S.java", unit)),
            catalog());
    return SourceRelations.extract(
        "tenant/repo/S.java", unit, graph, query, options, new SourceSymbolGraph.Work());
  }

  @Test
  void allPrimitiveArrayLexemesRequireActuallyConsumedParameterAndMaskIndependently()
      throws Exception {
    for (String primitive :
        List.of("boolean", "byte", "char", "short", "int", "long", "float", "double")) {
      String source =
          "final class S { int calculate("
              + primitive
              + "[] data) { var alias=data; return java.util.Arrays.hashCode(alias); } }";
      var query = SourceApiCatalog.terms(primitive + " hash");
      assertEquals(
          1, features(source, query, new SourceRelations.Options(true, true, true))[2], primitive);
      assertEquals(
          1, features(source, query, new SourceRelations.Options(true, false, true))[2], primitive);
      assertEquals(
          0, features(source, query, new SourceRelations.Options(false, true, true))[2], primitive);
      String state =
          "final class S { "
              + primitive
              + "[] state; int calculate("
              + primitive
              + "[] unused) { return java.util.Arrays.hashCode(state); } }";
      assertEquals(
          0, features(state, query, new SourceRelations.Options(true, true, true))[2], primitive);
    }
  }

  @Test
  void guardedEffectRequiresSameValueEarlierSameScopeAndSupportedControl() throws Exception {
    String prefix =
        "import java.io.*; final class S { void send(DataInputStream in, DataOutputStream out) { int number=in.readInt(); ";
    var options = new SourceRelations.Options(true, true, true);
    var query = Set.of("number", "write");
    assertEquals(
        1,
        features(
            prefix + "if(number>0) throw new IllegalArgumentException(); out.writeInt(number); } }",
            query,
            options)[4]);
    assertEquals(
        0,
        features(
            prefix + "out.writeInt(number); if(number>0) throw new IllegalArgumentException(); } }",
            query,
            options)[4]);
    assertEquals(
        0,
        features(
            prefix
                + "int other=5; if(other>0) throw new IllegalArgumentException(); out.writeInt(number); } }",
            query,
            options)[4]);
    assertEquals(
        0,
        features(
            prefix
                + "try { if(number>0) throw new IllegalArgumentException(); } catch(Exception ex) {} out.writeInt(number); } }",
            query,
            options)[4]);
  }

  @Test
  void commentStringNestedAndReassignedInputsCannotManufactureParameterValueEdge()
      throws Exception {
    var options = new SourceRelations.Options(true, true, true);
    var query = Set.of("byte", "hash");
    assertEquals(
        0,
        features(
            "final class S { byte[] data; int calculate(byte[] unused) { String comment=\"byte hash\"; return java.util.Arrays.hashCode(data); } }",
            query,
            options)[2]);
    assertEquals(
        0,
        features(
            "final class S { int calculate(byte[] data, byte[] other) { data=other; return java.util.Arrays.hashCode(data); } }",
            query,
            options)[2]);
    assertEquals(
        0,
        features(
            "final class S { byte[] state; int calculate(byte[] data) { return java.util.Arrays.hashCode(state); } int unrelated(byte[] data) { return data.length; } }",
            query,
            options)[2]);
  }

  @Test
  void nestedConditionalAndZeroIterationLoopGuardsCannotDominateOuterEffect() throws Exception {
    String prefix =
        "import java.io.*; final class S { void send(DataInputStream in,DataOutputStream out,boolean flag){int number=in.readInt(); ";
    var options = new SourceRelations.Options(true, true, true);
    var query = Set.of("number", "write");
    for (String guard :
        List.of(
            "if(flag) if(number<0) throw new IllegalArgumentException();",
            "while(flag) if(number<0) throw new IllegalArgumentException();",
            "if(flag){if(number<0) throw new IllegalArgumentException();}",
            "while(flag){if(number<0) throw new IllegalArgumentException();}")) {
      var values = features(prefix + guard + "out.writeInt(number);}}", query, options);
      assertEquals(0, values[4], guard);
      assertEquals(0, values[5], guard);
    }
    assertArrayEquals(
        features(
            prefix + "if(number<0) throw new IllegalArgumentException();out.writeInt(number);}}",
            query,
            options),
        features(
            prefix + "if(number<0) throw new IllegalArgumentException(); out.writeInt(number);}}",
            query,
            options));
    assertEquals(
        1,
        features(
            prefix + "if(number<0) throw new IllegalArgumentException();out.writeInt(number);}}",
            query,
            options)[4]);
  }

  @Test
  void receiverChainRequiresSameBoundObjectAndRespectsAliasesAndMemberBoundaries()
      throws Exception {
    String header = "import java.io.*; final class S { ";
    var options = new SourceRelations.Options(true, true, true);
    var query = Set.of("write", "flush");
    assertEquals(
        1,
        features(
            header + "void send(DataOutputStream out,int n){out.writeInt(n);out.flush();}}",
            query,
            options)[3]);
    assertEquals(
        1,
        features(
            header
                + "void send(DataOutputStream out,int n){var alias=out;alias.writeInt(n);out.flush();}}",
            query,
            options)[3]);
    assertEquals(
        0,
        features(
            header
                + "void send(DataOutputStream first,DataOutputStream other,int n){first.writeInt(n);other.flush();}}",
            query,
            options)[3]);
    assertEquals(
        0,
        features(
            header + "DataOutputStream out; void send(int n){out.writeInt(n);out.flush();}}",
            query,
            options)[3]);
    assertEquals(
        0,
        features(
            header
                + "void send(DataOutputStream out,int n){out.writeInt(n);} void different(DataOutputStream out){out.flush();}}",
            query,
            options)[3]);
  }

  @Test
  void oneDirectPrivateHelperPreservesParameterEdgeButRecursionAndSecondHopDoNot()
      throws Exception {
    var options = new SourceRelations.Options(true, true, true);
    var query = Set.of("byte", "hash");
    String consumer =
        "int calculate(byte[] data){return java.util.Arrays.hashCode(identity(data));}";
    assertEquals(
        1,
        features(
            "final class S {private static byte[] identity(byte[] data){return data;}"
                + consumer
                + "}",
            query,
            options)[2]);
    assertEquals(
        0,
        features(
            "final class S {private static byte[] identity(byte[] data){return identity(data);}"
                + consumer
                + "}",
            query,
            options)[2]);
    assertEquals(
        0,
        features(
            "final class S {private static byte[] identity(byte[] data){return other(data);} private static byte[] other(byte[] data){return data;}"
                + consumer
                + "}",
            query,
            options)[2]);
    assertEquals(
        0,
        features(
            "final class S {private static byte[] identity(byte[] data){if(data!=null)return data;}"
                + consumer
                + "}",
            query,
            options)[2]);
  }

  @Test
  void catalogFieldsUseActualLexemesAndProseWithIndependentMasksAndConnectedEndpoints()
      throws Exception {
    String header =
        "import java.nio.*; import java.nio.charset.*; final class S { void run(CharsetDecoder decoder, ByteBuffer input){";
    String report =
        header + "decoder.onMalformedInput(CodingErrorAction.REPORT);decoder.decode(input);}}";
    String replace =
        header + "decoder.onMalformedInput(CodingErrorAction.REPLACE);decoder.decode(input);}}";
    var all = new SourceRelations.Options(true, true, true);
    var prose = SourceApiCatalog.terms("reject decode");
    assertEquals(1, features(report, prose, all)[3]);
    assertEquals(0, features(replace, prose, all)[3]);
    assertEquals(1, features(report, prose, new SourceRelations.Options(false, true, true))[3]);
    assertEquals(0, features(report, prose, new SourceRelations.Options(true, false, true))[3]);
    assertEquals(
        1,
        features(
            report,
            SourceApiCatalog.terms("REPORT decode"),
            new SourceRelations.Options(true, false, true))[3]);
    assertEquals(
        0,
        features(
            report,
            SourceApiCatalog.terms("REPORT decode"),
            new SourceRelations.Options(false, true, true))[3]);
    String other = "final class S {int run(){return Math.abs(Integer.MAX_VALUE);}}";
    assertEquals(1, features(other, SourceApiCatalog.terms("MAX_VALUE"), all)[0]);
    assertEquals(1, features(other, SourceApiCatalog.terms("maximum"), all)[1]);
    assertEquals(
        0,
        features(
            other,
            SourceApiCatalog.terms("maximum"),
            new SourceRelations.Options(true, false, true))[1]);
  }

  @Test
  void catalogFieldReferencesOutsideAnEdgeCannotInventReceiverOrGuardRelations() throws Exception {
    var all = new SourceRelations.Options(true, true, true);
    var query = SourceApiCatalog.terms("reject decode");
    String header =
        "import java.nio.*; import java.nio.charset.*; final class S { void run(CharsetDecoder first, CharsetDecoder second, ByteBuffer input){";
    assertEquals(
        0,
        features(
            header + "first.onMalformedInput(CodingErrorAction.REPORT);second.decode(input);}}",
            query,
            all)[3]);
    String unused =
        header
            + "var unused=CodingErrorAction.REPORT;first.onMalformedInput(CodingErrorAction.REPLACE);first.decode(input);}}";
    var vector = features(unused, query, all);
    assertEquals(
        1, vector[1], "R1 may observe an actually referenced field elsewhere in this member");
    for (int i = 2; i < 6; i++)
      assertEquals(0, vector[i], "unused field cannot fabricate relation dimension " + i);
    String shadow =
        "import java.nio.*; import java.nio.charset.*; final class CodingErrorAction {static CodingErrorAction REPORT;} final class S {void run(CharsetDecoder first, ByteBuffer input){first.onMalformedInput(CodingErrorAction.REPORT);first.decode(input);}}";
    assertEquals(0, features(shadow, query, all)[3]);
    String local =
        header
            + "Object CodingErrorAction=new Object();first.onMalformedInput(CodingErrorAction.REPORT);first.decode(input);}}";
    assertEquals(0, features(local, query, all)[3]);
    String strings =
        header
            + "String unrelated=\"CodingErrorAction.REPORT reject\";first.onMalformedInput(CodingErrorAction.REPLACE);first.decode(input);}}";
    assertEquals(0, features(strings, query, all)[3]);
  }
}
