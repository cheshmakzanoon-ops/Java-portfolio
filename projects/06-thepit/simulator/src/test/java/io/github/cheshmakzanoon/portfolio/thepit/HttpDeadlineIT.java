package io.github.cheshmakzanoon.portfolio.thepit;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpDeadlineIT {
  @TempDir Path directory;

  @Test
  void incompleteUploadsReleaseAllHandlerPermitsWithinTheOwnedServerDeadline() throws Exception {
    Path journal = directory.resolve("http.log"), output = directory.resolve("http.out");
    Process server =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                HttpApi.class.getName(),
                journal.toString(),
                "deadline",
                "0",
                "trusted-local-demo")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    List<Socket> clients = new ArrayList<>();
    try {
      int port = awaitPort(server, output);
      long start = System.nanoTime();
      for (int index = 0; index < 4; index++) {
        Socket socket = new Socket();
        clients.add(socket);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setSoTimeout(12_000);
        socket
            .getOutputStream()
            .write(
                ("POST /commands HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                        + "X-Demo-Account: alice\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      }
      for (Socket client : clients) {
        try {
          client.getInputStream().read();
        } catch (SocketException closedByServer) {
          /* A reset also refuses the incomplete request. */
        }
      }
      assertTrue(
          System.nanoTime() - start < Duration.ofSeconds(12).toNanos(),
          "incomplete request exceeded its deadline");
      var response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(3))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/commands"))
                      .timeout(Duration.ofSeconds(4))
                      .header("X-Demo-Account", "alice")
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              "{\"clientCommandId\":\"init\",\"accountId\":\"alice\",\"type\":\"INIT\",\"priceTicks\":0,\"quantityLots\":0,\"postOnly\":false,\"initialCash\":1000,\"initialLots\":10}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(response.body().contains("INITIALIZED"));
    } finally {
      for (Socket client : clients) client.close();
      server.destroy();
      if (!server.waitFor(5, TimeUnit.SECONDS)) {
        server.destroyForcibly();
        assertTrue(server.waitFor(5, TimeUnit.SECONDS), "owned HTTP process did not terminate");
      }
    }
    try (Journal recovered = new Journal(journal, "deadline")) {
      assertEquals(
          1, recovered.entries().size(), "incomplete requests must not allocate durable sequences");
    }
  }

  private int awaitPort(Process process, Path output) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Pattern ready = Pattern.compile("listening on loopback:(\\d+) mode=trusted-local-demo");
    while (process.isAlive() && System.nanoTime() < deadline) {
      var match = ready.matcher(Files.readString(output));
      if (match.find()) return Integer.parseInt(match.group(1));
      Thread.sleep(10);
    }
    throw new AssertionError("owned HTTP process did not start: " + Files.readString(output));
  }
}
