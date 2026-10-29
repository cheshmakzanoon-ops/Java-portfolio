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
            new String[] {"search", data.toString(), "t", "r", "a AND"},
            new String[] {"explain-query"},
            new String[] {"explain-query", "a", "b"},
            new String[] {"wave3-final", data.toString(), "fixtures", "out", "catalog"},
            new String[] {"up", data.toString(), "extra"})) {
      assertThrows(
          IllegalArgumentException.class,
          () -> StacksCli.main(arguments),
          Arrays.toString(arguments));
      assertFalse(Files.exists(data));
    }
  }

  @Test
  void explainQueryPrintsTheMeaningOrTheErrorPositionWithoutTouchingData() {
    java.io.PrintStream original = System.out;
    java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
    try {
      System.setOut(
          new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
      assertDoesNotThrow(() -> StacksCli.main(new String[] {"explain-query", "a b AND c"}));
      assertEquals(
          "query:     a b AND c\nparsed as: a OR (b AND c)\nnote:      Terms written next to each other are combined with OR; write AND to require all of them.\n",
          captured.toString(java.nio.charset.StandardCharsets.UTF_8));
      captured.reset();
      assertThrows(
          IllegalArgumentException.class,
          () -> StacksCli.main(new String[] {"explain-query", "a )"}));
      assertTrue(
          captured.toString(java.nio.charset.StandardCharsets.UTF_8).contains("^ position 2"));
      captured.reset();
      assertThrows(
          IllegalArgumentException.class,
          () -> StacksCli.main(new String[] {"explain-query", "bad\0path/../x:"}));
    } finally {
      System.setOut(original);
    }
    assertFalse(Files.exists(Path.of("a b AND c")), "the argument is a query, never a path");
  }
}
