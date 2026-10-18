package io.github.cheshmakzanoon.portfolio.quorumkv;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessFaultIT {
  @TempDir Path directory;

  @Test
  void actualTlsThreeProcessPartitionSnapshotAndCrashRecovery() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    if (port > 65532) port = 18300;
    ClusterTool.demo(directory, port);
    String result = Files.readString(directory.resolve("demo-result.json"));
    assertTrue(result.contains("\"history\":\"PASS\""));
    assertTrue(result.contains("\"dedupRestart\":\"OK\""));
  }

  @Test
  void independentControllerStopsOnlyMatchingChildrenWithLongClasspath() throws Exception {
    String original = System.getProperty("java.class.path");
    int port;
    try (ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    if (port > 65532) port = 18320;
    System.setProperty(
        "java.class.path", original + java.io.File.pathSeparator + "nonexistent/".repeat(1024));
    try (ProcessCluster cluster = new ProcessCluster(directory, port)) {
      cluster.start();
      try (ProcessCluster independent = new ProcessCluster(directory, port)) {
        independent.kill(0);
      }
      for (int id = 0; id < 3; id++)
        assertFalse(Files.exists(directory.resolve("node-" + id + ".pid")));
      // A malicious/stale PID file must not authorize terminating this test process.
      Files.writeString(
          directory.resolve("node-0.pid"), Long.toString(ProcessHandle.current().pid()));
      try {
        assertThrows(java.io.IOException.class, () -> cluster.kill(0));
      } finally {
        Files.deleteIfExists(directory.resolve("node-0.pid"));
      }
    } finally {
      System.setProperty("java.class.path", original);
    }
  }

  @Test
  void detachedLauncherCanStopEveryNodeAndRestart() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    if (port > 65532) port = 18340;
    Path data = directory.resolve("detached");
    Process launcher =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                ClusterTool.class.getName(),
                "up",
                data.toString(),
                Integer.toString(port))
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("launcher.log").toFile())
            .start();
    try {
      assertTrue(launcher.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          0,
          launcher.exitValue(),
          () -> {
            try {
              return Files.readString(directory.resolve("launcher.log"));
            } catch (java.io.IOException error) {
              return error.toString();
            }
          });
    } finally {
      if (launcher.isAlive()) launcher.destroyForcibly();
      try (ProcessCluster controller = new ProcessCluster(data, port)) {
        controller.close();
      }
    }
    for (int id = 0; id < 3; id++) assertFalse(Files.exists(data.resolve("node-" + id + ".pid")));
    try (ProcessCluster restarted = new ProcessCluster(data, port)) {
      restarted.start();
      try (KvClient client = new KvClient(restarted.settings(), "restart-health")) {
        assertTrue(ClusterTool.leader(client, -1) >= 0);
      }
    }
  }
}
