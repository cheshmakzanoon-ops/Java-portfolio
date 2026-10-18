package io.github.cheshmakzanoon.portfolio.matchcore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashIT {
  @TempDir Path dir;

  @Test
  void sigkillEveryProcessBoundary() throws Exception {
    for (String boundary :
        new String[] {"BEFORE_APPEND", "DURING_FRAME", "AFTER_FORCE", "AFTER_APPLY"}) {
      Path journal = dir.resolve(boundary + ".log"), signal = dir.resolve(boundary + ".signal");
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xmx128m",
                  "-cp",
                  System.getProperty("java.class.path"),
                  CrashProcess.class.getName(),
                  journal.toString(),
                  signal.toString(),
                  boundary)
              .redirectErrorStream(true)
              .redirectOutput(dir.resolve(boundary + ".out").toFile())
              .start();
      try {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline)
          Thread.sleep(10);
        assertTrue(
            Files.exists(signal),
            () -> "child did not reach " + boundary + ": " + read(dir.resolve(boundary + ".out")));
        assertThrows(
            java.io.IOException.class,
            () -> new Journal(journal, "demo"),
            "second process must not acquire writer lock");
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      int durable = boundary.equals("AFTER_FORCE") || boundary.equals("AFTER_APPLY") ? 12 : 11;
      ReferenceBook reference = new ReferenceBook();
      for (var c : Demo.fixture().subList(0, durable)) reference.apply(c);
      try (Gateway recovered = new Gateway(journal, "demo", 64, 1000)) {
        assertEquals(durable, recovered.fullView().sequence());
        assertEquals(reference.view(), recovered.fullView());
        assertEquals(
            "CANCELED",
            recovered.submit("carol", Demo.fixture().getLast()).get(5, TimeUnit.SECONDS).code());
        assertEquals(12, recovered.fullView().sequence());
      }
    }
  }

  private String read(Path p) {
    try {
      return Files.readString(p);
    } catch (Exception e) {
      return e.toString();
    }
  }
}
