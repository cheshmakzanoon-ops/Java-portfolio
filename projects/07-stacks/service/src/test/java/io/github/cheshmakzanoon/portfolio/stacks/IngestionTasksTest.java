package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class IngestionTasksTest {
  private static final Duration WAIT = Duration.ofSeconds(5);

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(WAIT.toNanos(), TimeUnit.NANOSECONDS), "latch deadline");
  }

  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), "condition deadline");
  }

  /** Blocks after cancellation until the test explicitly allows the worker to leave. */
  private static final class HeldWork implements IngestionTasks.Work {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger runs = new AtomicInteger();
    final boolean finishNormally;

    HeldWork(boolean finishNormally) {
      this.finishNormally = finishNormally;
    }

    @Override
    public void execute() throws Exception {
      runs.incrementAndGet();
      entered.countDown();
      boolean sawInterrupt = false;
      while (true) {
        try {
          if (!release.await(WAIT.toNanos(), TimeUnit.NANOSECONDS))
            throw new IOException("test worker deadline");
          break;
        } catch (InterruptedException cancelled) {
          sawInterrupt = true;
          interrupted.countDown();
        }
      }
      if (sawInterrupt && !finishNormally) throw new InterruptedException("cooperative exit");
    }
  }

  @Test
  void boundedAdmissionDoesNotCountIdempotentDuplicateAsAnotherTask() throws Exception {
    var held = new HeldWork(true);
    AtomicInteger queuedRuns = new AtomicInteger();
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      try {
        assertEquals(IngestionTasks.State.QUEUED, tasks.submit("a", "first", held));
        await(held.entered);
        assertEquals(IngestionTasks.State.RUNNING, tasks.submit("a", "first", () -> fail()));
        assertEquals(
            IngestionTasks.State.QUEUED, tasks.submit("a", "second", queuedRuns::incrementAndGet));
        assertEquals(IngestionTasks.State.QUEUED, tasks.submit("a", "second", () -> fail()));
        assertThrows(
            RejectedExecutionException.class, () -> tasks.submit("a", "third", () -> fail()));
        assertEquals(IngestionTasks.State.REGISTERED, tasks.state("third"));
        assertEquals(new IngestionTasks.Counts(0, 1, 1, 0), tasks.counts("a"));
        assertEquals(0, queuedRuns.get());
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("second") == IngestionTasks.State.COMPLETE);
      assertEquals(1, held.runs.get());
      assertEquals(1, queuedRuns.get());
      assertEquals(IngestionTasks.State.COMPLETE, tasks.state("first"));
      assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("a"));
    }
  }

  @Test
  void cancellingQueuedTasksImmediatelyReturnsCapacityWithoutRunningTheirBodies() throws Exception {
    var held = new HeldWork(true);
    AtomicInteger cancelledBodies = new AtomicInteger();
    try (var tasks = new IngestionTasks(1, 1, 8, WAIT)) {
      try {
        tasks.submit("a", "held", held);
        await(held.entered);
        for (int i = 0; i < 64; i++) {
          String id = "queued-" + i;
          assertEquals(
              IngestionTasks.State.QUEUED, tasks.submit("a", id, cancelledBodies::incrementAndGet));
          assertEquals(IngestionTasks.State.CANCELLED, tasks.cancel(id));
          assertEquals(IngestionTasks.State.CANCELLED, tasks.state(id));
          assertEquals(new IngestionTasks.Counts(0, 0, 1, 0), tasks.counts("a"));
        }
        assertEquals(0, cancelledBodies.get());
        // Retained outcomes are bounded; none of those evictions can remove active work.
        assertEquals(IngestionTasks.State.REGISTERED, tasks.state("queued-0"));
        assertEquals(IngestionTasks.State.RUNNING, tasks.state("held"));
        tasks.submit("a", "next", () -> {});
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("next") == IngestionTasks.State.COMPLETE);
      assertEquals(0, cancelledBodies.get());
    }
  }

  @Test
  void cancelledRunningAttemptCannotBeResumedUntilItsWorkerActuallyExits() throws Exception {
    var held = new HeldWork(false);
    AtomicInteger retryRuns = new AtomicInteger();
    try (var tasks = new IngestionTasks(1, 2, 16, WAIT)) {
      try {
        tasks.submit("a", "job", held);
        await(held.entered);
        assertEquals(IngestionTasks.State.CANCELLING, tasks.cancel("job"));
        await(held.interrupted);
        for (int i = 0; i < 32; i++)
          assertEquals(
              IngestionTasks.State.CANCELLING,
              tasks.submit("a", "job", retryRuns::incrementAndGet));
        assertEquals(IngestionTasks.State.CANCELLING, tasks.cancel("job"));
        assertEquals(new IngestionTasks.Counts(0, 0, 0, 1), tasks.counts("a"));
        assertEquals(0, retryRuns.get());
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("job") == IngestionTasks.State.CANCELLED);
      tasks.submit("a", "job", retryRuns::incrementAndGet);
      eventually(() -> tasks.state("job") == IngestionTasks.State.COMPLETE);
      assertEquals(1, retryRuns.get());
      assertEquals(1, held.runs.get());
      assertEquals(0, tasks.counts("a").failures());
    }
  }

  @Test
  void acknowledgedSuccessWinsWhenCancellationArrivesAfterTheWorkCommitted() throws Exception {
    var held = new HeldWork(true);
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      try {
        tasks.submit("a", "committed", held);
        await(held.entered);
        assertEquals(IngestionTasks.State.CANCELLING, tasks.cancel("committed"));
        await(held.interrupted);
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("committed") == IngestionTasks.State.COMPLETE);
      assertEquals(IngestionTasks.State.COMPLETE, tasks.cancel("committed"));
      assertEquals(0, tasks.counts("a").failures());
    }
  }

  @Test
  void unexpectedFailureIsCountedOnceAndRetryHasItsOwnOutcome() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      tasks.submit(
          "a",
          "retry",
          () -> {
            runs.incrementAndGet();
            throw new IOException("source path must never enter public state");
          });
      eventually(() -> tasks.state("retry") == IngestionTasks.State.FAILED);
      assertEquals(1, tasks.counts("a").failures());
      assertEquals(IngestionTasks.State.FAILED, tasks.cancel("retry"));
      assertEquals(1, tasks.counts("a").failures());
      tasks.submit("a", "retry", runs::incrementAndGet);
      eventually(() -> tasks.state("retry") == IngestionTasks.State.COMPLETE);
      assertEquals(2, runs.get());
      assertEquals(1, tasks.counts("a").failures());
      assertEquals(0, tasks.counts("unrelated").failures());
    }
  }

  @Test
  void rejectedRetryPreservesThePreviousFailureAndCanBeAdmittedLater() throws Exception {
    var held = new HeldWork(true);
    AtomicInteger retryRuns = new AtomicInteger();
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      tasks.submit(
          "a",
          "retry",
          () -> {
            throw new IOException("failed");
          });
      eventually(() -> tasks.state("retry") == IngestionTasks.State.FAILED);
      try {
        tasks.submit("a", "held", held);
        await(held.entered);
        tasks.submit("a", "queued", () -> {});
        assertThrows(
            RejectedExecutionException.class,
            () -> tasks.submit("a", "retry", retryRuns::incrementAndGet));
        assertEquals(IngestionTasks.State.FAILED, tasks.state("retry"));
        assertEquals(IngestionTasks.State.CANCELLED, tasks.cancel("queued"));
        tasks.submit("a", "retry", retryRuns::incrementAndGet);
        assertEquals(IngestionTasks.State.QUEUED, tasks.state("retry"));
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("retry") == IngestionTasks.State.COMPLETE);
      assertEquals(1, retryRuns.get());
      assertEquals(1, tasks.counts("a").failures());
    }
  }

  @Test
  void metricsDistinguishTenantsAndCancellationFromFailure() throws Exception {
    var first = new HeldWork(false);
    var second = new HeldWork(true);
    try (var tasks = new IngestionTasks(2, 4, 16, WAIT)) {
      try {
        tasks.submit("a", "a-running", first);
        tasks.submit("b", "b-running", second);
        await(first.entered);
        await(second.entered);
        tasks.submit("a", "a-queued", () -> {});
        tasks.submit("b", "b-queued", () -> {});
        tasks.cancel("a-running");
        await(first.interrupted);
        assertEquals(new IngestionTasks.Counts(0, 1, 0, 1), tasks.counts("a"));
        assertEquals(new IngestionTasks.Counts(0, 1, 1, 0), tasks.counts("b"));
        assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("other"));
        tasks.cancel("a-queued");
        assertEquals(new IngestionTasks.Counts(0, 0, 0, 1), tasks.counts("a"));
      } finally {
        first.release.countDown();
        second.release.countDown();
      }
      eventually(() -> tasks.state("b-queued") == IngestionTasks.State.COMPLETE);
      eventually(() -> tasks.state("a-running") == IngestionTasks.State.CANCELLED);
      assertEquals(0, tasks.counts("a").failures());
      assertEquals(0, tasks.counts("b").failures());
    }
  }

  @Test
  void concurrentDuplicateSubmissionsExecuteOnlyOnce() throws Exception {
    var held = new HeldWork(true);
    int clients = 24;
    try (var tasks = new IngestionTasks(2, 2, 16, WAIT);
        var pool = Executors.newFixedThreadPool(clients)) {
      CyclicBarrier start = new CyclicBarrier(clients);
      List<Future<IngestionTasks.State>> submissions = new ArrayList<>();
      try {
        for (int i = 0; i < clients; i++)
          submissions.add(
              pool.submit(
                  () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return tasks.submit("a", "one", held);
                  }));
        await(held.entered);
        for (var submission : submissions)
          assertTrue(
              List.of(IngestionTasks.State.QUEUED, IngestionTasks.State.RUNNING)
                  .contains(submission.get(5, TimeUnit.SECONDS)));
        assertEquals(1, held.runs.get());
        assertEquals(new IngestionTasks.Counts(0, 0, 1, 0), tasks.counts("a"));
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("one") == IngestionTasks.State.COMPLETE);
    }
  }

  @Test
  void crossTenantAttemptIdentityCollisionCannotJoinAnExistingTask() throws Exception {
    var held = new HeldWork(true);
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      try {
        tasks.submit("a", "same-id", held);
        await(held.entered);
        assertThrows(SecurityException.class, () -> tasks.submit("b", "same-id", () -> {}));
        assertEquals(new IngestionTasks.Counts(0, 0, 1, 0), tasks.counts("a"));
        assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("b"));
      } finally {
        held.release.countDown();
      }
    }
  }

  @Test
  void completedOutcomeEvictionsNeverEvictAnActiveAttempt() throws Exception {
    var held = new HeldWork(true);
    try (var tasks = new IngestionTasks(2, 2, 2, WAIT)) {
      try {
        tasks.submit("a", "held", held);
        await(held.entered);
        for (int i = 0; i < 20; i++) {
          String id = "quick-" + i;
          tasks.submit("b", id, () -> {});
          eventually(() -> tasks.state(id) == IngestionTasks.State.COMPLETE);
        }
        assertEquals(IngestionTasks.State.REGISTERED, tasks.state("quick-0"));
        assertEquals(IngestionTasks.State.COMPLETE, tasks.state("quick-18"));
        assertEquals(IngestionTasks.State.COMPLETE, tasks.state("quick-19"));
        assertEquals(IngestionTasks.State.RUNNING, tasks.state("held"));
        assertEquals(IngestionTasks.State.RUNNING, tasks.submit("a", "held", () -> fail()));
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("held") == IngestionTasks.State.COMPLETE);
    }
  }

  @Test
  void shutdownClosesAdmissionBeforeWaitingForCancellationToFinish() throws Exception {
    var held = new HeldWork(false);
    var tasks = new IngestionTasks(1, 2, 16, WAIT);
    AtomicInteger queuedRuns = new AtomicInteger();
    try (var pool = Executors.newSingleThreadExecutor()) {
      Future<?> closing = null;
      try {
        tasks.submit("a", "held", held);
        await(held.entered);
        tasks.submit("a", "queued", queuedRuns::incrementAndGet);
        closing =
            pool.submit(
                () -> {
                  tasks.close();
                  return null;
                });
        await(held.interrupted);
        assertThrows(IngestionTasks.Stopping.class, () -> tasks.submit("a", "new", () -> fail()));
        assertThrows(IngestionTasks.Stopping.class, () -> tasks.submit("a", "held", () -> fail()));
        assertEquals(IngestionTasks.State.CANCELLED, tasks.state("queued"));
        assertEquals(IngestionTasks.State.CANCELLING, tasks.state("held"));
        assertFalse(closing.isDone(), "close must join the actual worker, not a cancelled Future");
        assertEquals(0, queuedRuns.get());
      } finally {
        held.release.countDown();
        if (closing != null) closing.get(5, TimeUnit.SECONDS);
        tasks.close();
      }
      assertEquals(IngestionTasks.State.CANCELLED, tasks.state("held"));
      assertEquals(0, queuedRuns.get());
      assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("a"));
    }
  }

  @Test
  void shutdownDeadlineRetainsOwnershipAndSecondCloseCanJoinLateWorker() throws Exception {
    var held = new HeldWork(false);
    var tasks = new IngestionTasks(1, 1, 16, Duration.ofMillis(20));
    try {
      tasks.submit("a", "held", held);
      await(held.entered);
      IOException failure = assertThrows(IOException.class, tasks::close);
      assertEquals("ingestion shutdown deadline exceeded", failure.getMessage());
      await(held.interrupted);
      assertEquals(IngestionTasks.State.CANCELLING, tasks.state("held"));
      assertThrows(IngestionTasks.Stopping.class, () -> tasks.submit("a", "held", () -> fail()));
    } finally {
      held.release.countDown();
      eventually(() -> tasks.state("held") == IngestionTasks.State.CANCELLED);
      tasks.close();
    }
  }

  @Test
  void interruptedShutdownPreservesTheCallersInterruptAndStillCancelsTasks() throws Exception {
    var held = new HeldWork(false);
    var tasks = new IngestionTasks(1, 1, 16, WAIT);
    AtomicReference<IOException> failure = new AtomicReference<>();
    AtomicBoolean interruptPreserved = new AtomicBoolean();
    try {
      tasks.submit("a", "held", held);
      await(held.entered);
      Thread closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    Thread.currentThread().interrupt();
                    try {
                      tasks.close();
                    } catch (IOException expected) {
                      failure.set(expected);
                      interruptPreserved.set(Thread.currentThread().isInterrupted());
                    }
                  });
      closer.join(5000);
      assertFalse(closer.isAlive());
      assertNotNull(failure.get());
      assertEquals("ingestion shutdown interrupted", failure.get().getMessage());
      assertTrue(interruptPreserved.get());
      await(held.interrupted);
      assertEquals(IngestionTasks.State.CANCELLING, tasks.state("held"));
      assertThrows(IngestionTasks.Stopping.class, () -> tasks.submit("a", "new", () -> {}));
    } finally {
      held.release.countDown();
      tasks.close();
    }
  }

  @Test
  void cancellationInterruptDoesNotLeakIntoTheNextJobOnTheSameWorker() throws Exception {
    var held = new HeldWork(false);
    AtomicBoolean nextInterrupted = new AtomicBoolean(true);
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      try {
        tasks.submit("a", "first", held);
        await(held.entered);
        tasks.cancel("first");
        await(held.interrupted);
        tasks.submit(
            "b", "next", () -> nextInterrupted.set(Thread.currentThread().isInterrupted()));
      } finally {
        held.release.countDown();
      }
      eventually(() -> tasks.state("next") == IngestionTasks.State.COMPLETE);
      assertFalse(nextInterrupted.get());
      assertEquals(IngestionTasks.State.CANCELLED, tasks.state("first"));
    }
  }

  @Test
  void cancellationAndWorkerPickupRaceNeverRunsTwoAttemptsForOneRegistration() throws Exception {
    for (int schedule = 0; schedule < 32; schedule++) {
      var held = new HeldWork(true);
      AtomicInteger simultaneous = new AtomicInteger();
      AtomicInteger maximum = new AtomicInteger();
      AtomicInteger executions = new AtomicInteger();
      IngestionTasks.Work quick =
          () -> {
            int active = simultaneous.incrementAndGet();
            maximum.accumulateAndGet(active, Math::max);
            try {
              executions.incrementAndGet();
              Thread.yield();
            } finally {
              simultaneous.decrementAndGet();
            }
          };
      try (var tasks = new IngestionTasks(2, 4, 16, WAIT);
          var pool = Executors.newFixedThreadPool(2)) {
        tasks.submit("a", "held-1", held);
        tasks.submit("a", "held-2", held);
        eventually(() -> held.runs.get() == 2);
        tasks.submit("a", "target", quick);
        CyclicBarrier race = new CyclicBarrier(2);
        var cancel =
            pool.submit(
                () -> {
                  race.await();
                  return tasks.cancel("target");
                });
        var release =
            pool.submit(
                () -> {
                  race.await();
                  held.release.countDown();
                  return null;
                });
        cancel.get(5, TimeUnit.SECONDS);
        release.get(5, TimeUnit.SECONDS);
        tasks.submit("a", "target", quick);
        eventually(
            () ->
                tasks.state("target") == IngestionTasks.State.COMPLETE
                    || tasks.state("target") == IngestionTasks.State.CANCELLED);
        assertTrue(executions.get() <= 2);
        assertTrue(maximum.get() <= 1, "cancelled old attempt must not overlap its replacement");
      } finally {
        held.release.countDown();
      }
    }
  }

  @Test
  void missingOutcomeAndUnknownCancellationStayRegisteredWithoutAllocatingWork() throws Exception {
    try (var tasks = new IngestionTasks(1, 1, 0, WAIT)) {
      assertEquals(IngestionTasks.State.REGISTERED, tasks.state("unknown"));
      assertEquals(IngestionTasks.State.REGISTERED, tasks.cancel("unknown"));
      var ran = new CountDownLatch(1);
      tasks.submit("a", "unretained", ran::countDown);
      await(ran);
      eventually(() -> tasks.counts("a").running() == 0);
      assertEquals(IngestionTasks.State.REGISTERED, tasks.state("unretained"));
      assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("a"));
    }
  }

  @Test
  void invalidLimitsAndIdentitiesFailBeforeAnyWorkIsAdmitted() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> new IngestionTasks(0, 1, 1, WAIT));
    assertThrows(IllegalArgumentException.class, () -> new IngestionTasks(1, 0, 1, WAIT));
    assertThrows(IllegalArgumentException.class, () -> new IngestionTasks(1, 1, -1, WAIT));
    assertThrows(IllegalArgumentException.class, () -> new IngestionTasks(1, 1, 1, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new IngestionTasks(1, 1, 1, Duration.ofNanos(-1)));
    try (var tasks = new IngestionTasks(1, 1, 16, WAIT)) {
      for (String bad : new String[] {null, "", "  "}) {
        assertThrows(IllegalArgumentException.class, () -> tasks.submit(bad, "job", () -> {}));
        assertThrows(IllegalArgumentException.class, () -> tasks.submit("tenant", bad, () -> {}));
      }
      assertThrows(NullPointerException.class, () -> tasks.submit("tenant", "job", null));
      assertEquals(new IngestionTasks.Counts(0, 0, 0, 0), tasks.counts("tenant"));
    }
  }
}
