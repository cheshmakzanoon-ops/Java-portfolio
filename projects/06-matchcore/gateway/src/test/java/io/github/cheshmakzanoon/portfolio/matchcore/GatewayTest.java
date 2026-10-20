package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayTest {
  @TempDir Path dir;

  Command init(String id) {
    return new Command(id, "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 100);
  }

  @Test
  void concurrentPendingRetriesAndForceBarrier() throws Exception {
    CountDownLatch forced = new CountDownLatch(1), release = new CountDownLatch(1);
    try (Gateway g =
        new Gateway(
            dir.resolve("journal"),
            "run",
            4,
            10,
            b -> {
              if (b.equals("AFTER_FORCE")) {
                forced.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("test timeout");
                } catch (InterruptedException e) {
                  throw new java.io.IOException(e);
                }
              }
            })) {
      Command c = init("first");
      CompletableFuture<Result> first = g.submit("alice", c);
      assertTrue(forced.await(5, TimeUnit.SECONDS));
      assertFalse(first.isDone());
      assertEquals(0, g.view().sequence());
      try (ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<CompletableFuture<Result>>> retries = new ArrayList<>();
        for (int i = 0; i < 50; i++) retries.add(producers.submit(() -> g.submit("alice", c)));
        for (var retry : retries) assertNotSame(first, retry.get(3, TimeUnit.SECONDS));
      }
      assertEquals(
          "CONFLICT",
          g.submit(
                  "alice",
                  new Command(
                      "first", "alice", Type.INIT, null, null, 0, 0, null, false, 2000, 100))
              .get(5, TimeUnit.SECONDS)
              .code());
      assertThrows(SecurityException.class, () -> g.submit("bob", c));
      assertThrows(TimeoutException.class, () -> g.read(1, Duration.ofMillis(10)));
      release.countDown();
      assertEquals(1, first.get(5, TimeUnit.SECONDS).sequence());
      assertEquals(1, g.read(1, Duration.ofSeconds(1)).sequence());
      assertEquals(1, g.submit("alice", c).get(5, TimeUnit.SECONDS).sequence());
    }
  }

  @Test
  void callerFutureMutationCannotChangeAnotherWaiterOrDurableAuthority() throws Exception {
    CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
    try (Gateway gateway =
        new Gateway(
            dir.resolve("owned-futures"),
            "run",
            8,
            10,
            boundary -> {
              if (boundary.equals("AFTER_FORCE")) {
                at.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("test deadline");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(interrupted);
                }
              }
            })) {
      Command command = init("first");
      var canceled = gateway.submit("alice", command);
      assertTrue(at.await(5, TimeUnit.SECONDS));
      var forged = gateway.submit("alice", command);
      var failed = gateway.submit("alice", command);
      var observed = gateway.submit("alice", command);
      canceled.cancel(true);
      forged.complete(new Result(999, "FORGED", command.hash(), List.of()));
      failed.completeExceptionally(new AssertionError("caller-owned failure"));
      assertFalse(observed.isDone());
      var callback =
          observed.thenRun(
              () ->
                  assertFalse(Thread.holdsLock(gateway), "callback ran inside admission monitor"));
      release.countDown();
      assertEquals("INITIALIZED", observed.get(5, TimeUnit.SECONDS).code());
      callback.get(5, TimeUnit.SECONDS);
      assertEquals(1, gateway.result("alice", "first").sequence());
      assertEquals("INITIALIZED", gateway.submit("alice", command).get(5, TimeUnit.SECONDS).code());
      assertTrue(gateway.healthy());
    } finally {
      release.countDown();
    }
  }

  @Test
  void appliedButUnacknowledgedCommandsAreCountedOnceAtTheRunCap() throws Exception {
    CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    try (Gateway gateway =
        new Gateway(
            dir.resolve("admission-count"),
            "run",
            3,
            2,
            boundary -> {
              if (boundary.equals("AFTER_APPLY") && once.compareAndSet(false, true)) {
                at.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("test deadline");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(interrupted);
                }
              }
            })) {
      var first = gateway.submit("alice", init("first"));
      assertTrue(at.await(5, TimeUnit.SECONDS));
      var second = gateway.submit("alice", init("second"));
      assertFalse(second.isCompletedExceptionally());
      assertEquals(1, gateway.queueDepth());
      release.countDown();
      assertEquals(1, first.get(5, TimeUnit.SECONDS).sequence());
      assertEquals(2, second.get(5, TimeUnit.SECONDS).sequence());
      assertThrows(
          ExecutionException.class,
          () -> gateway.submit("alice", init("third")).get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  @Test
  void queuedCapacityAndWaitingReadsObserveOnlyForcedPublication() throws Exception {
    CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    try (Gateway gateway =
            new Gateway(
                dir.resolve("capacity"),
                "run",
                3,
                10,
                boundary -> {
                  if (boundary.equals("AFTER_FORCE") && once.compareAndSet(false, true)) {
                    at.countDown();
                    try {
                      if (!release.await(5, TimeUnit.SECONDS))
                        throw new java.io.IOException("test deadline");
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      throw new java.io.IOException(interrupted);
                    }
                  }
                });
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
      Command malformed =
          new Command("bad id", "alice", Type.INIT, null, null, 0, 0, null, false, 1, 1);
      assertThrows(IllegalArgumentException.class, () -> gateway.submit("alice", malformed));
      assertEquals(0, gateway.view().sequence());
      var first = gateway.submit("alice", init("one"));
      assertTrue(at.await(5, TimeUnit.SECONDS));
      var second = gateway.submit("alice", init("two"));
      var third = gateway.submit("alice", init("three"));
      assertEquals(2, gateway.queueDepth());
      assertThrows(
          ExecutionException.class,
          () -> gateway.submit("alice", init("four")).get(5, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, gateway::snapshot);
      assertThrows(IllegalStateException.class, gateway::fullView);
      CountDownLatch reading = new CountDownLatch(1);
      Future<View> waiting =
          readers.submit(
              () -> {
                reading.countDown();
                return gateway.read(3, Duration.ofSeconds(3));
              });
      assertTrue(reading.await(1, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> waiting.get(40, TimeUnit.MILLISECONDS));
      assertThrows(TimeoutException.class, () -> gateway.read(1, Duration.ofMillis(20)));
      release.countDown();
      assertEquals(1, first.get(1, TimeUnit.SECONDS).sequence());
      assertEquals(2, second.get(1, TimeUnit.SECONDS).sequence());
      assertEquals(3, third.get(1, TimeUnit.SECONDS).sequence());
      assertEquals(3, waiting.get(500, TimeUnit.MILLISECONDS).sequence());
      assertEquals(0, gateway.queueDepth());
    } finally {
      release.countDown();
    }
  }

  @Test
  void paginatedExecutionsAndDurableResultLookupsSurviveCheckpointRecovery() throws Exception {
    Path path = dir.resolve("trade-checkpoint");
    var commands =
        List.of(
            new Command("init", "buyer", Type.INIT, null, null, 0, 0, null, false, 1000, 0),
            new Command("init", "seller1", Type.INIT, null, null, 0, 0, null, false, 0, 10),
            new Command("init", "seller2", Type.INIT, null, null, 0, 0, null, false, 0, 10),
            new Command("ask", "seller1", Type.NEW, "ask", Side.SELL, 10, 1, Tif.GTC, false, 0, 0),
            new Command("ask", "seller2", Type.NEW, "ask", Side.SELL, 11, 3, Tif.GTC, false, 0, 0),
            new Command("buy", "buyer", Type.NEW, "buy", Side.BUY, 12, 3, Tif.GTC, false, 0, 0));
    View expected;
    try (Gateway gateway = new Gateway(path, "run", 4, 10)) {
      for (Command command : commands)
        gateway.submit(command.accountId(), command).get(2, TimeUnit.SECONDS);
      expected = gateway.fullView();
      assertEquals(2, gateway.executions("buyer", 0, 100, 6).size());
      assertEquals(List.of(expected.executions().get(1)), gateway.executions("buyer", 1, 1, 6));
      assertEquals(List.of(expected.executions().get(0)), gateway.executions("seller1", 0, 100, 6));
      assertEquals(List.of(expected.executions().get(1)), gateway.executions("seller2", 0, 100, 6));
      assertTrue(gateway.executions("buyer", 0, 100, 5).isEmpty());
      assertTrue(gateway.executions("stranger", 0, 100, 6).isEmpty());
      gateway.snapshot();
    }
    try (Gateway recovered = new Gateway(path, "run", 4, 10)) {
      assertEquals(expected, recovered.fullView());
      assertEquals(expected.executions(), recovered.executions("buyer", 0, 100, 6));
      for (Command command : commands) {
        assertEquals(
            expected.results().get(command.key()),
            recovered.result(command.accountId(), command.clientCommandId()));
        assertEquals(
            expected.results().get(command.key()),
            recovered.submit(command.accountId(), command).get(5, TimeUnit.SECONDS));
      }
      assertNull(recovered.result("stranger", "buy"));
      assertEquals(6, recovered.view().sequence());
    }
  }

  @Test
  void replayFailureRemainsPrimaryWhenJournalCloseAlsoFails() throws Exception {
    Path path = dir.resolve("duplicate-replay");
    try (Journal journal = new Journal(path, "run")) {
      journal.appendAndForce(1, init("duplicate"));
      journal.appendAndForce(2, init("duplicate"));
    }
    var cleanup = new java.io.IOException("injected close failure");
    var failure =
        assertThrows(
            java.io.IOException.class,
            () ->
                new Gateway(
                    path,
                    "run",
                    2,
                    10,
                    boundary -> {
                      if (boundary.equals("BEFORE_CLOSE")) throw cleanup;
                    }));
    assertTrue(failure.getMessage().contains("invalid replay"));
    assertInstanceOf(IllegalStateException.class, failure.getCause());
    assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
    try (Journal unlocked = new Journal(path, "run")) {
      assertEquals(2, unlocked.entries().size());
    }
  }

  @Test
  void checksumValidCheckpointStateAndResultTamperingFallsBackToReplay() throws Exception {
    Path path = dir.resolve("checkpoint");
    try (Gateway gateway = new Gateway(path, "run", 2, 10)) {
      gateway.submit("alice", init("first")).get(5, TimeUnit.SECONDS);
      gateway.snapshot();
    }
    var json = CommandJson.create();
    Path snapshot = path.resolveSibling("checkpoint.snapshot");
    var envelope = json.readValue(Files.readAllBytes(snapshot), Journal.SnapshotEnvelope.class);
    var original = json.readValue(envelope.payload(), Journal.Snapshot.class);
    Checkpoint good = original.checkpoint();
    Map<String, Result> wrongResults = new HashMap<>(good.view().results());
    Result result = wrongResults.get("alice:first");
    wrongResults.put(
        "alice:first",
        new Result(result.sequence(), result.code(), "0".repeat(64), result.executions()));
    for (Checkpoint corrupt :
        List.of(
            new Checkpoint(
                new View(
                    1,
                    Map.of("alice", new Balance(-1, 100, 0, 0)),
                    List.of(),
                    List.of(),
                    good.view().results()),
                Set.of()),
            new Checkpoint(
                new View(
                    1,
                    Map.of("alice", new Balance(2000, 100, 0, 0)),
                    List.of(),
                    List.of(),
                    good.view().results()),
                Set.of()),
            new Checkpoint(
                new View(1, good.view().accounts(), List.of(), List.of(), wrongResults),
                Set.of()))) {
      String payload =
          json.writeValueAsString(
              new Journal.Snapshot(original.version(), 1, original.prefixDigest(), corrupt));
      Files.write(
          snapshot, json.writeValueAsBytes(new Journal.SnapshotEnvelope(payload, sha(payload))));
      try (Gateway recovered = new Gateway(path, "run", 2, 10)) {
        assertEquals(1000, recovered.view().accounts().get("alice").cash());
        assertEquals(good.view(), recovered.fullView());
      }
    }
    Files.write(snapshot, json.writeValueAsBytes(envelope));
    try (Gateway recovered = new Gateway(path, "run", 2, 10)) {
      assertEquals(good.view(), recovered.fullView());
    }
  }

  @Test
  void admissionCapOverloadAndIoFailure() throws Exception {
    CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
    try (Gateway g =
        new Gateway(
            dir.resolve("overload"),
            "run",
            1,
            5,
            b -> {
              if (b.equals("BEFORE_APPEND")) {
                at.countDown();
                try {
                  release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  throw new java.io.IOException(e);
                }
              }
            })) {
      var first = g.submit("alice", init("first"));
      assertTrue(at.await(5, TimeUnit.SECONDS));
      var rejected = g.submit("alice", init("second"));
      assertThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS));
      assertEquals(0, g.view().sequence());
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
    }
    try (Gateway g = new Gateway(dir.resolve("cap"), "run", 2, 1)) {
      g.submit("alice", init("first")).get(5, TimeUnit.SECONDS);
      assertThrows(
          ExecutionException.class,
          () -> g.submit("alice", init("second")).get(5, TimeUnit.SECONDS));
    }
    Path failed = dir.resolve("failed");
    try (Gateway g =
        new Gateway(
            failed,
            "run",
            2,
            10,
            b -> {
              if (b.equals("AFTER_FORCE"))
                throw new java.io.IOException("injected storage failure");
            })) {
      assertThrows(
          ExecutionException.class,
          () -> g.submit("alice", init("first")).get(5, TimeUnit.SECONDS));
      assertFalse(g.healthy());
      assertThrows(IllegalStateException.class, g::snapshot);
      assertEquals(0, g.view().sequence());
      assertThrows(
          ExecutionException.class,
          () -> g.submit("alice", init("second")).get(5, TimeUnit.SECONDS));
    }
    try (Gateway recovered = new Gateway(failed, "run", 2, 10)) {
      assertEquals(1, recovered.view().sequence());
      assertEquals(
          "INITIALIZED", recovered.submit("alice", init("first")).get(5, TimeUnit.SECONDS).code());
    }
  }

  @Test
  void equalV1HashesCannotHideDifferentPendingCommittedOrRecoveredPayloads() throws Exception {
    Path path = dir.resolve("exact-authority");
    Command original = init("collision");
    Command changed =
        new Command(
            original.clientCommandId(),
            original.accountId(),
            original.type(),
            "null",
            original.side(),
            original.priceTicks(),
            original.quantityLots(),
            original.timeInForce(),
            original.postOnly(),
            original.initialCash(),
            original.initialLots());
    assertNotEquals(original, changed);
    assertEquals(original.hash(), changed.hash());
    CountDownLatch at = new CountDownLatch(1), release = new CountDownLatch(1);
    var once = new java.util.concurrent.atomic.AtomicBoolean();
    try (Gateway gateway =
        new Gateway(
            path,
            "run",
            2,
            10,
            boundary -> {
              if (boundary.equals("AFTER_FORCE") && once.compareAndSet(false, true)) {
                at.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new java.io.IOException("test deadline");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(interrupted);
                }
              }
            })) {
      var first = gateway.submit("alice", original);
      assertTrue(at.await(5, TimeUnit.SECONDS));
      assertEquals("CONFLICT", gateway.submit("alice", changed).get(5, TimeUnit.SECONDS).code());
      var exact = gateway.submit("alice", original);
      assertFalse(exact.isDone());
      release.countDown();
      assertEquals(first.get(2, TimeUnit.SECONDS), exact.get(2, TimeUnit.SECONDS));
      assertEquals("CONFLICT", gateway.submit("alice", changed).get(5, TimeUnit.SECONDS).code());
      assertEquals(
          "INITIALIZED", gateway.submit("alice", original).get(5, TimeUnit.SECONDS).code());
      gateway.snapshot();
    } finally {
      release.countDown();
    }
    try (Journal journal = new Journal(path, "run")) {
      assertTrue(journal.validSnapshot());
      assertEquals(1, journal.entries().size());
      assertEquals(original, journal.entries().getFirst().command());
    }
    try (Gateway recovered = new Gateway(path, "run", 2, 10)) {
      assertEquals("CONFLICT", recovered.submit("alice", changed).get(5, TimeUnit.SECONDS).code());
      assertEquals(1, recovered.submit("alice", original).get(5, TimeUnit.SECONDS).sequence());
      assertEquals("INITIALIZED", recovered.result("alice", "collision").code());
      assertEquals(1, recovered.view().sequence());
    }
  }
}
