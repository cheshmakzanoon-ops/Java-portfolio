package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class JwtSecurityTest {
  @Test
  void fixedTrustVerifiesSignaturesAndAllRequiredClaimsWithoutDiscovery() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("trusted").generate();
    RSAKey forged = new RSAKeyGenerator(2048).keyID("trusted").generate();
    AtomicInteger jwksReads = new AtomicInteger(), discoveryReads = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        r -> {
          jwksReads.incrementAndGet();
          byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          r.getResponseHeaders().set("Content-Type", "application/json");
          r.sendResponseHeaders(200, body.length);
          r.getResponseBody().write(body);
          r.close();
        });
    server.createContext(
        "/evil",
        r -> {
          discoveryReads.incrementAndGet();
          r.sendResponseHeaders(500, -1);
          r.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      String issuer = base + "/trusted";
      JwtDecoder decoder =
          DecisionApplication.trustedDecoder(issuer, base + "/jwks", "tenantshield");
      assertThat(
              decoder
                  .decode(sign(key, claims(issuer, "tenantshield", 0, 60, 0, true)))
                  .getSubject())
          .isEqualTo("alice");
      for (JWTClaimsSet claims :
          List.of(
              new JWTClaimsSet.Builder()
                  .issuer(issuer)
                  .subject("alice")
                  .issueTime(new Date())
                  .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                  .build(),
              new JWTClaimsSet.Builder()
                  .issuer(issuer)
                  .subject(" ")
                  .audience("tenantshield")
                  .issueTime(new Date())
                  .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                  .build(),
              claims(issuer, "wrong", 0, 60, 0, true),
              claims(base + "/evil", "tenantshield", 0, 60, 0, true),
              claims(issuer, "tenantshield", -120, -60, 0, true),
              claims(issuer, "tenantshield", 0, 120, 60, true),
              claims(issuer, "tenantshield", 60, 120, 0, true),
              claims(issuer, "tenantshield", 0, 60, 0, false),
              new JWTClaimsSet.Builder()
                  .issuer(issuer)
                  .subject("alice")
                  .audience("tenantshield")
                  .issueTime(new Date())
                  .build()))
        assertThatThrownBy(() -> decoder.decode(sign(key, claims)))
            .isInstanceOf(JwtException.class);
      assertThatThrownBy(
              () -> decoder.decode(sign(forged, claims(issuer, "tenantshield", 0, 60, 0, true))))
          .isInstanceOf(JwtException.class);
      PlainJWT unsigned = new PlainJWT(claims(issuer, "tenantshield", 0, 60, 0, true));
      assertThatThrownBy(() -> decoder.decode(unsigned.serialize()))
          .isInstanceOf(JwtException.class);
      for (Object invalid : new Object[] {42, List.of("alice"), null, "", " ", "x".repeat(129)}) {
        Map<String, Object> raw =
            new LinkedHashMap<>(claims(issuer, "tenantshield", 0, 60, 0, true).toJSONObject());
        raw.put("sub", invalid);
        JWSObject signed =
            new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(),
                new Payload(raw));
        signed.sign(new RSASSASigner(key));
        assertThatThrownBy(() -> decoder.decode(signed.serialize()))
            .as("original sub=%s", invalid)
            .isInstanceOf(JwtException.class);
      }
      assertThatThrownBy(() -> decoder.decode("a".repeat(16385))).isInstanceOf(JwtException.class);
      assertThat(discoveryReads.get()).isZero();
      assertThat(jwksReads.get()).isPositive();
    } finally {
      server.stop(0);
    }
  }

  static JWTClaimsSet claims(
      String issuer,
      String audience,
      long issued,
      long expiry,
      long before,
      boolean includeIssued) {
    JWTClaimsSet.Builder b =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("alice")
            .audience(audience)
            .expirationTime(Date.from(Instant.now().plusSeconds(expiry)));
    if (includeIssued) b.issueTime(Date.from(Instant.now().plusSeconds(issued)));
    if (before != 0) b.notBeforeTime(Date.from(Instant.now().plusSeconds(before)));
    return b.build();
  }

  static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    signed.sign(new RSASSASigner(key));
    return signed.serialize();
  }
}
