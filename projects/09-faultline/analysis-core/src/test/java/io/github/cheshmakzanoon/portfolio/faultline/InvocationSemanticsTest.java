package io.github.cheshmakzanoon.portfolio.faultline;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.faultline.dataflow.*;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InvocationSemanticsTest {
  @TempDir Path directory;

  private Analyzer.Result scan(String name, String source) throws Exception {
    Path classes = Files.createDirectories(directory.resolve(name));
    Path file = classes.resolve(name + ".java");
    Files.writeString(file, source);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-proc:none", "-g", "-d", classes.toString(), file.toString()));
    return new Analyzer()
        .scan(List.of(classes), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
  }

  private List<MethodSummary.Flow> concreteSql(Analyzer.Result result, String method) {
    assertFalse(result.analysis().partial());
    return result.analysis().summaries().entrySet().stream()
        .filter(entry -> entry.getKey().contains("." + method + "("))
        .flatMap(entry -> entry.getValue().flows().values().stream())
        .filter(flow -> flow.rule().equals("BG-SQL-TEXT") && flow.taint().hasConcrete(1))
        .toList();
  }

  @Test
  void nativeVirtualAlternativeCannotDisappearBehindACleanModeledOverride() throws Exception {
    var result =
        scan(
            "NativeDispatch",
            """
        public class NativeDispatch {
          static class Base { native String pass(String value); }
          static class Clean extends Base { String pass(String value) { return "fixed"; } }
          public static void tainted(Base receiver, java.sql.Statement statement) throws Exception {
            statement.execute(receiver.pass(System.getenv("INPUT")));
          }
          public static void clean(Base receiver, java.sql.Statement statement) throws Exception {
            statement.execute(receiver.pass("fixed"));
          }
        }
        """);
    var tainted = concreteSql(result, "tainted");
    assertEquals(1, tainted.size());
    assertTrue(tainted.getFirst().taint().unknown());
    // Ground truth: this second call is clean. Shared receiver/formal aliases remain
    // object-insensitive, so this reused-helper control is a documented false
    // positive, not another labeled corpus positive.
    assertEquals(1, concreteSql(result, "clean").size());
    assertTrue(
        result.analysis().diagnostics().stream()
            .anyMatch(
                value ->
                    value.startsWith("UNKNOWN_CALL")
                        && value.contains("NativeDispatch$Base.pass")));
    assertScheduleIndependent(result);
    var isolated =
        scan(
            "NativeClean",
            """
        public class NativeClean {
          static class Base { native String pass(String value); }
          static class Clean extends Base { String pass(String value) { return "fixed"; } }
          public static void clean(Base receiver, java.sql.Statement statement) throws Exception {
            statement.execute(receiver.pass("fixed"));
          }
        }
        """);
    assertTrue(concreteSql(isolated, "clean").isEmpty());
  }

  @Test
  void opaqueMutableReturnsKeepLaterStoresAndPossibleAliases() throws Exception {
    var result =
        scan(
            "OpaqueArrays",
            """
        public class OpaqueArrays {
          static native String[] create();
          static native String[] maybeAlias(String[] input);
          static void fill(String[] original, String value) {
            String[] returned = maybeAlias(original);
            returned[0] = value;
          }
          public static void fresh(java.sql.Statement statement) throws Exception {
            String[] values = create();
            values[0] = System.getenv("FRESH");
            statement.execute(values[0]);
          }
          public static void alias(java.sql.Statement statement) throws Exception {
            String[] original = new String[1];
            String[] returned = maybeAlias(original);
            returned[0] = System.getenv("ALIAS");
            statement.execute(original[0]);
          }
          public static void clean(java.sql.Statement statement) throws Exception {
            String[] values = create();
            values[0] = "fixed";
            statement.execute(values[0]);
          }
          public static void helper(java.sql.Statement statement) throws Exception {
            String[] values = new String[1];
            fill(values, System.getenv("HELPER"));
            statement.execute(values[0]);
          }
        }
        """);
    assertEquals(1, concreteSql(result, "fresh").size());
    assertEquals(1, concreteSql(result, "alias").size());
    assertEquals(1, concreteSql(result, "helper").size());
    assertTrue(concreteSql(result, "clean").isEmpty());
    assertScheduleIndependent(result);
  }

  @Test
  void arrayLengthRetainsItsAllocationDependencyWithoutReadingElementTaint() throws Exception {
    var result =
        scan(
            "ArrayLengths",
            """
        public class ArrayLengths {
          public static void length(java.sql.Statement statement) throws Exception {
            byte[] values = new byte[Integer.parseInt(System.getenv("LENGTH"))];
            statement.execute(Integer.toString(values.length));
          }
          public static void elements(java.sql.Statement statement) throws Exception {
            String[] values = new String[1];
            values[0] = System.getenv("ELEMENT");
            statement.execute(Integer.toString(values.length));
          }
        }
        """);
    assertEquals(1, concreteSql(result, "length").size());
    assertTrue(concreteSql(result, "elements").isEmpty());
    assertScheduleIndependent(result);
  }

  private void assertScheduleIndependent(Analyzer.Result result) {
    var fifo = new SummaryWorklist().analyze(result.classes(), AnalysisBudget.defaults(), false);
    var lifo = new SummaryWorklist().analyze(result.classes(), AnalysisBudget.defaults(), true);
    assertFalse(fifo.partial());
    assertFalse(lifo.partial());
    assertEquals(fifo.summaries(), lifo.summaries());
    assertEquals(fifo.diagnostics(), lifo.diagnostics());
  }
}
