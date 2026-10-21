package io.github.cheshmakzanoon.portfolio.byteguard.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationFailureTest {
  @TempDir Path temp;

  @Test
  void invalidRuleConfigurationFailsBeforeTargetParsingWithMachineReadableFailure()
      throws Exception {
    checkFailure("--rules", "{\"model\":\"byteguard-core\",\"model\":\"bad\"}", "rule model");
  }

  @Test
  void invalidBaselineFailsBeforeTargetParsingWithMachineReadableFailure() throws Exception {
    checkFailure("--baseline", "{\"runs\":[]}", "baseline");
  }

  private void checkFailure(String option, String configuration, String expectedDiagnostic)
      throws Exception {
    Path input = temp.resolve("Unparsed.class");
    byte[] original = new byte[] {0, 1, 2, 3};
    Files.write(input, original);
    Path config = temp.resolve("config.json");
    Files.writeString(config, configuration);
    Path output = temp.resolve("failure.sarif");
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exit =
        ByteGuard.execute(
            new String[] {
              "scan",
              "--input",
              input.toString(),
              option,
              config.toString(),
              "--out",
              output.toString()
            },
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(errors));
    assertEquals(2, exit);
    assertTrue(errors.toString().contains(expectedDiagnostic), errors::toString);
    var run = new ObjectMapper().readTree(output.toFile()).path("runs").get(0);
    assertFalse(run.path("invocations").get(0).path("executionSuccessful").booleanValue());
    assertFalse(run.path("properties").path("partial").booleanValue());
    assertEquals(
        "invalid input/configuration",
        run.path("properties").path("unfinishedFrontier").get(0).textValue());
    assertEquals(0, run.path("results").size());
    assertArrayEquals(original, Files.readAllBytes(input));
    assertEquals(configuration, Files.readString(config));
  }
}
