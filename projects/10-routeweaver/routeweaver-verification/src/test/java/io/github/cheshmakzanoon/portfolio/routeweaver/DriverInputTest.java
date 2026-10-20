package io.github.cheshmakzanoon.portfolio.routeweaver;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.junit.jupiter.api.Test;

class DriverInputTest {
  @Test
  void responseIsBoundedEvenWhenTheServerNeverEndsItsStream() throws Exception {
    assertEquals("fixture", Driver.boundedResponse(new ByteArrayInputStream("fixture".getBytes())));
    InputStream unending =
        new InputStream() {
          public int read() {
            return 'x';
          }

          public int read(byte[] target, int offset, int length) {
            java.util.Arrays.fill(target, offset, offset + length, (byte) 'x');
            return length;
          }
        };
    assertThrows(IOException.class, () -> Driver.boundedResponse(unending));
  }

  @Test
  void invalidFixtureParametersFailBeforeAllocatingAMatrix() {
    assertThrows(
        IllegalArgumentException.class, () -> Fixtures.problem(Integer.MAX_VALUE, 1, 42, "easy"));
    assertThrows(IllegalArgumentException.class, () -> Fixtures.problem(-1, 1, 42, "easy"));
    assertThrows(IllegalArgumentException.class, () -> Fixtures.problem(1, 0, 42, "easy"));
    assertThrows(IllegalArgumentException.class, () -> Fixtures.problem(1, 26, 42, "easy"));
    assertThrows(IllegalArgumentException.class, () -> Fixtures.problem(1, 1, 42, "typo"));
  }
}
