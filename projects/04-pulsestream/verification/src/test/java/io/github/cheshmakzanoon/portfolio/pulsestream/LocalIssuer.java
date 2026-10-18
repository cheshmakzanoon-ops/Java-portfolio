package io.github.cheshmakzanoon.portfolio.pulsestream;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/** Disposable local issuer with real RSA signing and public JWK discovery. No mocked JwtDecoder. */
final class LocalIssuer implements AutoCloseable {
  private final RSAKey key = new RSAKeyGenerator(2048).keyID("local-test-key").generate();
  private final RSAKey wrong = new RSAKeyGenerator(2048).keyID("local-test-key").generate();
  private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
  private final java.util.concurrent.ExecutorService threads =
      Executors.newVirtualThreadPerTaskExecutor();

  LocalIssuer() throws Exception {
    server.createContext(
        "/jwks",
        e -> {
          byte[] data = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Content-Type", "application/json");
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    server.createContext(
        "/.well-known/openid-configuration",
        e -> {
          byte[] data =
              ("{\"issuer\":\""
                      + url()
                      + "\",\"jwks_uri\":\""
                      + url()
                      + "/jwks\",\"token_endpoint\":\""
                      + url()
                      + "/token\"}")
                  .getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    server.createContext(
        "/token",
        e -> {
          try {
            String query = e.getRequestURI().getQuery();
            String fixture = query == null ? "valid" : query.replace("fixture=", "");
            byte[] data =
                ("{\"access_token\":\"" + token(fixture) + "\",\"token_type\":\"Bearer\"}")
                    .getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, data.length);
            e.getResponseBody().write(data);
          } catch (Exception problem) {
            e.sendResponseHeaders(500, -1);
          } finally {
            e.close();
          }
        });
    server.setExecutor(threads);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String token(String variant) throws Exception {
    Instant now = Instant.now();
    var builder =
        new JWTClaimsSet.Builder()
            .issuer(variant.equals("issuer") ? "http://wrong.invalid" : url())
            .issueTime(Date.from(now.minusSeconds(1)))
            .claim("scope", variant.equals("scope") ? "other" : "telemetry:read");
    if (!variant.equals("no-sub")) builder.subject("fixture-reader");
    if (!variant.equals("no-exp"))
      builder.expirationTime(Date.from(now.plusSeconds(variant.equals("expired") ? -120 : 300)));
    if (!variant.equals("no-audience"))
      builder.audience(variant.equals("audience") ? "wrong" : "pulse-api");
    if (!variant.equals("no-tenant"))
      builder.claim(
          "tenant",
          variant.equals("tenant-type")
              ? Map.of("id", "t")
              : variant.equals("tenant") ? "other" : "t");
    if (variant.equals("nbf")) builder.notBeforeTime(Date.from(now.plusSeconds(300)));
    if (variant.equals("nbf-type")) builder.claim("nbf", "not-an-instant");
    if (variant.equals("numeric-sub")
        || variant.equals("array-sub")
        || variant.equals("blank-sub")) {
      var raw = new java.util.HashMap<String, Object>(builder.build().toJSONObject());
      raw.put(
          "sub",
          variant.equals("numeric-sub")
              ? 42
              : variant.equals("array-sub") ? java.util.List.of("fixture-reader") : "   ");
      var rawJws =
          new com.nimbusds.jose.JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              new com.nimbusds.jose.Payload(raw));
      rawJws.sign(new RSASSASigner(key));
      return rawJws.serialize();
    }
    var claims = builder.build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(variant.equals("signature") ? wrong : key));
    return jwt.serialize();
  }

  @Override
  public void close() {
    server.stop(0);
    threads.close();
  }
}
