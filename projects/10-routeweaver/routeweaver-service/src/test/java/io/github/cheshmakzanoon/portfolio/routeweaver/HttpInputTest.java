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

  // Deliberately a lite configuration, not component-scanned by the full PostgreSQL test app.
  @EnableAutoConfiguration(
      excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
  @Import({Api.class, Security.class})
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
                    throw new AssertionError("invalid input must be rejected before persistence");
                  });
      return new Store(unreachable);
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
      }
      var unauthenticated =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/plans/p"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(401, unauthenticated.statusCode());
      String original = Json.write(PostgresIT.problem("demo"));
      var invalidBodies = new java.util.ArrayList<String>();
      for (String invalid : java.util.List.of("a/b", "..", "a b")) {
        invalidBodies.add(original.replace("\"id\":\"p\"", "\"id\":\"" + invalid + "\""));
      }
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
      assertFalse(DATABASE_TOUCHED.get());
    }
  }
}
