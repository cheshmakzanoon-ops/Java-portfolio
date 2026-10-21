package io.github.cheshmakzanoon.portfolio.majority;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeExecutorTest {
  @TempDir Path directory;

  @Test
  void queuedProtocolWorkCannotRunAfterAnEarlierActionFails() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        failed = new CountDownLatch(1);
    AtomicBoolean secondRan = new AtomicBoolean();
    NodeExecutor executor = new NodeExecutor(error -> failed.countDown());
    try {
      assertTrue(
          executor.submit(
              () -> {
                entered.countDown();
                await(release);
                throw new IllegalStateException("failed transition");
              }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(executor.submit(() -> secondRan.set(true)));
      release.countDown();
      assertTrue(failed.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.close();
    }
    assertFalse(secondRan.get());
  }

  @Test
  void diskFailureClosesQueuedExecutionWithoutAcknowledgingPersistence() throws Exception {
    CountDownLatch diskEntered = new CountDownLatch(1),
        failDisk = new CountDownLatch(1),
        ownerEntered = new CountDownLatch(1),
        releaseOwner = new CountDownLatch(1),
        failed = new CountDownLatch(1);
    AtomicBoolean acknowledged = new AtomicBoolean(),
        queued = new AtomicBoolean(),
        interruptedFailure = new AtomicBoolean();
    NodeExecutor executor =
        new NodeExecutor(
            error -> {
              interruptedFailure.set(Thread.currentThread().isInterrupted());
              failed.countDown();
            });
    try {
      assertTrue(
          executor.submit(
              () ->
                  executor.persist(
                      () -> {
                        diskEntered.countDown();
                        await(failDisk);
                        throw new IOException("disk failure");
                      },
                      () -> acknowledged.set(true))));
      assertTrue(diskEntered.await(2, TimeUnit.SECONDS));
      assertTrue(
          executor.submit(
              () -> {
                ownerEntered.countDown();
                await(releaseOwner);
              }));
      assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));
      assertTrue(executor.submit(() -> queued.set(true)));
      failDisk.countDown();
      assertTrue(failed.await(2, TimeUnit.SECONDS));
      releaseOwner.countDown();
    } finally {
      failDisk.countDown();
      releaseOwner.countDown();
      executor.close();
    }
    assertFalse(interruptedFailure.get());
    assertFalse(queued.get());
    assertFalse(acknowledged.get());
  }

  @Test
  void shutdownFinishesAdmittedDiskWriteAndAbandonsQueuedProtocolWork() throws Exception {
    CountDownLatch diskEntered = new CountDownLatch(1), finishDisk = new CountDownLatch(1);
    AtomicBoolean written = new AtomicBoolean(),
        acknowledged = new AtomicBoolean(),
        queued = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    NodeExecutor executor = new NodeExecutor(failure::set);
    assertTrue(
        executor.submit(
            () ->
                executor.persist(
                    () -> {
                      diskEntered.countDown();
                      await(finishDisk);
                      written.set(true);
                    },
                    () -> acknowledged.set(true))));
    assertTrue(diskEntered.await(2, TimeUnit.SECONDS));
    executor.beginClose();
    assertFalse(executor.submit(() -> queued.set(true)));
    Thread closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    executor.close();
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    finishDisk.countDown();
    closer.join(3000);
    assertFalse(closer.isAlive());
    assertNull(failure.get());
    assertTrue(written.get());
    assertFalse(acknowledged.get());
    assertFalse(queued.get());
    executor.close();
  }

  @Test
  void closeAloneEstablishesTheBarrierBeforeDrainingQueuedActions() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean queued = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    NodeExecutor executor = new NodeExecutor(failure::set);
    Thread closer = null;
    try {
      assertTrue(
          executor.submit(
              () -> {
                entered.countDown();
                await(release);
              }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertTrue(executor.submit(() -> queued.set(true)));
      closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      executor.close();
                    } catch (Throwable error) {
                      failure.set(error);
                    }
                  });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (closer.getState() != Thread.State.TIMED_WAITING
          && closer.isAlive()
          && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(Thread.State.TIMED_WAITING, closer.getState());
      release.countDown();
      closer.join(3000);
      assertFalse(closer.isAlive());
      assertNull(failure.get());
      assertFalse(queued.get());
    } finally {
      release.countDown();
      if (closer != null) closer.join(3000);
      executor.close();
    }
  }

  @Test
  void onePersistenceWriteDeliversExactlyOneCompletion() throws Exception {
    AtomicInteger acknowledgments = new AtomicInteger();
    CountDownLatch first = new CountDownLatch(1), duplicate = new CountDownLatch(1);
    try (NodeExecutor executor = new NodeExecutor(error -> fail(error))) {
      assertTrue(
          executor.submit(
              () ->
                  executor.persist(
                      () -> {},
                      () -> {
                        if (acknowledgments.incrementAndGet() == 1) first.countDown();
                        else duplicate.countDown();
                      })));
      assertTrue(first.await(2, TimeUnit.SECONDS));
      assertFalse(duplicate.await(100, TimeUnit.MILLISECONDS));
      assertEquals(1, acknowledgments.get());
    }
  }

  @Test
  void interruptedDiskCompletionRestoresTheFailureCallbacksInterruptStatus() throws Exception {
    CountDownLatch failed = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(), acknowledged = new AtomicBoolean();
    try (NodeExecutor executor =
        new NodeExecutor(
            error -> {
              interrupted.set(Thread.currentThread().isInterrupted());
              failed.countDown();
            })) {
      assertTrue(
          executor.submit(
              () ->
                  executor.persist(
                      () -> Thread.currentThread().interrupt(), () -> acknowledged.set(true))));
      assertTrue(failed.await(2, TimeUnit.SECONDS));
      assertTrue(interrupted.get());
      assertFalse(acknowledged.get());
    }
  }

  @Test
  void failedNodeConstructionReleasesTheStorageDirectoryLock() throws Exception {
    var settings =
        new TlsTransport.Settings(directory.resolve("missing-tls"), 18300, new Properties());
    assertThrows(Exception.class, () -> new NodeMain(0, directory.resolve("data"), settings));
    try (Journal recovered = new Journal(directory.resolve("data"))) {
      assertEquals(0, recovered.recovered().commit());
    }
    assertThrows(
        IllegalArgumentException.class, () -> new NodeMain(3, directory.resolve("data"), settings));
    try (Journal recovered = new Journal(directory.resolve("data"))) {
      assertEquals(0, recovered.recovered().term());
    }
  }

  @Test
  void fullOwnerQueueRejectsExternalAdmissionButEventuallyDeliversDiskCompletion()
      throws Exception {
    CountDownLatch ownerEntered = new CountDownLatch(1),
        releaseOwner = new CountDownLatch(1),
        persisted = new CountDownLatch(1),
        acknowledged = new CountDownLatch(1);
    AtomicInteger completed = new AtomicInteger();
    NodeExecutor executor = new NodeExecutor(error -> fail(error));
    try {
      assertTrue(
          executor.submit(
              () -> {
                executor.persist(persisted::countDown, acknowledged::countDown);
                ownerEntered.countDown();
                await(releaseOwner);
              }));
      assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));
      assertTrue(persisted.await(2, TimeUnit.SECONDS));
      // Reserve space for a completion that may already be queued; fill until rejection.
      int admitted = 0;
      boolean rejected = false;
      for (int attempt = 0; attempt < 513; attempt++) {
        if (!executor.submit(completed::incrementAndGet)) {
          rejected = true;
          break;
        }
        admitted++;
      }
      assertTrue(rejected, "bounded admission must reject before a test can spin indefinitely");
      assertTrue(admitted >= 511 && admitted <= 512);
      releaseOwner.countDown();
      assertTrue(acknowledged.await(2, TimeUnit.SECONDS));
    } finally {
      releaseOwner.countDown();
      executor.close();
    }
  }

  @Test
  void shutdownDeadlineDoesNotPretendAnUnfinishedWriterStopped() throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        finished = new CountDownLatch(1);
    NodeExecutor executor = new NodeExecutor(error -> fail(error), 100);
    try {
      assertTrue(
          executor.submit(
              () ->
                  executor.persist(
                      () -> {
                        entered.countDown();
                        await(release);
                        finished.countDown();
                      },
                      () -> fail("shutdown cannot acknowledge"))));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      IOException failure = assertThrows(IOException.class, executor::close);
      assertEquals("DISK_SHUTDOWN_TIMEOUT", failure.getMessage());
      assertEquals(1, finished.getCount());
      release.countDown();
      assertTrue(finished.await(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.close();
    }
  }

  @Test
  void interruptedShutdownRestoresInterruptAndCanBeRetriedAfterOwnerStops() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    NodeExecutor executor = new NodeExecutor(error -> {});
    try {
      assertTrue(
          executor.submit(
              () -> {
                entered.countDown();
                await(release);
              }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Thread.currentThread().interrupt();
      assertThrows(IOException.class, executor::close);
      assertTrue(Thread.interrupted(), "the interrupted status must be preserved for the caller");
    } finally {
      Thread.interrupted();
      release.countDown();
      executor.close();
    }
  }

  @Test
  void protocolShutdownTimeoutIsReportedAndItsInterruptedTaskCannotResumeAdmission()
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        stopped = new CountDownLatch(1);
    NodeExecutor executor = new NodeExecutor(error -> {}, 100);
    try {
      executor.submit(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              stopped.countDown();
            }
          });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      IOException failure = assertThrows(IOException.class, executor::close);
      assertEquals("PROTOCOL_SHUTDOWN_TIMEOUT", failure.getMessage());
      assertTrue(stopped.await(2, TimeUnit.SECONDS));
      assertFalse(executor.submit(() -> fail("closed executor admitted work")));
    } finally {
      release.countDown();
      executor.close();
    }
    assertThrows(IllegalArgumentException.class, () -> new NodeExecutor(error -> {}, 0));
    try (NodeExecutor shortest = new NodeExecutor(error -> fail(error), 1)) {
      // A positive deadline is valid even when it is shorter than the default.
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
