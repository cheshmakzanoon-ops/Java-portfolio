package io.github.cheshmakzanoon.portfolio.backtrace.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {
  @TempDir Path directory;

  @Test
  void configurationIsByteBoundedAndRejectsMalformedUtf8BeforeInstallation() throws Exception {
    Path input = directory.resolve("config.yaml");
    String declaration = "allowPackages: [sample.application]\n";
    Files.writeString(input, declaration + "#" + "x".repeat(16384 - declaration.length() - 1));
    assertEquals(1, AgentConfig.read("config=" + input).allowPackages().size());
    Files.writeString(input, "x", StandardOpenOption.APPEND);
    assertThrows(java.io.IOException.class, () -> AgentConfig.read("config=" + input));
    Files.write(input, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(java.io.IOException.class, () -> AgentConfig.read("config=" + input));
  }
}
