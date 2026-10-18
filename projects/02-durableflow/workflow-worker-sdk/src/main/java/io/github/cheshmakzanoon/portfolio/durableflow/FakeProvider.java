package io.github.cheshmakzanoon.portfolio.durableflow;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent append-only effect log survives provider restart; it is not engine history. */
public final class FakeProvider implements AutoCloseable {
  private final HttpServer server;
  private final Path log;
  private final Map<String, String> effects = new HashMap<>();
  private final Map<String, String> parameters = new HashMap<>();
  private final Set<String> firstRejected = new HashSet<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(8);

  public FakeProvider(int port, Path log) throws IOException {
    this.log = log;
    Files.createDirectories(log.toAbsolutePath().getParent());
    if (Files.exists(log))
      try (var lines = Files.lines(log)) {
        var iterator = lines.iterator();
        while (iterator.hasNext()) {
          String[] parts = iterator.next().split("\\t", 4);
          if (parts.length == 4 && parts[0].equals("EFFECT")) {
            effects.put(parts[1], parts[3]);
            parameters.put(parts[1], parts[2]);
          }
        }
      }
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
    server.setExecutor(executor);
    server.createContext("/effects", this::handle);
    server.createContext(
        "/health",
        x -> {
          byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
          x.sendResponseHeaders(200, b.length);
          x.getResponseBody().write(b);
          x.close();
        });
    server.start();
  }

  private synchronized void record(String text) throws IOException {
    Files.writeString(log, text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private void handle(HttpExchange x) throws IOException {
    if (!x.getRequestMethod().equals("POST")) {
      respond(x, 405, "method");
      return;
    }
    String key = x.getRequestHeaders().getFirst("Idempotency-Key"),
        safety = x.getRequestHeaders().getFirst("X-Safety"),
        mode = x.getRequestHeaders().getFirst("X-Failure-Mode");
    byte[] body = x.getRequestBody().readNBytes(4097);
    if (body.length > 4096 || key == null || !key.matches("[a-zA-Z0-9:-]{1,128}")) {
      respond(x, 400, "bounds");
      return;
    }
    String parameter = Base64.getEncoder().encodeToString(body);
    String result;
    synchronized (this) {
      record("CALL\t" + key + "\t" + parameter + "\t" + mode);
      if ("reject".equals(mode) || ("transient".equals(mode) && firstRejected.add(key))) {
        respond(x, 503, "rejected before effect");
        return;
      }
      if ("IDEMPOTENT_WITH_KEY".equals(safety) && effects.containsKey(key)) {
        if (!parameters.get(key).equals(parameter)) {
          respond(x, 409, "parameter conflict");
          return;
        }
        result = effects.get(key);
      } else {
        result = "effect-" + UUID.randomUUID();
        record("EFFECT\t" + key + "\t" + parameter + "\t" + result);
        effects.put(key, result);
        parameters.put(key, parameter);
      }
    }
    if ("disconnect".equals(mode)) {
      x.close();
      return;
    }
    if ("delay".equals(mode))
      try {
        Thread.sleep(3500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    respond(x, 200, result); // duplicate mode deliberately returns the same recorded receipt.
  }

  private static void respond(HttpExchange x, int status, String text) throws IOException {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    x.sendResponseHeaders(status, body.length);
    try (OutputStream out = x.getResponseBody()) {
      out.write(body);
    } finally {
      x.close();
    }
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public synchronized long effectCount() throws IOException {
    if (!Files.exists(log)) return 0;
    try (var lines = Files.lines(log)) {
      return lines.filter(l -> l.startsWith("EFFECT\t")).count();
    }
  }

  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
