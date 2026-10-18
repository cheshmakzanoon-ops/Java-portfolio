package io.github.cheshmakzanoon.portfolio.byteguard.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.report.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

class BoundaryTest implements Opcodes {
  @TempDir Path temp;

  Path compile(String name, String source) throws Exception {
    Path file = temp.resolve(name + ".java");
    Files.writeString(file, source);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-proc:none", "-g", "-d", temp.toString(), file.toString()));
    return temp.resolve(name + ".class");
  }

  Analyzer.Result scan(Path input, AnalysisBudget budget) throws Exception {
    return new Analyzer().scan(List.of(input), List.of(), 25, InputLimits.defaults(), budget);
  }

  @Test
  void helperArrayAndCollectionWritesReachCaller() throws Exception {
    Path clazz =
        compile(
            "Effects",
            "public class Effects { static void fill(String[] out,String s){out[0]=s;} static void list(java.util.List<String> out,String s){out.add(s);} public static void run()throws Exception{String s=System.getenv(\"X\");String[] out=new String[1];fill(out,s);java.sql.Statement statement=null;statement.execute(out[0]);java.util.List<String> values=new java.util.ArrayList<>();list(values,s);statement.execute(values.get(0));} }");
    var analysis = scan(clazz, AnalysisBudget.defaults());
    var f = new FindingReporter().findings(analysis);
    assertFalse(analysis.analysis().partial());
    assertEquals(2, f.size(), f.toString());
  }

  @Test
  void fieldAndReturnedContainerAliasesRetainUnknownMutation() throws Exception {
    Path file =
        compile(
            "Aliases",
            """
      public class Aliases {
        static String[] holder=new String[1];
        static java.util.List<String> list=new java.util.ArrayList<>();
        static native void fill(String[] out,String text);
        static native void mutate(java.util.List<String> out,String text);
        static String[] pass(String[] out){return out;}
        static String[] make(){return new String[1];}
        static void store(String[] out){holder=out;}
        public static void field(java.sql.Statement stmt)throws Exception {
          fill(holder,System.getenv("FIELD"));stmt.execute(holder[0]);
        }
        public static void alias(java.sql.Statement stmt)throws Exception {
          String[] out=new String[1];store(out);fill(holder,System.getenv("ALIAS"));stmt.execute(out[0]);
        }
        public static void identity(java.sql.Statement stmt)throws Exception {
          String[] out=new String[1];fill(pass(out),System.getenv("IDENTITY"));stmt.execute(out[0]);
        }
        public static void allocation(java.sql.Statement stmt)throws Exception {
          String[] out=make();fill(out,System.getenv("FACTORY"));stmt.execute(out[0]);
        }
        public static void collection(java.sql.Statement stmt)throws Exception {
          mutate(list,System.getenv("LIST"));stmt.execute(list.get(0));
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertFalse(result.analysis().partial(), result.analysis().diagnostics().toString());
    var findings = new FindingReporter().findings(result);
    for (String method : List.of("field(", "alias(", "identity(", "allocation(", "collection(")) {
      assertTrue(
          findings.stream().anyMatch(f -> f.sink().method().contains(method)),
          method + ": " + findings);
    }
    assertTrue(findings.stream().allMatch(f -> f.confidence().equals("low")));
  }

  @Test
  void scalarFieldSetterInstantiatesItsSymbolicWrite() throws Exception {
    Path file =
        compile(
            "ScalarField",
            """
      public class ScalarField {
        static String holder;
        static void put(String text){holder=text;}
        public static void run(java.sql.Statement stmt)throws Exception{
          put(System.getenv("FIELD"));stmt.execute(holder);
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    assertEquals(1, new FindingReporter().findings(result).size());
  }

  @Test
  void safeReturnedAndFieldContainersRemainClean() throws Exception {
    Path file =
        compile(
            "CleanAliases",
            """
      public class CleanAliases {
        static String[] holder;
        static String[] pass(String[] out){return out;}
        static String[] make(){return new String[]{"constant"};}
        static void store(String[] out){holder=out;}
        public static void run(java.sql.Statement stmt)throws Exception{
          String[] out=make();store(pass(out));stmt.execute(holder[0]);stmt.execute(out[0]);
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    assertTrue(new FindingReporter().findings(result).isEmpty());
  }

  @Test
  void unknownObjectMutationReachesScalarFieldsAndPreservesUnknown() throws Exception {
    Path file =
        compile(
            "ObjectMutation",
            """
      public class ObjectMutation {
        String text="constant";
        static native void mutate(ObjectMutation box,String text);
        public static void run(java.sql.Statement stmt)throws Exception {
          ObjectMutation box=new ObjectMutation();mutate(box,System.getenv("FIELD"));stmt.execute(box.text);
        }
        public static String uncertain(){
          ObjectMutation box=new ObjectMutation();mutate(box,"constant");return box.text;
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertFalse(result.analysis().partial());
    var findings = new FindingReporter().findings(result);
    assertEquals(1, findings.size());
    assertEquals("low", findings.getFirst().confidence());
    var uncertain =
        result.analysis().summaries().get("ObjectMutation.uncertain()Ljava/lang/String;");
    assertNotNull(uncertain);
    assertTrue(uncertain.returns().unknown());
    assertNotEquals(
        io.github.cheshmakzanoon.portfolio.byteguard.dataflow.Taint.BOTTOM, uncertain.returns());
    Path clean =
        compile(
            "KnownField",
            """
      public class KnownField {
        String text="constant";
        static void set(KnownField box){box.text="known";}
        public static void run(java.sql.Statement stmt)throws Exception{
          KnownField box=new KnownField();set(box);stmt.execute(box.text);
        }
      }
      """);
    var matched = scan(clean, AnalysisBudget.defaults());
    assertFalse(matched.analysis().partial());
    assertTrue(new FindingReporter().findings(matched).isEmpty());
  }

  @Test
  void unsupportedVoidIndyIsPartialWithoutInvalidFrame() throws Exception {
    ClassWriter c = new ClassWriter(0);
    c.visit(V25, ACC_PUBLIC, "Unsupported", null, "java/lang/Object", null);
    MethodVisitor m = c.visitMethod(ACC_STATIC, "run", "()V", null, null);
    m.visitCode();
    m.visitInvokeDynamicInsn(
        "unknown",
        "()V",
        new Handle(
            H_INVOKESTATIC, "unknown/Bootstrap", "link", "()Ljava/lang/invoke/CallSite;", false));
    m.visitInsn(RETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();
    c.visitEnd();
    Path file = temp.resolve("Unsupported.class");
    Files.write(file, c.toByteArray());
    var result = scan(file, AnalysisBudget.defaults());
    assertTrue(result.analysis().partial());
    assertTrue(
        result.analysis().diagnostics().stream()
            .anyMatch(s -> s.startsWith("UNSUPPORTED_BOOTSTRAP")));
    assertFalse(result.analysis().unfinished().isEmpty());
  }

  @Test
  void dispatchAndGlobalLimitsAreDistinct() throws Exception {
    StringBuilder source =
        new StringBuilder("public class Dispatch { interface I {String get(String s);} ");
    for (int i = 0; i < 33; i++)
      source
          .append("static class Impl")
          .append(i)
          .append(" implements I {public String get(String s){return s;}} ");
    source.append(
        "public static void run(I i)throws Exception{java.sql.Statement s=null;s.execute(i.get(System.getenv(\"X\")));} }");
    compile("Dispatch", source.toString());
    var limited = scan(temp, new AnalysisBudget(100_000, 120_000, 32, 5));
    assertTrue(limited.analysis().partial());
    assertTrue(
        limited.analysis().diagnostics().stream().anyMatch(s -> s.startsWith("DISPATCH_LIMIT")));
    assertFalse(
        limited.analysis().diagnostics().stream().anyMatch(s -> s.startsWith("RESOURCE_LIMIT")));
    var expanded = scan(temp, new AnalysisBudget(100_000, 120_000, 40, 5));
    assertFalse(expanded.analysis().partial());
    assertFalse(new FindingReporter().findings(expanded).isEmpty());
    var tiny = scan(temp, new AnalysisBudget(1, 120_000, 40, 5));
    assertTrue(tiny.analysis().partial());
    assertTrue(
        tiny.analysis().diagnostics().stream().anyMatch(s -> s.startsWith("RESOURCE_LIMIT")));
  }

  @Test
  void fingerprintIgnoresUnrelatedMethodAndDebugMissing() throws Exception {
    String prefix =
        "public class Stable {public static void run()throws Exception{java.sql.Statement s=null;s.execute(System.getenv(\"X\"));}";
    Path file = compile("Stable", prefix + "}");
    String before =
        new FindingReporter()
            .findings(scan(file, AnalysisBudget.defaults()))
            .getFirst()
            .fingerprint();
    file = compile("Stable", prefix + "static int unrelated(){return 42;} }");
    String after =
        new FindingReporter()
            .findings(scan(file, AnalysisBudget.defaults()))
            .getFirst()
            .fingerprint();
    assertEquals(before, after);
  }

  @Test
  void falseSanitizerAndNativeUncertaintyPropagate() throws Exception {
    Path file =
        compile(
            "Unknown",
            "public class Unknown { static native String nativeIdentity(String s);static String sanitize(String s){return s.trim();} public static void run()throws Exception{java.sql.Statement stmt=null;stmt.execute(nativeIdentity(sanitize(System.getenv(\"X\"))));} }");
    var a = scan(file, AnalysisBudget.defaults());
    var findings = new FindingReporter().findings(a);
    assertEquals(1, findings.size());
    assertTrue(a.analysis().diagnostics().stream().anyMatch(s -> s.contains("nativeIdentity")));
    assertEquals("low", findings.getFirst().confidence());
  }

  @Test
  void inheritedDefaultInterfaceIsResolved() throws Exception {
    compile(
        "Defaults",
        "public class Defaults { interface I {default String pass(String s){return s;}}static class C implements I{}public static void run(java.sql.Statement statement)throws Exception{I i=new C();statement.execute(i.pass(System.getenv(\"X\")));}}");
    var a = scan(temp, AnalysisBudget.defaults());
    assertEquals(1, new FindingReporter().findings(a).size());
    assertFalse(a.analysis().diagnostics().stream().anyMatch(d -> d.contains("I.pass")));
    assertTrue(
        a.analysis().contexts().keySet().stream().anyMatch(c -> c.callee().contains("I.pass")));
  }

  @Test
  void documentedShellArrayFalsePositiveIsObservable() throws Exception {
    Path file =
        compile(
            "ShellPositional",
            "public class ShellPositional {public static void run()throws Exception{Runtime.getRuntime().exec(new String[]{\"/bin/sh\",\"-c\",\"echo fixed\",System.getenv(\"X\")});}}");
    var a = scan(file, AnalysisBudget.defaults());
    assertEquals(1, new FindingReporter().findings(a).size());
    assertEquals("BG-SHELL-COMMAND", new FindingReporter().findings(a).getFirst().rule());
  }

  @Test
  void oneCallSiteContextSeparatesSafeAndTaintedInvocations() throws Exception {
    Path file =
        compile(
            "Contexts",
            "public class Contexts {static void sink(java.sql.Statement stmt,String s)throws Exception{stmt.execute(s);}public static void run(java.sql.Statement stmt)throws Exception{sink(stmt,\"constant\");sink(stmt,System.getenv(\"X\"));}}");
    var a = scan(file, AnalysisBudget.defaults());
    assertEquals(1, new FindingReporter().findings(a).size());
    var contexts =
        a.analysis().contexts().entrySet().stream()
            .filter(e -> e.getKey().callee().contains("Contexts.sink"))
            .toList();
    assertEquals(2, contexts.size());
    assertEquals(
        1,
        contexts.stream()
            .filter(
                e -> e.getValue().flows().values().stream().anyMatch(f -> f.taint().hasConcrete(1)))
            .count());
  }

  @Test
  void benchmarkClassShapeHasRealSupportedFlows() throws Exception {
    Path file = temp.resolve("Case.class");
    Files.write(file, BenchmarkCorpus.generate("benchmark/Case00000", 0));
    var a = scan(file, AnalysisBudget.defaults());
    assertFalse(a.analysis().partial());
    assertEquals(4, a.classes().get("benchmark/Case00000").methods().size());
    assertEquals(1, new FindingReporter().findings(a).size());
  }

  @Test
  void noncapturingVoidLambdaPropagatesSinkArguments() throws Exception {
    Path file =
        compile(
            "LambdaSink",
            "public class LambdaSink {static void sink(String value){try{java.nio.file.Files.readString(java.nio.file.Path.of(value));}catch(Exception e){throw new RuntimeException(e);}}public static void run(){java.util.function.Consumer<String> consumer=LambdaSink::sink;consumer.accept(System.getenv(\"X\"));}}");
    var a = scan(file, AnalysisBudget.defaults());
    assertFalse(a.analysis().partial());
    assertEquals(1, new FindingReporter().findings(a).size());
    var reverse = new SummaryWorklist().analyze(a.classes(), AnalysisBudget.defaults(), true);
    assertFalse(reverse.partial());
    assertEquals(a.analysis().summaries(), reverse.summaries());
  }

  @Test
  void unresolvedLambdaImplementationIsExplicitlyPartial() throws Exception {
    Path file =
        compile(
            "MissingLambda",
            """
      public class MissingLambda {
        public static void run(java.sql.Statement stmt)throws Exception{
          java.util.function.Function<String,String> function=String::trim;
          stmt.execute(function.apply(System.getenv("X")));
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertTrue(result.analysis().partial());
    assertTrue(
        result.analysis().diagnostics().stream()
            .anyMatch(d -> d.startsWith("UNSUPPORTED_LAMBDA_TARGET")));
    assertEquals(1, new FindingReporter().findings(result).size());
  }

  @Test
  void nativeStaticLambdaImplementationCannotBecomeModeledEmpty() throws Exception {
    Path file =
        compile(
            "NativeLambda",
            """
      public class NativeLambda {
        static native String nativeIdentity(String input);
        public static void run(java.sql.Statement stmt)throws Exception {
          java.util.function.Function<String,String> function=NativeLambda::nativeIdentity;
          stmt.execute(function.apply(System.getenv("X")));
        }
      }
      """);
    var result = scan(file, AnalysisBudget.defaults());
    assertTrue(result.analysis().partial());
    assertTrue(
        result.analysis().diagnostics().stream()
            .anyMatch(d -> d.startsWith("UNSUPPORTED_LAMBDA_TARGET")));
    assertEquals(1, new FindingReporter().findings(result).size());
  }

  @Test
  void unsupportedLambdaHeapEffectsAreExplicitlyPartial() throws Exception {
    Path file =
        compile(
            "LambdaEffects",
            "public class LambdaEffects {public static void run(){java.util.function.Consumer<String[]> consumer=a->a[0]=System.getenv(\"X\");String[] out=new String[1];consumer.accept(out);}}");
    var a = scan(file, AnalysisBudget.defaults());
    assertTrue(a.analysis().partial());
    assertTrue(
        a.analysis().diagnostics().stream()
            .anyMatch(d -> d.startsWith("UNSUPPORTED_LAMBDA_HEAP_EFFECT")));
    assertFalse(a.analysis().unfinished().isEmpty());
  }

  @Test
  void unknownVoidMutatorsHavocElementsAndPropagateArgumentTaint() throws Exception {
    Path file =
        compile(
            "NativeEffects",
            "public class NativeEffects {static native void fill(String[] out,String source);static native void unknown(String[] out);public static void run(java.sql.Statement stmt)throws Exception{String[] out=new String[1];fill(out,System.getenv(\"X\"));stmt.execute(out[0]);}public static String unresolvedElements(){String[] out=new String[1];unknown(out);return out[0];}}");
    var a = scan(file, AnalysisBudget.defaults());
    assertFalse(a.analysis().partial());
    assertEquals(1, new FindingReporter().findings(a).size());
    assertTrue(
        a.analysis()
            .summaries()
            .get("NativeEffects.unresolvedElements()Ljava/lang/String;")
            .returns()
            .unknown());
    assertTrue(a.analysis().diagnostics().stream().anyMatch(d -> d.contains("NativeEffects.fill")));
  }

  @Test
  void untrustedSourceMetadataCannotEmitAbsolutePaths() throws Exception {
    ClassWriter c = new ClassWriter(0);
    c.visit(V25, ACC_PUBLIC, "Privacy", null, "java/lang/Object", null);
    c.visitSource("/workstation/person/private/Secret.java", null);
    MethodVisitor method = c.visitMethod(ACC_STATIC, "run", "(Ljava/sql/Statement;)V", null, null);
    method.visitCode();
    method.visitVarInsn(ALOAD, 0);
    method.visitLdcInsn("X");
    method.visitMethodInsn(
        INVOKESTATIC,
        "java/lang/System",
        "getenv",
        "(Ljava/lang/String;)Ljava/lang/String;",
        false);
    method.visitMethodInsn(
        INVOKEINTERFACE, "java/sql/Statement", "execute", "(Ljava/lang/String;)Z", true);
    method.visitInsn(POP);
    method.visitInsn(RETURN);
    method.visitMaxs(2, 1);
    method.visitEnd();
    c.visitEnd();
    Path file = temp.resolve("Privacy.class");
    Files.write(file, c.toByteArray());
    var result = scan(file, AnalysisBudget.defaults());
    Finding finding = new FindingReporter().findings(result).getFirst();
    assertEquals("Secret.java", finding.sourceLocation().uri());
    assertEquals("Secret.java", finding.sinkLocation().uri());
  }

  @Test
  void unsupportedConstantDynamicIsUnknownAndPartial() throws Exception {
    ClassWriter c = new ClassWriter(0);
    c.visit(V25, ACC_PUBLIC, "DynamicConstant", null, "java/lang/Object", null);
    MethodVisitor m = c.visitMethod(ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
    m.visitCode();
    m.visitLdcInsn(
        new ConstantDynamic(
            "external",
            "Ljava/lang/String;",
            new Handle(
                H_INVOKESTATIC,
                "unknown/Bootstrap",
                "constant",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                false)));
    m.visitInsn(ARETURN);
    m.visitMaxs(1, 0);
    m.visitEnd();
    c.visitEnd();
    Path file = temp.resolve("DynamicConstant.class");
    Files.write(file, c.toByteArray());
    var a = scan(file, AnalysisBudget.defaults());
    assertTrue(a.analysis().partial());
    assertTrue(a.analysis().diagnostics().stream().anyMatch(d -> d.contains("constant-dynamic")));
    assertTrue(
        a.analysis()
            .summaries()
            .get("DynamicConstant.value()Ljava/lang/String;")
            .returns()
            .unknown());
  }
}
