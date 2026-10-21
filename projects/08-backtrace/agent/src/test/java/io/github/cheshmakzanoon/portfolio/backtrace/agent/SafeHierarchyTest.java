package io.github.cheshmakzanoon.portfolio.backtrace.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class SafeHierarchyTest {
  @Test
  void metadataIdentityAndByteBudgetsPreventUnsafeFrameResolution() {
    byte[] wrong = bytes("sample/Wrong", "java/lang/Object", new String[0], 0);
    ClassLoader wrongIdentity =
        new ClassLoader() {
          public InputStream getResourceAsStream(String name) {
            return new ByteArrayInputStream(wrong);
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new SafeHierarchy(wrongIdentity, target()).common("sample/Expected", "sample/Other"));
    java.util.concurrent.atomic.AtomicInteger read =
        new java.util.concurrent.atomic.AtomicInteger();
    ClassLoader oversized =
        new ClassLoader() {
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
              public int read() {
                read.incrementAndGet();
                return 0;
              }
            };
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new SafeHierarchy(oversized, target()).common("sample/A", "sample/B"));
    assertEquals(2 * 1024 * 1024 + 1, read.get());
    var policy = new InstrumentationPolicy(List.of("sample.application"));
    assertNull(
        new TimingTransformer(
                policy, new io.github.cheshmakzanoon.portfolio.backtrace.probe.MethodRegistry(1))
            .transform(
                null,
                ClassLoader.getSystemClassLoader(),
                "sample/application/Large",
                null,
                null,
                new byte[4 * 1024 * 1024 + 1]));
    assertEquals(1, policy.reasons().get("oversizedClass"));
  }

  private static ClassReader target() {
    return new ClassReader(bytes("sample/Target", "java/lang/Object", new String[0], 0));
  }

  private static byte[] bytes(String name, String parent, String[] interfaces, int access) {
    ClassWriter w = new ClassWriter(0);
    w.visit(Opcodes.V25, access | Opcodes.ACC_PUBLIC, name, null, parent, interfaces);
    w.visitEnd();
    return w.toByteArray();
  }

  @Test
  void resolvesClassAndInterfaceMergesFromResourcesWithoutClassLoading() {
    var h = new SafeHierarchy(ClassLoader.getSystemClassLoader(), target());
    assertEquals("java/lang/String", h.common("java/lang/String", "java/lang/String"));
    assertEquals("java/lang/Object", h.common("java/lang/String", "java/lang/Object"));
    assertEquals("java/lang/Object", h.common("java/lang/Object", "java/lang/String"));
    assertEquals("java/lang/Object", h.common("java/lang/String", "java/lang/Integer"));
    assertEquals("java/lang/Object", h.common("java/util/List", "java/util/Set"));
    assertThrows(
        IllegalArgumentException.class, () -> h.common("[Ljava/lang/String;", "java/lang/Object"));
    assertThrows(
        IllegalArgumentException.class, () -> h.common("missing/Metadata", "java/lang/Object"));
  }

  @Test
  void badMetadataResourcesAreRejectedRatherThanLoadingTargetTypes() {
    ClassLoader loader =
        new ClassLoader() {
          @Override
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("injected metadata read failure");
              }
            };
          }

          @Override
          public Class<?> loadClass(String name) throws ClassNotFoundException {
            throw new AssertionError("must never load a target class");
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new SafeHierarchy(loader, target()).common("bad/A", "bad/B"));
  }

  @Test
  void hierarchyMetadataBudgetIsExplicit() {
    Map<String, byte[]> resources = new HashMap<>();
    for (int i = 0; i < 300; i++)
      resources.put(
          "sample/T" + i + ".class",
          bytes(
              "sample/T" + i,
              i == 299 ? "java/lang/Object" : "sample/T" + (i + 1),
              new String[0],
              0));
    ClassLoader loader =
        new ClassLoader() {
          @Override
          public InputStream getResourceAsStream(String name) {
            byte[] b = resources.get(name);
            return b == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(b);
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new SafeHierarchy(loader, target()).common("sample/T0", "java/lang/String"));
  }

  @Test
  void packageLoaderModuleAndExactMethodScopeAreAuditable() {
    var p = new InstrumentationPolicy(List.of("sample.application"), List.of("selected"));
    assertTrue(
        p.selectClass(null, ClassLoader.getSystemClassLoader(), "sample/application/Target"));
    assertFalse(p.selectClass(null, null, "sample/application/Target"));
    assertFalse(
        p.selectClass(
            String.class.getModule(),
            ClassLoader.getSystemClassLoader(),
            "sample/application/Target"));
    for (String name :
        new String[] {
          "java/lang/String",
          "javax/sql/DataSource",
          "jdk/Thing",
          "sun/Thing",
          "org/objectweb/asm/Type",
          "io/github/cheshmakzanoon/portfolio/backtrace/agent/Thing"
        }) assertFalse(p.selectClass(null, ClassLoader.getSystemClassLoader(), name));
    assertFalse(p.selectClass(null, ClassLoader.getSystemClassLoader(), null));
    assertFalse(p.selectClass(null, ClassLoader.getSystemClassLoader(), "sample/other/Target"));
    assertFalse(p.selectMethod(0, "<init>"));
    assertFalse(p.selectMethod(0, "<clinit>"));
    assertFalse(p.selectMethod(Opcodes.ACC_NATIVE, "selected"));
    assertFalse(p.selectMethod(Opcodes.ACC_ABSTRACT, "selected"));
    assertFalse(p.selectMethod(0, "other"));
    assertTrue(p.selectMethod(0, "selected"));
    assertTrue(p.reasons().get("namedModule") > 0);
  }

  @Test
  void cyclicMetadataIsRejectedWithBoundedWork() {
    Map<String, byte[]> map =
        Map.of(
            "sample/A.class",
            bytes("sample/A", "sample/B", new String[0], 0),
            "sample/B.class",
            bytes("sample/B", "sample/A", new String[0], 0),
            "sample/C.class",
            bytes("sample/C", "java/lang/Object", new String[0], 0));
    ClassLoader loader =
        new ClassLoader() {
          @Override
          public InputStream getResourceAsStream(String name) {
            byte[] value = map.get(name);
            return value == null
                ? super.getResourceAsStream(name)
                : new ByteArrayInputStream(value);
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new SafeHierarchy(loader, target()).common("sample/A", "sample/C"));
  }

  @Test
  void unchangedScopeAndOversizedMethodsHaveExplicitSkipReasons() {
    var policy = new InstrumentationPolicy(List.of("sample.application"));
    var transformer =
        new TimingTransformer(
            policy, new io.github.cheshmakzanoon.portfolio.backtrace.probe.MethodRegistry(1));
    byte[] api =
        bytes("sample/application/Api", "java/lang/Object", new String[0], Opcodes.ACC_INTERFACE);
    assertNull(
        transformer.transform(
            null, ClassLoader.getSystemClassLoader(), "sample/application/Api", null, null, api));
    assertNull(
        transformer.transform(
            null,
            ClassLoader.getSystemClassLoader(),
            "sample/application/Target",
            String.class,
            null,
            api));
    ClassWriter w = new ClassWriter(0);
    w.visit(
        Opcodes.V25,
        Opcodes.ACC_PUBLIC,
        "sample/application/Giant",
        null,
        "java/lang/Object",
        null);
    var m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "giant", "()V", null, null);
    m.visitCode();
    for (int i = 0; i < 65530; i++) m.visitInsn(Opcodes.NOP);
    m.visitInsn(Opcodes.RETURN);
    m.visitMaxs(0, 0);
    m.visitEnd();
    w.visitEnd();
    assertNull(
        transformer.transform(
            null,
            ClassLoader.getSystemClassLoader(),
            "sample/application/Giant",
            null,
            null,
            w.toByteArray()));
    assertTrue(policy.reasons().get("oversizedMethod") > 0);
    assertTrue(policy.reasons().get("nonOrdinaryClass") > 0);
    var excluded = new InstrumentationPolicy(List.of("sample.application"), List.of("different"));
    assertNull(
        new TimingTransformer(
                excluded, new io.github.cheshmakzanoon.portfolio.backtrace.probe.MethodRegistry(1))
            .transform(
                null,
                ClassLoader.getSystemClassLoader(),
                "sample/application/Giant",
                null,
                null,
                w.toByteArray()));
  }

  @Test
  void methodCeilingLeavesTheClassLoadableAndCountsUnselectedMethods() {
    ClassWriter w = new ClassWriter(0);
    w.visit(
        Opcodes.V25,
        Opcodes.ACC_PUBLIC,
        "sample/application/Limited",
        null,
        "java/lang/Object",
        null);
    for (String name : List.of("first", "second")) {
      var method = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()I", null, null);
      method.visitCode();
      method.visitInsn(Opcodes.ICONST_1);
      method.visitInsn(Opcodes.IRETURN);
      method.visitMaxs(1, 0);
      method.visitEnd();
    }
    w.visitEnd();
    var policy = new InstrumentationPolicy(List.of("sample.application"));
    var registry = new io.github.cheshmakzanoon.portfolio.backtrace.probe.MethodRegistry(1);
    byte[] transformed =
        new TimingTransformer(policy, registry)
            .transform(
                null,
                ClassLoader.getSystemClassLoader(),
                "sample/application/Limited",
                null,
                null,
                w.toByteArray());
    assertNotNull(transformed);
    assertEquals(1, registry.snapshot().size());
    assertEquals(1, registry.overflow());
    assertEquals(1, policy.reasons().get("methodCeiling"));
  }
}
