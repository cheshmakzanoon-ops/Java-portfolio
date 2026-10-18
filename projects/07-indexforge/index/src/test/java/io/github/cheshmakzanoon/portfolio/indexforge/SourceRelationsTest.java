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
    return new SourceApiCatalog(
        "a".repeat(64),
        "b".repeat(64),
        "synthetic-test-signatures",
        "test",
        Set.of("java.util.Arrays", "java.io.DataInputStream", "java.io.DataOutputStream"),
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
}
