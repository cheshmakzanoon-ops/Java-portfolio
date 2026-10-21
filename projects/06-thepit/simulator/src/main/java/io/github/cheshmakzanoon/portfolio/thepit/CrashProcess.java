package io.github.cheshmakzanoon.portfolio.thepit;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Child process pauses at a selected boundary; the parent issues SIGKILL/destroyForcibly. */
public final class CrashProcess {
  private CrashProcess() {}

  public static void main(String[] args) throws Exception {
    Path journal = Path.of(args[0]), signal = Path.of(args[1]);
    String boundary = args[2];
    java.util.concurrent.atomic.AtomicBoolean armed =
        new java.util.concurrent.atomic.AtomicBoolean();
    try (Gateway gateway =
        new Gateway(
            journal,
            "demo",
            64,
            1000,
            b -> {
              if (armed.get() && b.equals(boundary)) {
                Files.writeString(signal, b);
                try {
                  new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException(e);
                }
              }
            })) {
      var commands = Demo.fixture();
      for (var c : commands.subList(0, 11))
        gateway.submit(c.accountId(), c).get(5, TimeUnit.SECONDS);
      armed.set(true);
      gateway.submit("carol", commands.getLast()).get(30, TimeUnit.SECONDS);
    }
  }
}
