package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP, real OIDC/JWKS validation: the explain route is a pure function behind the usual gate.
 */
class HttpQueryExplainIT {
  @TempDir Path dir;

  private static HttpResponse<String> post(
      HttpClient client, String base, String token, String body, String... headers)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + "/v1/query/explain"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void explainsValidAndInvalidQueriesBehindTheSameAuthenticationAsEveryRoute() throws Exception {
    SpringApplication app = new SpringApplication(StacksApplication.class);
    app.setDefaultProperties(
        Map.of(
            "server.address", "127.0.0.1",
            "server.port", "0",
            "spring.profiles.active", "dev",
            "stacks.issuer-port", "0",
            "stacks.data", dir.resolve("data").toString(),
            "stacks.sources", dir.resolve("sources").toString(),
            "spring.main.banner-mode", "off"));
    try (ConfigurableApplicationContext context = app.run()) {
      String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
      HttpClient client = HttpClient.newHttpClient();
      LocalOidcIssuer issuer = context.getBean(LocalOidcIssuer.class);
      String token = issuer.token("alice", "demo", Instant.now().plusSeconds(300), "stacks");
      JsonMapper json = JsonMapper.builder().build();

      var valid = post(client, base, token, "{\"text\":\"a b AND c\"}");
      assertEquals(200, valid.statusCode(), valid.body());
      JsonNode ok = json.readTree(valid.body());
      assertTrue(ok.get("valid").asBoolean());
      assertEquals("a OR (b AND c)", ok.get("canonical").asString());
      assertTrue(ok.get("notes").get(0).asString().startsWith("Terms written next to each other"));
      assertTrue(ok.get("error").isNull());
      assertTrue(ok.get("position").isNull());

      var invalid = post(client, base, token, "{\"text\":\"a AND (b OR\"}");
      assertEquals(200, invalid.statusCode(), "a refusal is the answer, not an error");
      JsonNode bad = json.readTree(invalid.body());
      assertFalse(bad.get("valid").asBoolean());
      assertTrue(bad.get("canonical").isNull());
      assertEquals(0, bad.get("notes").size());
      assertEquals("a AND (b OR".length(), bad.get("position").asInt(), "end of the query text");
      assertTrue(bad.get("error").asString().startsWith("an operand is missing"));

      // The response carries fixed sentences and an offset, never an exception message.
      assertFalse(
          invalid.body().contains("MissingOperand") && invalid.body().contains("Exception"));
      var empty = post(client, base, token, "{\"text\":\"\"}");
      assertEquals(200, empty.statusCode());
      assertFalse(json.readTree(empty.body()).get("valid").asBoolean());
      var missing = post(client, base, token, "{}");
      assertEquals(200, missing.statusCode(), missing.body());
      assertEquals(0, json.readTree(missing.body()).get("position").asInt());

      // The strict JSON policy applies as everywhere else.
      assertEquals(400, post(client, base, token, "{\"text\":\"a\",\"extra\":1}").statusCode());
      assertEquals(400, post(client, base, token, "{\"text\":\"a\",\"text\":\"b\"}").statusCode());
      assertEquals(400, post(client, base, token, "{\"text\":5}").statusCode());
      assertEquals(400, post(client, base, token, "{\"text\":\"a\"} {}").statusCode());

      // No token, no search scope and ambient cookies are refused exactly like search.
      assertEquals(401, post(client, base, null, "{\"text\":\"a\"}").statusCode());
      String claims =
          "{\"iss\":\""
              + issuer.issuer()
              + "\",\"sub\":\"alice\",\"aud\":[\"stacks\"],\"iat\":"
              + Instant.now().getEpochSecond()
              + ",\"exp\":"
              + Instant.now().plusSeconds(300).getEpochSecond()
              + ",\"tenant\":\"demo\",\"scope\":\"ingest\"}";
      assertEquals(401, post(client, base, issuer.sign(claims), "{\"text\":\"a\"}").statusCode());
      assertEquals(
          403,
          post(client, base, token, "{\"text\":\"a\"}", "Cookie", "JSESSIONID=ambient")
              .statusCode());
    }
  }
}
