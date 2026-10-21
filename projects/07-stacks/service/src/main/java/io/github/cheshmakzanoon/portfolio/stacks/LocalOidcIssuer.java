package io.github.cheshmakzanoon.portfolio.stacks;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;

public final class LocalOidcIssuer implements AutoCloseable {
  private final KeyPair pair;
  private final HttpServer server;
  private final String issuer;

  public LocalOidcIssuer(int port) throws IOException {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      pair = generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IOException(e);
    }
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/.well-known/openid-configuration",
        exchange -> {
          byte[] bytes =
              ("{\"issuer\":\""
                      + issuer
                      + "\",\"jwks_uri\":\""
                      + issuer
                      + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.createContext(
        "/jwks",
        exchange -> {
          RSAPublicKey key = (RSAPublicKey) pair.getPublic();
          String body =
              "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"disposable-local\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""
                  + unsigned(key.getModulus())
                  + "\",\"e\":\""
                  + unsigned(key.getPublicExponent())
                  + "\"}]}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  private static String unsigned(BigInteger n) {
    byte[] b = n.toByteArray();
    if (b[0] == 0) b = Arrays.copyOfRange(b, 1, b.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public String issuer() {
    return issuer;
  }

  public String token(String subject, String tenant, Instant expiration, String audience) {
    if (!subject.matches("[a-zA-Z0-9_-]+") || !tenant.matches("[a-zA-Z0-9_-]+"))
      throw new IllegalArgumentException("identity");
    return sign(
        "{\"iss\":\""
            + issuer
            + "\",\"sub\":\""
            + subject
            + "\",\"aud\":[\""
            + audience
            + "\"],\"iat\":"
            + Instant.now().getEpochSecond()
            + ",\"exp\":"
            + expiration.getEpochSecond()
            + ",\"tenant\":\""
            + tenant
            + "\",\"scope\":\"search ingest\"}");
  }

  public String sign(String claims) {
    String h =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"disposable-local\"}"
                    .getBytes(StandardCharsets.UTF_8));
    String payload =
        h
            + "."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(pair.getPrivate());
      s.update(payload.getBytes(StandardCharsets.US_ASCII));
      return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
