package io.github.cheshmakzanoon.portfolio.matchcore;

import static io.github.cheshmakzanoon.portfolio.matchcore.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpApiTest {
  @TempDir Path dir;
  private final HttpClient client = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private RSAKey key() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    KeyPair p = g.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) p.getPublic())
        .privateKey((RSAPrivateKey) p.getPrivate())
        .keyID("fixture-key")
        .build();
  }

  private String token(
      RSAKey key, String issuer, String audience, String subject, String scope, Instant expiry)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .claim("scope", scope)
                .issueTime(new Date())
                .expirationTime(Date.from(expiry))
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private HttpResponse<String> request(int port, String path, String token, String body)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void realIssuerSignatureAudienceExpiryScopesOwnership() throws Exception {
    RSAKey key = key();
    HttpServer issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String url = "http://127.0.0.1:" + issuer.getAddress().getPort();
    issuer.createContext(
        "/.well-known/openid-configuration",
        x -> {
          byte[] body =
              ("{\"issuer\":\""
                      + url
                      + "\",\"jwks_uri\":\""
                      + url
                      + "/jwks\",\"authorization_endpoint\":\""
                      + url
                      + "/authorize\",\"response_types_supported\":[\"id_token\"],\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    issuer.createContext(
        "/jwks",
        x -> {
          byte[] body =
              new JWKSet(key.toPublicJWK())
                  .toString()
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.getResponseHeaders().set("Content-Type", "application/json");
          x.sendResponseHeaders(200, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    issuer.start();
    try (Gateway gateway = new Gateway(dir.resolve("journal"), "test", 8, 100);
        HttpApi api =
            new HttpApi(gateway, new InetSocketAddress("127.0.0.1", 0), url, "matchcore", false)) {
      Instant future = Instant.now().plusSeconds(600);
      String scopes = "matchcore:read matchcore:write matchcore:admin";
      String valid = token(key, url, "matchcore", "alice", scopes, future);
      assertEquals(401, request(api.port(), "/book", null, null).statusCode());
      for (JWTClaimsSet missing :
          List.of(
              new JWTClaimsSet.Builder()
                  .issuer(url)
                  .audience("matchcore")
                  .subject("alice")
                  .issueTime(new Date())
                  .claim("scope", scopes)
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer(url)
                  .audience("matchcore")
                  .subject("alice")
                  .expirationTime(Date.from(future))
                  .claim("scope", scopes)
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer(url)
                  .subject("alice")
                  .issueTime(new Date())
                  .expirationTime(Date.from(future))
                  .claim("scope", scopes)
                  .build()))
        assertEquals(401, request(api.port(), "/book", sign(key, missing), null).statusCode());
      for (Object badSubject : Arrays.asList(null, "", "   ", 42, List.of("alice"))) {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("iss", url);
        raw.put("aud", List.of("matchcore"));
        raw.put("sub", badSubject);
        raw.put("scope", scopes);
        raw.put("exp", future.getEpochSecond());
        raw.put("iat", Instant.now().getEpochSecond());
        var signed =
            new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new Payload(raw));
        signed.sign(new RSASSASigner(key));
        assertEquals(
            401,
            request(api.port(), "/book", signed.serialize(), null).statusCode(),
            "original signed sub=" + badSubject);
      }
      for (String invalid :
          List.of(
              token(key, url, "wrong", "alice", scopes, future),
              token(key, "https://wrong.example", "matchcore", "alice", scopes, future),
              token(key, url, "matchcore", "alice", scopes, Instant.now().minusSeconds(120)),
              token(key(), url, "matchcore", "alice", scopes, future)))
        assertEquals(401, request(api.port(), "/book", invalid, null).statusCode());
      assertEquals(
          403,
          request(
                  api.port(),
                  "/book",
                  token(key, url, "matchcore", "alice", "matchcore:write", future),
                  null)
              .statusCode());
      assertEquals(
          403,
          request(
                  api.port(),
                  "/book?unknown=1",
                  token(key, url, "matchcore", "alice", "matchcore:write", future),
                  null)
              .statusCode());
      Command c = new Command("init", "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 10);
      assertEquals(
          200, request(api.port(), "/commands", valid, json.writeValueAsString(c)).statusCode());
      String bob = token(key, url, "matchcore", "bob", scopes, future);
      assertEquals(
          403, request(api.port(), "/commands", bob, json.writeValueAsString(c)).statusCode());
      assertEquals(404, request(api.port(), "/commands/init", bob, null).statusCode());
      assertEquals(200, request(api.port(), "/commands/init", valid, null).statusCode());
      assertEquals(
          200, request(api.port(), "/book?depth=20&minimumSequence=1", valid, null).statusCode());
      assertEquals(400, request(api.port(), "/book?depth=1001", valid, null).statusCode());
      assertEquals(
          200, request(api.port(), "/executions?offset=0&limit=10", valid, null).statusCode());
      assertEquals(400, request(api.port(), "/commands", valid, "malformed").statusCode());
      assertEquals(200, request(api.port(), "/account", valid, null).statusCode());
      Command resting =
          new Command("new", "alice", Type.NEW, "order", Side.BUY, 10, 2, Tif.GTC, false, 0, 0);
      assertEquals(
          200,
          request(api.port(), "/commands", valid, json.writeValueAsString(resting)).statusCode());
      Command replacement =
          new Command(
              "replace", "alice", Type.REPLACE, "order", Side.BUY, 10, 1, Tif.GTC, false, 0, 0);
      String replacementJson = json.writeValueAsString(replacement);
      assertEquals(400, request(api.port(), "/commands", valid, replacementJson).statusCode());
      String explicitRemaining = replacementJson.replace("quantityLots", "newRemainingQtyLots");
      assertTrue(
          request(api.port(), "/commands", valid, explicitRemaining).body().contains("REDUCED"));
      assertEquals(403, request(api.port(), "/commands", bob, explicitRemaining).statusCode());
      assertEquals(
          400,
          request(
                  api.port(),
                  "/commands",
                  valid,
                  json.writeValueAsString(c).replace("1000", "1000.5"))
              .statusCode());
    } finally {
      issuer.stop(0);
    }
  }

  @Test
  void saturationReturnsExplicitRetryableResponse() throws Exception {
    java.util.concurrent.CountDownLatch forced = new java.util.concurrent.CountDownLatch(1),
        release = new java.util.concurrent.CountDownLatch(1);
    try (Gateway g =
            new Gateway(
                dir.resolve("saturated"),
                "test",
                8,
                100,
                b -> {
                  if (b.equals("BEFORE_FORCE")) {
                    forced.countDown();
                    try {
                      if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("test timeout");
                    } catch (InterruptedException e) {
                      throw new java.io.IOException(e);
                    }
                  }
                });
        HttpApi api = new HttpApi(g, new InetSocketAddress("127.0.0.1", 0), null, null, true)) {
      List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> requests =
          new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Command c =
            new Command("init-" + i, "alice", Type.INIT, null, null, 0, 0, null, false, 1000, 100);
        requests.add(
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/commands"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("X-Demo-Account", "alice")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(c)))
                    .build(),
                HttpResponse.BodyHandlers.ofString()));
      }
      assertTrue(forced.await(5, java.util.concurrent.TimeUnit.SECONDS));
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
      while (api.activeRequests() < 4 && System.nanoTime() < deadline) Thread.sleep(1);
      assertEquals(4, api.activeRequests());
      java.util.concurrent.CountDownLatch overloaded = new java.util.concurrent.CountDownLatch(1);
      for (int i = 0; i < 90; i++) {
        var response =
            client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/book"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("X-Demo-Account", "alice")
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        response.thenAccept(
            r -> {
              if (r.statusCode() == 429 && r.body().contains("OVERLOADED_RETRYABLE"))
                overloaded.countDown();
            });
        requests.add(response);
      }
      try {
        assertTrue(
            overloaded.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "HTTP saturation must return a visible retryable response");
      } finally {
        release.countDown();
      }
      for (var request : requests) {
        int status = request.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode();
        assertTrue(status == 200 || status == 429, "status " + status);
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void commandEnumsAndQueryParametersHaveOneInterpretation() throws Exception {
    try (Gateway gateway = new Gateway(dir.resolve("strict-input"), "test", 8, 100);
        HttpApi api =
            new HttpApi(gateway, new InetSocketAddress("127.0.0.1", 0), null, null, true)) {
      Command command =
          new Command("new", "alice", Type.NEW, "order", Side.BUY, 10, 2, Tif.GTC, false, 0, 0);
      String body = json.writeValueAsString(command);
      for (String invalid :
          List.of(
              body.replace("\"NEW\"", "1"),
              body.replace("\"BUY\"", "0"),
              body.replace("\"GTC\"", "0"),
              body.replace("\"clientCommandId\":\"new\"", "\"clientCommandId\":123"),
              body.replace("\"accountId\":\"alice\"", "\"accountId\":true"),
              body.replace("\"orderId\":\"order\"", "\"orderId\":12.5"),
              body.replace("\"priceTicks\":10", "\"priceTicks\":\"10\""),
              body.replace("\"postOnly\":false", "\"postOnly\":\"false\""),
              body.replace("\"quantityLots\":2", "\"quantityLots\":null"))) {
        HttpResponse<String> response = trustedRequest(api.port(), "/commands", invalid);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("INVALID_REQUEST"));
      }
      assertEquals(0, gateway.view().sequence(), "invalid commands must not enter the journal");
      assertEquals(400, trustedRequest(api.port(), "/commands?unknown=1", body).statusCode());
      assertEquals(0, gateway.view().sequence(), "query validation must precede admission");
      assertEquals(
          "no-store",
          trustedRequest(api.port(), "/account", null)
              .headers()
              .firstValue("Cache-Control")
              .orElse(""));
      for (String path :
          List.of(
              "/book?depth=1&depth=2",
              "/book?depth=1&%64epth=2",
              "/book?minimumSequence=-1",
              "/book?unknown=1",
              "/executions?limit=1&limit=2",
              "/executions?offset=-1",
              "/executions?depth=1",
              "/account?unknown=1",
              "/commands/new?unknown=1"))
        assertEquals(400, trustedRequest(api.port(), path, null).statusCode(), path);
      assertEquals(200, trustedRequest(api.port(), "/book?%64epth=%32", null).statusCode());
      assertEquals(
          200, trustedRequest(api.port(), "/executions?offset=0&limit=1", null).statusCode());
    }
  }

  private HttpResponse<String> trustedRequest(int port, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("X-Demo-Account", "alice");
    if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void trustedModeRefusesPublicBinding() throws Exception {
    try (Gateway g = new Gateway(dir.resolve("journal"), "test", 8, 100)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new HttpApi(g, new InetSocketAddress("0.0.0.0", 0), null, null, true));
    }
  }
}
