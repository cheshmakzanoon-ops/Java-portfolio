package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderClientTest {
  @Test
  void responseLimitAppliesToContentLengthAndChunkedBodies() throws Exception {
    for (boolean chunked : List.of(false, true)) {
      HttpServer server = server();
      byte[] oversized = "x".repeat(4097).getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/effects",
          exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, chunked ? 0 : oversized.length);
            try (var body = exchange.getResponseBody()) {
              body.write(oversized);
            } finally {
              exchange.close();
            }
          });
      server.start();
      try (ProviderClient provider = new ProviderClient(endpoint(server))) {
        assertThrows(IOException.class, () -> call(provider));
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void redirectCannotBeReportedAsAnAcknowledgedEffectOrFollowed() throws Exception {
    HttpServer server = server();
    AtomicInteger redirected = new AtomicInteger();
    server.createContext(
        "/redirected",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/effects",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Location", "/redirected");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();
    try (ProviderClient provider = new ProviderClient(endpoint(server))) {
      assertThrows(IOException.class, () -> call(provider));
      assertEquals(0, redirected.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void receiptsUseStrictUtf8AndPreserveValidUnicode() throws Exception {
    HttpServer server = server();
    AtomicInteger requests = new AtomicInteger();
    String receipt = "receipt-\u03b1\ud83d\udce6";
    server.createContext(
        "/effects",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response =
              requests.getAndIncrement() == 0
                  ? receipt.getBytes(StandardCharsets.UTF_8)
                  : new byte[] {(byte) 0xc3, 0x28};
          exchange.sendResponseHeaders(200, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (ProviderClient provider = new ProviderClient(endpoint(server))) {
      assertEquals(receipt, call(provider));
      assertThrows(IOException.class, () -> call(provider));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void errorStatusAloneDoesNotProveTheProviderRefusedAnEffect() throws Exception {
    HttpServer server = server();
    AtomicInteger requests = new AtomicInteger();
    server.createContext(
        "/effects",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int request = requests.getAndIncrement();
          if (request > 0) exchange.getResponseHeaders().add("X-Effect-Outcome", "NOT_APPLIED");
          exchange.sendResponseHeaders(request == 2 ? 500 : 503, -1);
          exchange.close();
        });
    server.start();
    try (ProviderClient provider = new ProviderClient(endpoint(server))) {
      assertThrows(IOException.class, () -> call(provider));
      assertThrows(Worker.ProviderRejectedException.class, () -> call(provider));
      assertThrows(IOException.class, () -> call(provider));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void endpointsRejectEmbeddedCredentialsAndAmbiguousRoutingComponents() {
    assertThrows(IllegalArgumentException.class, () -> new ProviderClient(null));
    for (String invalid :
        List.of(
            "/effects",
            "ftp://localhost/effects",
            "http://user:password@localhost/effects",
            "http://localhost/effects?redirect=other",
            "http://localhost/effects#fragment",
            "http://localhost/other")) {
      assertThrows(IllegalArgumentException.class, () -> new ProviderClient(URI.create(invalid)));
    }
    try (ProviderClient ignored = new ProviderClient(URI.create("https://example.test/effects"))) {
      // Construction validates configuration without making a network request.
    }
  }

  private static String call(ProviderClient provider) throws Exception {
    return provider.execute(
        "instance:task", Definition.Safety.IDEMPOTENT_WITH_KEY, "normal", "reserve:{}");
  }

  private static HttpServer server() throws IOException {
    return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
  }

  private static URI endpoint(HttpServer server) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/effects");
  }
}
