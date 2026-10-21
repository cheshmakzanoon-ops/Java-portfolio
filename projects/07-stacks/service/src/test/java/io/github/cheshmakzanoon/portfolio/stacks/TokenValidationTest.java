package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class TokenValidationTest {
  Jwt.Builder token() {
    return Jwt.withTokenValue("unit")
        .header("alg", "RS256")
        .issuer("https://issuer.invalid")
        .subject("alice")
        .audience(List.of("stacks"))
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("tenant", "demo")
        .claim("scope", "search ingest");
  }

  @Test
  void missingAndWrongTypedMandatoryClaimsFailClosed() {
    var validator = SecurityConfiguration.strictClaims("stacks");
    assertFalse(validator.validate(token().build()).hasErrors());
    assertTrue(validator.validate(token().claims(c -> c.remove("exp")).build()).hasErrors());
    assertTrue(validator.validate(token().claims(c -> c.remove("sub")).build()).hasErrors());
    assertTrue(validator.validate(token().claims(c -> c.remove("aud")).build()).hasErrors());
    assertTrue(validator.validate(token().claim("tenant", 9).build()).hasErrors());
    assertTrue(validator.validate(token().claim("sub", 9).build()).hasErrors());
    assertTrue(
        validator
            .validate(token().claim("aud", Arrays.asList("stacks", null)).build())
            .hasErrors());
    assertTrue(validator.validate(token().claim("scope", "ingest").build()).hasErrors());
    assertTrue(
        validator.validate(token().expiresAt(Instant.now().minusSeconds(1)).build()).hasErrors());
    assertTrue(validator.validate(token().audience(List.of("other")).build()).hasErrors());
  }
}
