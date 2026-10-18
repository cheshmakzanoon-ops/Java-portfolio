package io.github.cheshmakzanoon.portfolio.threadlens.compatibility;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.threadlens.agent.*;
import io.github.cheshmakzanoon.portfolio.threadlens.probe.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TransformationTest {
  @TempDir static Path directory;
  static Class<?> original, transformed;
  static byte[] transformedBytes;
  static InstrumentationPolicy policy;
  static MethodRegistry registry;

  @BeforeAll
  static void compileAndTransform() throws Exception {
    Path source = directory.resolve("sample/application/Fixture.java");
    Files.createDirectories(source.getParent());
    StringBuilder code =
        new StringBuilder(
            "package sample.application; public class Fixture { public static final RuntimeException EX=new RuntimeException(\"fixture secret must not export\"); public static int finallyCalls; ");
    for (int i = 0; i < 40; i++) {
      String method =
          switch (i % 10) {
            case 0 -> "public static int m" + i + "(int n){return n+" + i + ";}";
            case 1 -> "public static long m" + i + "(int n){return 9223372036854770000L+n;}";
            case 2 -> "public static double m" + i + "(int n){return Math.PI*n;}";
            case 3 -> "public static synchronized int m" + i + "(int n){return n*2;}";
            case 4 ->
                "public static int m"
                    + i
                    + "(int n){try{try{if(n==3)throw EX;return n;}catch(IllegalArgumentException x){return -9;}}finally{finallyCalls++;}}";
            case 5 ->
                "public static Object m"
                    + i
                    + "(int n){Object x;if(n>0)x=\"ok\";else x=Integer.valueOf(n);return x;}";
            case 6 ->
                "public static int m"
                    + i
                    + "(int n){try{throw EX;}catch(RuntimeException x){if(x!=EX)throw new AssertionError();return n;}}";
            case 7 -> "public static int m" + i + "(int n){throw EX;}";
            case 8 ->
                "public static int m"
                    + i
                    + "(int n){return switch(n){case 1->6;case 3->7;default->2;};}";
            default -> "public static int m" + i + "(int n){return n<=0?0:n+m" + i + "(n-1);}";
          };
      code.append(method);
    }
    code.append("public static float m40(int n){return .25f*n;} public static void m41(int n){finallyCalls+=n;} public static boolean m42(int n){return n>0;} public static char m43(int n){return (char)(n+65);} public static byte m44(int n){return (byte)(n+126);} public static short m45(int n){return (short)(n+32766);} public static int m46(int n){try(java.io.StringReader reader=new java.io.StringReader(\"fixture\")){return reader.read()+n;}catch(java.io.IOException error){throw EX;}} public static synchronized long m47(int n){try{throw EX;}finally{finallyCalls++;}}");
    code.append(
        "public static void main(String[] args)throws Exception {io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingBuffer b=new io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingBuffer(65536);io.github.cheshmakzanoon.portfolio.threadlens.probe.TimingProbe.configure(b);int cases=0;for(int i=0;i<48;i++){try{Object result=Fixture.class.getMethod(\"m\"+i,int.class).invoke(null,3);if((i<40&&(i%10==7||i%10==4))||i==47)throw new AssertionError(\"exception lost\");if(result==null&&i!=41)throw new AssertionError();}catch(java.lang.reflect.InvocationTargetException caught){if(caught.getCause()!=EX)throw new AssertionError(\"identity changed\");}cases++;}int timed=0;while(b.poll()!=null)timed++;if(timed<48)throw new AssertionError(\"timing missing\");System.out.println(\"verified cases=\"+cases+\" timed=\"+timed);}}");
    Files.writeString(source, code);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", directory.toString(), source.toString()));
    byte[] bytes = Files.readAllBytes(directory.resolve("sample/application/Fixture.class"));
    policy = new InstrumentationPolicy(List.of("sample.application"));
    transformedBytes =
        new TimingTransformer(policy, registry = new MethodRegistry(100))
            .transform(
                null,
                ClassLoader.getSystemClassLoader(),
                "sample/application/Fixture",
                null,
                null,
                bytes);
    assertNotNull(transformedBytes);
    original = new ByteLoader().define(bytes);
    transformed = new ByteLoader().define(transformedBytes);
    TimingProbe.configure(new TimingBuffer(65_536));
  }

  static class ByteLoader extends ClassLoader {
    Class<?> define(byte[] bytes) {
      return defineClass("sample.application.Fixture", bytes, 0, bytes.length);
    }
  }

  @TestFactory
  Stream<DynamicTest> fortyEightOutputAndIdentityCases() {
    return java.util.stream.IntStream.range(0, 48)
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "transform-case-" + i,
                    () -> {
                      TimingBuffer observed = new TimingBuffer(65536);
                      TimingProbe.configure(observed);
                      try {
                        Object expected = original.getMethod("m" + i, int.class).invoke(null, 3);
                        Object actual = transformed.getMethod("m" + i, int.class).invoke(null, 3);
                        assertEquals(expected, actual);
                      } catch (java.lang.reflect.InvocationTargetException expected) {
                        assertSame(original.getField("EX").get(null), expected.getCause());
                        var actual =
                            assertThrows(
                                java.lang.reflect.InvocationTargetException.class,
                                () -> transformed.getMethod("m" + i, int.class).invoke(null, 3));
                        assertSame(transformed.getField("EX").get(null), actual.getCause());
                      }
                      int methodId =
                          registry.snapshot().entrySet().stream()
                              .filter(e -> e.getValue().contains(".m" + i + "("))
                              .findFirst()
                              .orElseThrow()
                              .getKey();
                      boolean timed = false;
                      TimingBuffer.Timing event;
                      while ((event = observed.poll()) != null)
                        if (event.methodId() == methodId) {
                          timed = true;
                          assertEquals((i < 40 && (i % 10 == 7 || i % 10 == 4)) || i == 47, event.exceptional());
                        }
                      assertTrue(timed, "supported case was not instrumented: " + i);
                    }));
  }

  @Test
  void transformedFixturePassesJvmVerificationAndObserverFault() throws Exception {
    Files.write(directory.resolve("sample/application/Fixture.class"), transformedBytes);
    String classpath =
        directory + java.io.File.pathSeparator + System.getProperty("java.class.path");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all",
                "-cp",
                classpath,
                "sample.application.Fixture")
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("verified cases=48"), output);
    TimingProbe.configure(new TimingBuffer(2));
    TimingProbe.injectFault(true);
    assertEquals(3, transformed.getMethod("m0", int.class).invoke(null, 3));
    TimingProbe.injectFault(false);
    assertFalse(TimingProbe.enabled());
  }

  @Test
  void unsafeTransformationAndScopeAreSkipped() {
    var transformer = new TimingTransformer(policy, new MethodRegistry(1));
    assertNull(
        transformer.transform(
            null,
            ClassLoader.getSystemClassLoader(),
            "sample/application/Broken",
            null,
            null,
            new byte[] {1, 2, 3}));
    assertTrue(policy.reasons().get("unsafeTransformation") > 0);
    assertNull(
        transformer.transform(null, null, "sample/application/Boot", null, null, transformedBytes));
    assertFalse(policy.selectMethod(0, "<init>"));
    assertFalse(policy.selectMethod(0x100, "nativeMethod"));
  }
}
