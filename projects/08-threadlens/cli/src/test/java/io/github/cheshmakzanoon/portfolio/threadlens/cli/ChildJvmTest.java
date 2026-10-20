package io.github.cheshmakzanoon.portfolio.threadlens.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildJvmTest {
  @TempDir Path directory;

  @Test
  void failedTerminationConfirmationIsSuppressedWithoutReplacingTheDeadline() {
    Process unresponsive = new UnresponsiveProcess(false);
    IOException failure =
        assertThrows(IOException.class, () -> ChildJvm.await(unresponsive, 1, "fixture"));
    assertEquals("fixture: deadline exceeded", failure.getMessage());
    assertEquals("owned child did not terminate", failure.getSuppressed()[0].getMessage());
    assertTrue(((UnresponsiveProcess) unresponsive).destroyed);
  }

  @Test
  void interruptedTerminationConfirmationRestoresInterruptAndRetainsThePrimaryFailure() {
    Process unresponsive = new UnresponsiveProcess(true);
    try {
      IOException failure =
          assertThrows(IOException.class, () -> ChildJvm.await(unresponsive, 1, "fixture"));
      assertEquals("fixture: deadline exceeded", failure.getMessage());
      assertInstanceOf(InterruptedException.class, failure.getSuppressed()[0]);
      assertTrue(Thread.interrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void deadlineTerminatesTheOwnedJvmAndRetainsItsFailure() throws Exception {
    Process process = child("sleep");
    try {
      assertTrue(
          assertThrows(IOException.class, () -> ChildJvm.await(process, 1, "fixture"))
              .getMessage()
              .contains("deadline exceeded"));
      assertFalse(process.isAlive());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void interruptedParentTerminatesItsChildAndRestoresInterrupt() throws Exception {
    Process process = child("sleep");
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> ChildJvm.await(process, 10, "fixture"));
      assertTrue(Thread.interrupted());
      assertFalse(process.isAlive());
    } finally {
      Thread.interrupted();
      process.destroyForcibly();
    }
  }

  @Test
  void successfulAndFailedExitsRemainDistinctAndTimeoutBoundsAreStrict() throws Exception {
    Process success = child("0");
    ChildJvm.await(success, 10, "fixture");
    ChildJvm.await(success, 7200, "fixture");
    Process failure = child("7");
    assertEquals(
        "fixture: exit 7",
        assertThrows(IOException.class, () -> ChildJvm.await(failure, 10, "fixture")).getMessage());
    assertFalse(failure.isAlive());
    assertThrows(IllegalArgumentException.class, () -> ChildJvm.await(success, 0, "fixture"));
    assertThrows(IllegalArgumentException.class, () -> ChildJvm.await(success, 7201, "fixture"));
  }

  @Test
  void misspelledSmokeOptionCannotStartAFullStudyOrCreateOutput() {
    Path output = directory.resolve("unexpected-study");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadLensCli.main(
                new String[] {"benchmark", output.toString(), "missing-agent.jar", "--smke"}));
    assertFalse(Files.exists(output));
  }

  private Process child(String mode) throws IOException {
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            Child.class.getName(),
            mode)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  public static class Child {
    public static void main(String[] args) throws Exception {
      if (args[0].equals("sleep")) new CountDownLatch(1).await();
      else System.exit(Integer.parseInt(args[0]));
    }
  }

  private static final class UnresponsiveProcess extends Process {
    private final boolean interruptConfirmation;
    private int waits;
    boolean destroyed;

    UnresponsiveProcess(boolean interruptConfirmation) {
      this.interruptConfirmation = interruptConfirmation;
    }

    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (++waits > 1 && interruptConfirmation)
        throw new InterruptedException("termination confirmation interrupted");
      return false;
    }

    public boolean isAlive() {
      return true;
    }

    public Process destroyForcibly() {
      destroyed = true;
      return this;
    }

    public void destroy() {
      destroyed = true;
    }

    public int waitFor() {
      throw new UnsupportedOperationException();
    }

    public int exitValue() {
      throw new IllegalThreadStateException();
    }

    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }
  }
}
