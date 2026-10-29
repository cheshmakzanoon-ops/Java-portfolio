package io.github.cheshmakzanoon.portfolio.backtrace.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObserverStressTest {
  @TempDir Path directory;

  @Test
  void cleanupStopsBothHostThreadKindsEvenWhenTheCallerIsInterrupted() throws Exception {
    AtomicBoolean running = new AtomicBoolean(true), interruptPreserved = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
    Runnable host =
        () -> {
          entered.countDown();
          while (release.getCount() > 0) {
            try {
              release.await();
            } catch (InterruptedException ignored) {
            }
          }
        };
    List<Thread> hosts = List.of(Thread.ofPlatform().start(host), Thread.ofVirtual().start(host));
    AtomicReference<Throwable> observed = new AtomicReference<>();
    Thread closer = null;
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      ObserverStress.stopHosts(hosts, running);
                    } catch (Throwable error) {
                      observed.set(error);
                      interruptPreserved.set(Thread.currentThread().isInterrupted());
                    }
                  });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (closer.isAlive()
          && closer.getState() != Thread.State.TIMED_WAITING
          && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(Thread.State.TIMED_WAITING, closer.getState());
      closer.interrupt();
      // Release the hosts only after the closer's join has actually thrown. Releasing straight
      // away lets the hosts finish before a delayed interrupt reaches a blocking call, so the
      // closer could then exit with the flag still set and no InterruptedException to report.
      long consumed = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (closer.isInterrupted() && System.nanoTime() < consumed) Thread.onSpinWait();
      assertFalse(closer.isInterrupted(), "the closer never reached a blocking call");
      release.countDown();
      closer.join(2000);
      assertFalse(closer.isAlive());
      assertFalse(running.get());
      assertTrue(hosts.stream().noneMatch(Thread::isAlive));
      assertInstanceOf(InterruptedException.class, observed.get());
      assertTrue(interruptPreserved.get());
    } finally {
      release.countDown();
      hosts.forEach(Thread::interrupt);
      for (Thread thread : hosts) thread.join(2000);
      if (closer != null) closer.join(2000);
    }
  }

  @Test
  void invalidStressDurationsCannotCreateOutputOrStartCollection() {
    for (String value : List.of("0", "3601")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ObserverStress.main(new String[] {value, directory.resolve("observer").toString()}));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              BoundedStress.main(
                  new String[] {value, directory.resolve("bounded.json").toString()}));
    }
    assertFalse(Files.exists(directory.resolve("observer")));
    assertFalse(Files.exists(directory.resolve("bounded.json")));
  }
}
