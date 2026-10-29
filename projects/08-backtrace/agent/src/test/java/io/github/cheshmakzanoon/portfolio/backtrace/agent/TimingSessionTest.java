package io.github.cheshmakzanoon.portfolio.backtrace.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.backtrace.probe.TimingProbe;
import java.lang.instrument.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

class TimingSessionTest {
  @TempDir Path directory;

  @org.junit.jupiter.api.BeforeEach
  void disablePreviousFixture() {
    TimingProbe.disable();
    TimingProbe.injectFault(false);
  }

  @Test
  void failedHookRegistrationClosesStagedOutputBeforeAnyTransformationOrProbeEnablement()
      throws Exception {
    AtomicInteger transforms = new AtomicInteger();
    IllegalStateException primary = new IllegalStateException("hook unavailable");
    Instrumentation instrumentation =
        instrumentation(
            (method, args) -> {
              if (method.equals("addTransformer")) transforms.incrementAndGet();
              return false;
            });
    var hooks =
        new TimingSession.Hooks() {
          public void add(Thread hook) {
            throw primary;
          }

          public void remove(Thread hook) {
            fail("unregistered hook cannot be removed");
          }
        };
    assertSame(
        primary,
        assertThrows(
            IllegalStateException.class,
            () -> TimingSession.start(config(), instrumentation, hooks)));
    assertFalse(TimingProbe.enabled());
    assertEquals(0, transforms.get());
    assertTrue(
        Files.readString(directory.resolve("observer-status.txt"))
            .contains("statusComplete=false"));
    assertNoConsumer();
  }

  @Test
  void failedTransformerInstallationRollsBackEveryOwnerAndPreservesSuppressedCleanupFailures()
      throws Exception {
    AtomicBoolean removed = new AtomicBoolean(), hookRemoved = new AtomicBoolean();
    IllegalStateException primary = new IllegalStateException("transformer unavailable");
    Instrumentation instrumentation =
        instrumentation(
            (method, args) -> {
              if (method.equals("addTransformer")) {
                assertFalse(TimingProbe.enabled());
                throw primary;
              }
              if (method.equals("removeTransformer")) {
                removed.set(true);
                throw new IllegalArgumentException("transformer removal failed");
              }
              return false;
            });
    var hooks =
        new TimingSession.Hooks() {
          public void add(Thread hook) {}

          public void remove(Thread hook) {
            hookRemoved.set(true);
            throw new IllegalArgumentException("hook removal failed");
          }
        };
    assertSame(
        primary,
        assertThrows(
            IllegalStateException.class,
            () -> TimingSession.start(config(), instrumentation, hooks)));
    assertTrue(removed.get());
    assertTrue(hookRemoved.get());
    assertEquals(2, primary.getSuppressed().length);
    assertFalse(TimingProbe.enabled());
    assertNoConsumer();
    String status = Files.readString(directory.resolve("observer-status.txt"));
    assertTrue(status.contains("collectionHealthy=false"));
    assertTrue(status.contains("statusComplete=false"));
  }

  @Test
  void installedSessionEnablesLastThenDrainsAndPublishesFinalStatistics() throws Exception {
    AtomicReference<ClassFileTransformer> transformer = new AtomicReference<>();
    AtomicReference<Thread> hook = new AtomicReference<>();
    Instrumentation instrumentation =
        instrumentation(
            (method, args) -> {
              if (method.equals("addTransformer")) {
                assertFalse(TimingProbe.enabled());
                transformer.set((ClassFileTransformer) args[0]);
              }
              return false;
            });
    var hooks =
        new TimingSession.Hooks() {
          public void add(Thread thread) {
            hook.set(thread);
          }

          public void remove(Thread thread) {
            fail("installed shutdown owner remains registered");
          }
        };
    try (TimingSession session = TimingSession.start(config(), instrumentation, hooks)) {
      assertNotNull(hook.get());
      assertTrue(TimingProbe.enabled());
      ClassWriter writer = new ClassWriter(0);
      writer.visit(
          Opcodes.V25,
          Opcodes.ACC_PUBLIC,
          "sample/application/Work",
          null,
          "java/lang/Object",
          null);
      var method =
          writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unit", "()I", null, null);
      method.visitCode();
      method.visitInsn(Opcodes.ICONST_1);
      method.visitInsn(Opcodes.IRETURN);
      method.visitMaxs(1, 0);
      method.visitEnd();
      writer.visitEnd();
      assertNotNull(
          transformer
              .get()
              .transform(
                  null,
                  ClassLoader.getSystemClassLoader(),
                  "sample/application/Work",
                  null,
                  null,
                  writer.toByteArray()));
      TimingProbe.exit(0, TimingProbe.enter(), false);
    }
    assertFalse(TimingProbe.enabled());
    assertNoConsumer();
    String status = Files.readString(directory.resolve("observer-status.txt"));
    assertTrue(status.contains("statusComplete=true"));
    assertTrue(status.contains("observedTimingEvents=1"));
    assertTrue(status.contains("acceptedTimingReservations=1"));
    assertTrue(status.contains("shutdownDiscarded=0"));
    assertEquals(2, Files.readAllLines(directory.resolve("timing-summary.tsv")).size());
    assertTrue(
        Files.readString(directory.resolve("methods.tsv"))
            .contains("sample.application.Work.unit()I"));
  }

  private AgentConfig config() {
    return new AgentConfig(List.of("sample.application"), List.of("unit"), 1, 8, directory);
  }

  private interface Invocation {
    Object invoke(String method, Object[] args) throws Throwable;
  }

  private static Instrumentation instrumentation(Invocation invocation) {
    return (Instrumentation)
        Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> invocation.invoke(method.getName(), args));
  }

  private static void assertNoConsumer() {
    assertTrue(
        Thread.getAllStackTraces().keySet().stream()
            .noneMatch(
                thread -> thread.isAlive() && thread.getName().equals("backtrace-probe-consumer")));
  }
}
