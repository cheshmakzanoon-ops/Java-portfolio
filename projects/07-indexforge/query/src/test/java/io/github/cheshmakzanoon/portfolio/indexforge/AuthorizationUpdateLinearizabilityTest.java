package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A later authorization version must never be replaced with an earlier version. These schedules
 * exercise real concurrent updates, not a mocked security store or a synthetic publication order.
 */
class AuthorizationUpdateLinearizabilityTest {
  @Test
  void laterSuccessfulUpdateRevokesEarlierHandleAndRetainsItsExactRepositories() {
    AuthorizationFilter filter = new AuthorizationFilter();
    var first = filter.update("alice", "tenant", Set.of("public", "private"), false);
    assertTrue(filter.current(first));
    var later = filter.update("alice", "tenant", Set.of("public"), false);
    assertTrue(later.version() > first.version());
    assertFalse(filter.current(first));
    assertTrue(filter.current(later));
    assertEquals(Set.of("public"), filter.current("alice").repositories());
    assertThrows(SecurityException.class, () -> filter.require(first, "tenant", "private"));
    assertThrows(SecurityException.class, () -> filter.require(later, "tenant", "private"));
    assertDoesNotThrow(() -> filter.require(later, "tenant", "public"));
  }

  @Test
  void validationFailureLeavesPublishedScopeAndRevisionIntact() {
    AuthorizationFilter filter = new AuthorizationFilter();
    var original = filter.update("alice", "tenant", Set.of("public"), false);
    for (String invalidTenant : List.of("", "../outside", "tenant with spaces")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> filter.update("alice", invalidTenant, Set.of("private"), true));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> filter.update("alice", "tenant", Set.of("invalid/repository"), true));
    assertTrue(filter.current(original));
    assertEquals(original, filter.current("alice"));
    var next = filter.update("alice", "tenant", Set.of("public"), false);
    assertEquals(original.version() + 1, next.version());
  }

  @Test
  void competingWritersNeverPublishARevisionOlderThanTheHighestIssuedRevision()
      throws Exception {
    AuthorizationFilter filter = new AuthorizationFilter();
    int writers = 12;
    int iterations = 350;
    CountDownLatch begin = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    ConcurrentLinkedQueue<AuthorizationFilter.Access> outcomes = new ConcurrentLinkedQueue<>();
    AtomicReference<Throwable> failed = new AtomicReference<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int writer = 0; writer < writers; writer++) {
        final int identity = writer;
        futures.add(
            executor.submit(
                () -> {
                  try {
                    begin.await();
                    for (int i = 0; i < iterations; i++) {
                      AuthorizationFilter.Access updated =
                          filter.update(
                              "target",
                              "tenant",
                              Set.of("scope" + identity, "shared"),
                              (i & 1) == 0);
                      outcomes.add(updated);
                      if ((i & 15) == 0) Thread.yield();
                    }
                  } catch (Throwable error) {
                    failed.compareAndSet(null, error);
                  }
                }));
      }
      begin.countDown();
      for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
      assertNull(failed.get());
      assertEquals(writers * iterations, outcomes.size());
      long highest =
          outcomes.stream().mapToLong(AuthorizationFilter.Access::version).max().orElseThrow();
      assertEquals(highest, filter.current("target").version());
      var current = filter.current("target");
      assertTrue(filter.current(current));
      for (var previous : outcomes) {
        if (previous.version() != highest) assertFalse(filter.current(previous));
      }
    } finally {
      begin.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void observedVersionsNeverDecreaseWhileWritersRaceWithReaders() throws Exception {
    AuthorizationFilter filter = new AuthorizationFilter();
    filter.update("target", "tenant", Set.of("seed"), false);
    int writers = 8;
    int updates = 500;
    ExecutorService executor = Executors.newFixedThreadPool(writers + 1);
    CountDownLatch begin = new CountDownLatch(1);
    AtomicLong observations = new AtomicLong();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int writer = 0; writer < writers; writer++) {
        int index = writer;
        futures.add(
            executor.submit(
                () -> {
                  try {
                    begin.await();
                    for (int i = 0; i < updates; i++) {
                      filter.update("target", "tenant", Set.of("scope" + index), false);
                      if ((i & 7) == 0) Thread.yield();
                    }
                  } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                  }
                }));
      }
      Future<?> reader =
          executor.submit(
              () -> {
                try {
                  begin.await();
                  long last = 0;
                  for (int i = 0; i < 15000; i++) {
                    long next = filter.current("target").version();
                    if (next < last) {
                      failure.compareAndSet(
                          null,
                          new AssertionError("scope rollback from " + last + " to " + next));
                      return;
                    }
                    last = next;
                    observations.incrementAndGet();
                  }
                } catch (Throwable problem) {
                  failure.compareAndSet(null, problem);
                }
              });
      begin.countDown();
      reader.get(20, TimeUnit.SECONDS);
      for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
      assertNull(failure.get());
      assertTrue(observations.get() > 0);
      assertEquals(1L + writers * updates, filter.current("target").version());
    } finally {
      begin.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void unrelatedPrincipalsRemainIndependentButAllRevisionsAreUnique() throws Exception {
    AuthorizationFilter filter = new AuthorizationFilter();
    int writers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    try {
      List<Callable<List<AuthorizationFilter.Access>>> tasks = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        int identity = i;
        tasks.add(
            () -> {
              String principal = "user" + identity;
              List<AuthorizationFilter.Access> versions = new ArrayList<>();
              for (int n = 0; n < 150; n++) {
                var a = filter.update(principal, "tenant", Set.of("r" + identity), false);
                assertTrue(versions.isEmpty() || a.version() > versions.getLast().version());
                versions.add(a);
              }
              return versions;
            });
      }
      List<Future<List<AuthorizationFilter.Access>>> futures = executor.invokeAll(tasks);
      Set<Long> revisions = new HashSet<>();
      for (int i = 0; i < writers; i++) {
        List<AuthorizationFilter.Access> versions = futures.get(i).get(20, TimeUnit.SECONDS);
        for (var version : versions) assertTrue(revisions.add(version.version()));
        assertEquals(versions.getLast(), filter.current("user" + i));
      }
      assertEquals(writers * 150, revisions.size());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
