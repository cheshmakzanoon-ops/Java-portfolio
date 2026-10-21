package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IssuerLifecycleTest {
  @TempDir Path root;

  @Test
  void failedTokenPersistenceReleasesTheAlreadyBoundIssuerPort() throws Exception {
    int port;
    try (var reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = reservation.getLocalPort();
    }
    Path notDirectory = Files.writeString(root.resolve("file"), "existing file");
    var env =
        new MockEnvironment()
            .withProperty("stacks.issuer-port", Integer.toString(port))
            .withProperty("stacks.data", notDirectory.toString());
    assertThrows(IOException.class, () -> new StacksApplication().localIssuer(env));
    try (var rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
      assertEquals(port, rebound.getLocalPort());
    }
  }
}
