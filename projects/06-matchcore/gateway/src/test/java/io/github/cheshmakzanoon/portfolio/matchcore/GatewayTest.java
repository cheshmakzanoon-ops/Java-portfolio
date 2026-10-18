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
        for (var retry : retries) assertSame(first, retry.get(3, TimeUnit.SECONDS));
      }
      assertEquals(
          "CONFLICT",
          g.submit(
                  "alice",
                  new Command(
                      "first", "alice", Type.INIT, null, null, 0, 0, null, false, 2000, 100))
              .get()
              .code());
      assertThrows(SecurityException.class, () -> g.submit("bob", c));
      assertThrows(TimeoutException.class, () -> g.read(1, Duration.ofMillis(10)));
      release.countDown();
      assertEquals(1, first.get(5, TimeUnit.SECONDS).sequence());
      assertEquals(1, g.read(1, Duration.ofSeconds(1)).sequence());
      assertEquals(1, g.submit("alice", c).get().sequence());
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
      assertThrows(ExecutionException.class, () -> rejected.get());
      assertEquals(0, g.view().sequence());
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
    }
    try (Gateway g = new Gateway(dir.resolve("cap"), "run", 2, 1)) {
      g.submit("alice", init("first")).get();
      assertThrows(ExecutionException.class, () -> g.submit("alice", init("second")).get());
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
      assertEquals(0, g.view().sequence());
      assertThrows(ExecutionException.class, () -> g.submit("alice", init("second")).get());
    }
    try (Gateway recovered = new Gateway(failed, "run", 2, 10)) {
      assertEquals(1, recovered.view().sequence());
      assertEquals("INITIALIZED", recovered.submit("alice", init("first")).get().code());
    }
  }
}
