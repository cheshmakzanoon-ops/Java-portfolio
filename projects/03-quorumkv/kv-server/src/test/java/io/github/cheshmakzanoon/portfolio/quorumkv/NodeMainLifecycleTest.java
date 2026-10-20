package io.github.cheshmakzanoon.portfolio.quorumkv;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeMainLifecycleTest {
  @TempDir Path directory;

  @Test
  void transportFailureStillFinishesTheWriterBeforeReleasingItsJournal() throws Exception {
    Journal journal = new Journal(directory);
    ExecutorService timer = Executors.newSingleThreadExecutor();
    NodeExecutor execution = new NodeExecutor(error -> fail(error));
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        transportStopped = new CountDownLatch(1);
    AtomicBoolean written = new AtomicBoolean(), acknowledged = new AtomicBoolean();
    AtomicReference<Throwable> observed = new AtomicReference<>();
    IllegalStateException transportFailure = new IllegalStateException("transport shutdown failed");
    Thread closer = null;
    try {
      assertTrue(
          execution.submit(
              () ->
                  execution.persist(
                      () -> {
                        entered.countDown();
                        await(release);
                        written.set(true);
                      },
                      () -> acknowledged.set(true))));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      NodeMain.closeResources(
                          execution,
                          timer,
                          () -> {
                            transportStopped.countDown();
                            throw transportFailure;
                          },
                          journal);
                    } catch (Throwable error) {
                      observed.set(error);
                    }
                  });
      assertTrue(transportStopped.await(2, TimeUnit.SECONDS));
      assertThrows(IOException.class, () -> new Journal(directory));
      release.countDown();
      closer.join(3000);
      assertFalse(closer.isAlive());
      assertSame(transportFailure, observed.get());
      assertTrue(timer.isShutdown());
      assertTrue(written.get());
      assertFalse(acknowledged.get());
      assertFalse(execution.submit(() -> fail("closed execution admitted work")));
      try (Journal reopened = new Journal(directory)) {
        assertEquals(0, reopened.recovered().commit());
      }
    } finally {
      release.countDown();
      if (closer != null) closer.join(3000);
      execution.close();
      journal.close();
      timer.shutdownNow();
    }
  }

  @Test
  void failedWriterTerminationRetainsTheLockAndPreservesBothShutdownFailures() throws Exception {
    Journal journal = new Journal(directory);
    ExecutorService timer = Executors.newSingleThreadExecutor();
    NodeExecutor execution = new NodeExecutor(error -> fail(error), 100);
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        finished = new CountDownLatch(1);
    IllegalStateException transportFailure = new IllegalStateException("transport shutdown failed");
    try {
      assertTrue(
          execution.submit(
              () ->
                  execution.persist(
                      () -> {
                        entered.countDown();
                        await(release);
                        finished.countDown();
                      },
                      () -> fail("shutdown cannot acknowledge"))));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  NodeMain.closeResources(
                      execution,
                      timer,
                      () -> {
                        throw transportFailure;
                      },
                      journal));
      assertSame(transportFailure, failure);
      assertEquals(1, failure.getSuppressed().length);
      assertEquals("DISK_SHUTDOWN_TIMEOUT", failure.getSuppressed()[0].getMessage());
      assertThrows(IOException.class, () -> new Journal(directory));
      release.countDown();
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      NodeMain.closeResources(execution, timer, () -> {}, journal);
      try (Journal reopened = new Journal(directory)) {
        assertEquals(0, reopened.recovered().commit());
      }
    } finally {
      release.countDown();
      execution.close();
      journal.close();
      timer.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new IOException("test barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(interrupted);
    }
  }
}
