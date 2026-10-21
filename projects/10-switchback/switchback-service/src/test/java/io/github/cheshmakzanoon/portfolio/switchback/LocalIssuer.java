package io.github.cheshmakzanoon.portfolio.switchback;

import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;

/** Actual local OIDC discovery/JWKS issuer, with JCA-signed RSA tokens for security acceptance. */
final class LocalIssuer implements AutoCloseable {
  final HttpServer server;
  final KeyPair key;
  final String issuer;

  LocalIssuer() throws Exception {
    var gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    key = gen.generateKeyPair();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/.well-known/openid-configuration",
        exchange -> {
          byte[] body =
              Json.write(
                      Map.of(
                          "issuer",
                          issuer,
                          "jwks_uri",
                          issuer + "/jwks",
                          "authorization_endpoint",
                          issuer + "/authorize",
                          "token_endpoint",
                          issuer + "/token",
                          "response_types_supported",
                          List.of("code"),
                          "subject_types_supported",
                          List.of("public"),
                          "id_token_signing_alg_values_supported",
                          List.of("RS256")))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/jwks",
        exchange -> {
          RSAPublicKey rsa = (RSAPublicKey) key.getPublic();
          byte[] body =
              Json.write(
                      Map.of(
                          "keys",
                          List.of(
                              Map.of(
                                  "kty",
                                  "RSA",
                                  "kid",
                                  "local-test",
                                  "use",
                                  "sig",
                                  "alg",
                                  "RS256",
                                  "n",
                                  unsigned(rsa.getModulus()),
                                  "e",
                                  unsigned(rsa.getPublicExponent())))))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  static String unsigned(BigInteger n) {
    byte[] b = n.toByteArray();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b);
  }

  String token(Map<String, Object> overrides) throws Exception {
    Map<String, Object> claims =
        new HashMap<>(
            Map.of(
                "iss",
                issuer,
                "sub",
                "dispatcher",
                "aud",
                List.of("switchback"),
                "tenant",
                "demo",
                "scope",
                "switchback.read switchback.write",
                "iat",
                Instant.now().getEpochSecond(),
                "exp",
                Instant.now().plusSeconds(300).getEpochSecond()));
    claims.putAll(overrides);
    claims.values().removeIf(Objects::isNull);
    String prefix =
        encode(Json.write(Map.of("alg", "RS256", "kid", "local-test")))
            + "."
            + encode(Json.write(claims));
    Signature sig = Signature.getInstance("SHA256withRSA");
    sig.initSign(key.getPrivate());
    sig.update(prefix.getBytes(StandardCharsets.US_ASCII));
    return prefix + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
  }

  static String encode(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  public void close() {
    server.stop(0);
  }
}
