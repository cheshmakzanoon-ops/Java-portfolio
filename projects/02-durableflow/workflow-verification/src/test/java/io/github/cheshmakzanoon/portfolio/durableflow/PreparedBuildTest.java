package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PreparedBuildTest {
  @Test
  void preparedRuntimeAcceptsOnlyMatchingSourceArtifactsAndSuccessfulReceipt() throws Exception {
    Process process =
        new ProcessBuilder("python3", Path.of("..", "scripts", "test-prepared-build.py").toString())
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "preparation CLI regressions timed out");
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains("Ran 9 tests"), output);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "preparation probe failed to stop");
      }
    }
  }
}
