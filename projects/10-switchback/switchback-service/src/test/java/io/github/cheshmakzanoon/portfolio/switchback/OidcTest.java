package io.github.cheshmakzanoon.portfolio.switchback;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class OidcTest {
  @Test
  void realLocalOidcSignatureIssuerAudienceExpiryAndClaims() throws Exception {
    try (LocalIssuer issuer = new LocalIssuer()) {
      JwtDecoder decoder = Security.protectedDecoder(issuer.issuer, "switchback");
      assertEquals("dispatcher", decoder.decode(issuer.token(Map.of())).getSubject());
      assertThrows(
          JwtException.class,
          () -> decoder.decode(issuer.token(Map.of("iss", "http://wrong.local"))));
      assertThrows(
          JwtException.class, () -> decoder.decode(issuer.token(Map.of("aud", List.of("other")))));
      assertThrows(
          JwtException.class,
          () ->
              decoder.decode(
                  issuer.token(Map.of("exp", Instant.now().minusSeconds(300).getEpochSecond()))));
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(Map.of("tenant", 42))));
      Map<String, Object> absent = new HashMap<>();
      absent.put("exp", null);
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(absent)));
      absent.clear();
      absent.put("aud", null);
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(absent)));
      absent.clear();
      absent.put("sub", null);
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(absent)));
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(Map.of("sub", 42))));
      assertThrows(
          JwtException.class,
          () -> decoder.decode(issuer.token(Map.of("tenant", List.of("demo")))));
      assertThrows(
          JwtException.class,
          () -> decoder.decode(issuer.token(Map.of("sub", List.of("dispatcher")))));
      assertThrows(JwtException.class, () -> decoder.decode(issuer.token(Map.of("sub", ""))));
      assertThrows(JwtException.class, () -> decoder.decode("a".repeat(16385)));
      String original = issuer.token(Map.of());
      String[] bits = original.split("\\.");
      String bad =
          bits[0] + "." + LocalIssuer.encode(Json.write(Map.of("sub", "intruder"))) + "." + bits[2];
      assertThrows(JwtException.class, () -> decoder.decode(bad));
    }
  }
}
