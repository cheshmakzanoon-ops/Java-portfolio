package io.github.cheshmakzanoon.portfolio.routeweaver;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Actual authenticated HTTP rejection must occur before a database connection is opened. */
class HttpInputTest {
  static final AtomicBoolean DATABASE_TOUCHED = new AtomicBoolean();
  static final AtomicBoolean INTERNAL_FAULT = new AtomicBoolean();

  // Deliberately a lite configuration, not component-scanned by the full PostgreSQL test app.
  @EnableAutoConfiguration(
      excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
  @Import({Api.class, ApiErrors.class, Security.class})
  public static class RejectionApplication {
    @Bean
    Store store() {
      DataSource unreachable =
          (DataSource)
              java.lang.reflect.Proxy.newProxyInstance(
                  DataSource.class.getClassLoader(),
                  new Class<?>[] {DataSource.class},
                  (proxy, method, args) -> {
                    DATABASE_TOUCHED.set(true);
                    if (INTERNAL_FAULT.get())
                      throw new NullPointerException("private fault canary");
                    throw new AssertionError("invalid input must be rejected before persistence");
                  });
      return new Store(unreachable);
    }
  }

  @Test
  void trustedLocalModeRejectsCrossOriginMutationsAndReboundHostsBeforePersistence()
      throws Exception {
    DATABASE_TOUCHED.set(false);
    try (var context =
        new SpringApplicationBuilder(RejectionApplication.class)
            .run("--server.port=0", "--server.address=127.0.0.1", "--routeweaver.auth-mode=dev")) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      String base = "http://127.0.0.1:" + port;
      HttpClient client = HttpClient.newHttpClient();
      for (String origin :
          java.util.List.of(
              "https://unrelated.example", "null", base + "/", base + "@unrelated.example")) {
        var response =
            client.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/demo/reset"))
                    .header("Origin", origin)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode(), origin);
        assertTrue(response.body().contains("UNTRUSTED_LOCAL_REQUEST"));
      }
      for (var headers : java.util.List.of(Map.of("Origin", base), Map.<String, String>of())) {
        var request = HttpRequest.newBuilder(URI.create(base + "/health"));
        headers.forEach(request::header);
        assertEquals(
            200,
            client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      }
      assertEquals(
          403,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(base + "/health"))
                      .header("Sec-Fetch-Site", "cross-site")
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode());
      try (var socket = new java.net.Socket("127.0.0.1", port)) {
        socket.setSoTimeout(5000);
        socket
            .getOutputStream()
            .write(
                ("GET /health HTTP/1.1\r\nHost: rebound.example:"
                        + port
                        + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(reader.readLine().contains("403"));
      }
      assertFalse(DATABASE_TOUCHED.get());
    }
  }

  @Test
  void unexpectedNullFaultIsAServerIncidentRatherThanAnInputRejection() throws Exception {
    INTERNAL_FAULT.set(true);
    try (var context =
        new SpringApplicationBuilder(RejectionApplication.class)
            .run("--server.port=0", "--server.address=127.0.0.1", "--routeweaver.auth-mode=dev")) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/plans/p"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(500, response.statusCode());
      assertTrue(response.body().contains("INTERNAL_ERROR"));
      assertFalse(response.body().contains("private fault canary"));
      Map<?, ?> body = Json.read(response.body(), Map.class);
      assertNotNull(java.util.UUID.fromString((String) body.get("incident")));
    } finally {
      INTERNAL_FAULT.set(false);
    }
  }

  @Test
  void authenticatedPostRejectsInvalidIdsAndScalarTypesWith400BeforePersistence() throws Exception {
    DATABASE_TOUCHED.set(false);
    try (LocalIssuer issuer = new LocalIssuer();
        var context =
            new SpringApplicationBuilder(RejectionApplication.class)
                .run(
                    "--server.port=0",
                    "--server.address=127.0.0.1",
                    "--routeweaver.auth-mode=protected",
                    "--routeweaver.issuer=" + issuer.issuer)) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      HttpClient client = HttpClient.newHttpClient();
      for (String shell : java.util.List.of("/", "/index.html")) {
        var shellResponse =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + shell))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, shellResponse.statusCode());
        assertTrue(shellResponse.body().contains("RouteWeaver dispatch"));
        assertTrue(shellResponse.body().contains("src=\"/app.js\""));
        String policy = shellResponse.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(policy.contains("default-src 'self'"));
        assertFalse(policy.contains("unsafe-inline"));
      }
      for (String asset : java.util.List.of("/app.js", "/styles.css")) {
        var assetResponse =
            client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + asset))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, assetResponse.statusCode());
        assertFalse(assetResponse.body().isBlank());
      }
      var unauthenticated =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/plans/p"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauthenticated.statusCode());
      // Headers alone must be denied before the body filter allocates or asks for upload bytes.
      try (var socket = new java.net.Socket("127.0.0.1", port)) {
        socket.setSoTimeout(5000);
        socket
            .getOutputStream()
            .write(
                ("POST /v1/problems HTTP/1.1\r\nHost: 127.0.0.1:"
                        + port
                        + "\r\nContent-Length: "
                        + (Security.BODY_LIMIT + 1)
                        + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var reader =
            new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        String status = reader.readLine();
        if (status.contains("100")) {
          while (!reader.readLine().isEmpty()) {}
          status = reader.readLine();
        }
        assertTrue(status.contains("401"), status);
      }
      String original = Json.write(PostgresIT.problem("demo"));
      var invalidBodies = new java.util.ArrayList<String>();
      for (String invalid : java.util.List.of("a/b", "..", "a b")) {
        invalidBodies.add(original.replace("\"id\":\"p\"", "\"id\":\"" + invalid + "\""));
      }
      invalidBodies.add("null");
      invalidBodies.add(original.replace("\"grams\":1000", "\"grams\":1000.5"));
      invalidBodies.add(original.replace("\"grams\":1000", "\"grams\":\"1000\""));
      invalidBodies.add(original.replace("\"seconds\":10", "\"seconds\":10.5"));
      invalidBodies.add(original.replace("\"version\":1,", "\"version\":1.5,"));
      invalidBodies.add(original.replace("\"failed\":false", "\"failed\":null"));
      invalidBodies.add(original.replace("\"id\":\"p\"", "\"id\":42"));
      for (String body : invalidBodies) {
        assertNotEquals(original, body);
        var request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/problems"))
                .header("Authorization", "Bearer " + issuer.token(Map.of()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), response.body());
      }
      for (var requestBody :
          java.util.List.of(
              Map.entry("publish", "{}"),
              Map.entry(
                  "publish", "{\"solveJobId\":null,\"expectedVersion\":1,\"expectedRevision\":0}"),
              Map.entry(
                  "publish",
                  "{\"solveJobId\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":0,\"expectedRevision\":0}"),
              Map.entry("replan", "{}"),
              Map.entry(
                  "replan",
                  "{\"expectedVersion\":1,\"expectedRevision\":0,\"planningInstant\":\"2026-10-20T12:00:00Z\",\"observations\":null,\"urgent\":[]}"),
              Map.entry(
                  "replan",
                  "{\"expectedVersion\":1,\"expectedRevision\":0,\"planningInstant\":\"2026-10-20T12:00:00.5Z\",\"observations\":[],\"urgent\":[]}"))) {
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://127.0.0.1:" + port + "/v1/plans/p/" + requestBody.getKey()))
                    .header("Authorization", "Bearer " + issuer.token(Map.of()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.getValue()))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), requestBody.toString());
      }
      assertFalse(DATABASE_TOUCHED.get());
    }
  }
}
