package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliArgumentsTest {
  @TempDir Path root;

  @Test
  void malformedCommandsFailBeforeOpeningOrCreatingData() {
    Path data = root.resolve("absent");
    for (String[] arguments :
        List.of(
            new String[] {"unknown", data.toString(), "t", "r"},
            new String[] {"resume", data.toString(), "t", "r"},
            new String[] {"evaluate", data.toString()},
            new String[] {"benchmark", data.toString(), "out", "3", "0"},
            new String[] {"demo", data.toString(), "2147483648"},
            new String[] {"search", data.toString(), "t", "r", "("},
            new String[] {"wave3-final", data.toString(), "fixtures", "out", "catalog"},
            new String[] {"up", data.toString(), "extra"})) {
      assertThrows(
          IllegalArgumentException.class,
          () -> StacksCli.main(arguments),
          Arrays.toString(arguments));
      assertFalse(Files.exists(data));
    }
  }
}
