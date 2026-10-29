package io.github.cheshmakzanoon.portfolio.gatehouse;

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
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.springframework.security.oauth2.jwt.*;

class ProtectedClaimsTest {
  @Test
  void documentEnforcementRejectsCorrectlySignedMissingAudienceOrSubject() throws Exception {
    RSAKey key = new RSAKeyGenerator(2048).keyID("fixture").generate();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        r -> {
          byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          r.getResponseHeaders().set("Content-Type", "application/json");
          r.sendResponseHeaders(200, body.length);
          r.getResponseBody().write(body);
          r.close();
        });
    server.start();
    try {
      String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
      StandardEnvironment env = new StandardEnvironment();
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "gatehouse.oidc.issuer", issuer, "gatehouse.oidc.jwks", issuer + "/jwks")));
      JwtDecoder decoder = new ProtectedDocuments(env).decoder(env);
      Map<String, Object> valid = new LinkedHashMap<>();
      valid.put("iss", issuer);
      valid.put("sub", "alice");
      valid.put("aud", "gatehouse");
      valid.put("iat", Instant.now().getEpochSecond());
      valid.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
      JWSObject validSigned =
          new JWSObject(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(),
              new Payload(valid));
      validSigned.sign(new RSASSASigner(key));
      assertThat(decoder.decode(validSigned.serialize()).getSubject()).isEqualTo("alice");
      for (Object invalid : new Object[] {42, List.of("alice"), null, "", " ", "x".repeat(129)}) {
        Map<String, Object> raw = new LinkedHashMap<>(valid);
        raw.put("sub", invalid);
        JWSObject signed =
            new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(),
                new Payload(raw));
        signed.sign(new RSASSASigner(key));
        assertThatThrownBy(() -> decoder.decode(signed.serialize()))
            .as("original sub=%s", invalid)
            .isInstanceOf(JwtException.class);
      }
      assertThatThrownBy(() -> decoder.decode("a".repeat(16385))).isInstanceOf(JwtException.class);
      for (String missing : List.of("audience", "subject", "expiry", "issued")) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer);
        if (!missing.equals("audience")) claims.audience("gatehouse");
        if (!missing.equals("subject")) claims.subject("alice");
        if (!missing.equals("expiry"))
          claims.expirationTime(Date.from(Instant.now().plusSeconds(60)));
        if (!missing.equals("issued")) claims.issueTime(new Date());
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        assertThatThrownBy(() -> decoder.decode(jwt.serialize()))
            .as(missing)
            .isInstanceOf(JwtException.class);
      }
    } finally {
      server.stop(0);
    }
  }
}
