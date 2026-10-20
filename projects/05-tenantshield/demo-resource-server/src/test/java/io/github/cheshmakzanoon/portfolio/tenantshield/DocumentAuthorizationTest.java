package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

class DocumentAuthorizationTest {
  private static final String ALLOW =
      """
      {"decision":"ALLOW","reason":"MATCHED_ALLOW","tenantEpoch":7,"policyVersion":3,
      "resourceVersion":2,"matchedRuleIds":["read-department"],
      "decisionId":"00000000-0000-0000-0000-000000000001"}
      """;
  private static final Jwt PRINCIPAL =
      Jwt.withTokenValue("fixture-token").header("alg", "RS256").subject("alice").build();

  @Test
  void everyDocumentReadRequiresANewCompleteDecision() throws Exception {
    try (DecisionServer server = new DecisionServer()) {
      ProtectedDocuments documents = server.documents();
      assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value())
          .isEqualTo(200);
      assertThat(server.authorization.get()).isEqualTo("Bearer fixture-token");
      assertThat(server.request.get()).contains("document:read", "doc-7");
      server.reply.set(
          new Reply(
              200,
              "application/json",
              ALLOW
                  .replace("\"decision\":\"ALLOW\"", "\"decision\":\"DENY\"")
                  .replace("MATCHED_ALLOW", "EXPLICIT_DENY")));
      assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value())
          .isEqualTo(403);
      server.reply.set(new Reply(200, "application/json", ALLOW));
      assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value())
          .isEqualTo(200);
      assertThat(server.calls).hasValue(3);
    }
  }

  @Test
  void malformedAllowResponsesNeverExposeTheDocument() throws Exception {
    try (DecisionServer server = new DecisionServer()) {
      ProtectedDocuments documents = server.documents();
      for (String malformed :
          List.of(
              "{\"decision\":\"ALLOW\"}",
              "null",
              ALLOW + " {}",
              ALLOW.replace(
                  "\"decision\":\"ALLOW\"", "\"decision\":\"DENY\",\"decision\":\"ALLOW\""),
              ALLOW.replace("\"tenantEpoch\":7", "\"tenantEpoch\":0"),
              ALLOW.replace("\"policyVersion\":3", "\"policyVersion\":-1"),
              ALLOW.replace("\"policyVersion\":3", "\"policyVersion\":\"3\""),
              ALLOW.replace("\"resourceVersion\":2", "\"resourceVersion\":2.5"),
              ALLOW.replace("MATCHED_ALLOW", "NO_RBAC_GRANT"),
              ALLOW.replace("[\"read-department\"]", "[]"),
              ALLOW.replace("[\"read-department\"]", "[123]"),
              ALLOW.replace("[\"read-department\"]", "[\"read-department\",\"read-department\"]"),
              ALLOW.replace("00000000-0000-0000-0000-000000000001", "invalid-uuid"),
              ALLOW + " ".repeat(65537))) {
        server.reply.set(new Reply(200, "application/json", malformed));
        assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value())
            .as(malformed.length() > 1024 ? "oversized response" : malformed)
            .isEqualTo(503);
      }
    }
  }

  @Test
  void transportFailuresAndWrongContentTypesFailClosed() throws Exception {
    DecisionServer server = new DecisionServer();
    ProtectedDocuments documents = server.documents();
    try (server) {
      for (Reply response :
          List.of(
              new Reply(503, "application/json", ALLOW),
              new Reply(200, "text/html", ALLOW),
              new Reply(200, "application/json", ""))) {
        server.reply.set(response);
        assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value())
            .isEqualTo(503);
      }
      assertThat(documents.read("invalid/tenant", "doc-7", PRINCIPAL).getStatusCode().value())
          .isEqualTo(400);
      assertThat(server.calls).hasValue(3);
    }
    assertThat(documents.read("harbor", "doc-7", PRINCIPAL).getStatusCode().value()).isEqualTo(503);
  }

  private record Reply(int status, String contentType, String body) {}

  private static final class DecisionServer implements AutoCloseable {
    final AtomicReference<Reply> reply =
        new AtomicReference<>(new Reply(200, "application/json", ALLOW));
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> request = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final HttpServer server;

    DecisionServer() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/tenants/harbor/decisions",
          exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply response = reply.get();
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    ProtectedDocuments documents() {
      StandardEnvironment environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "tenantshield.decision-url",
                      "http://127.0.0.1:" + server.getAddress().getPort())));
      return new ProtectedDocuments(environment);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
