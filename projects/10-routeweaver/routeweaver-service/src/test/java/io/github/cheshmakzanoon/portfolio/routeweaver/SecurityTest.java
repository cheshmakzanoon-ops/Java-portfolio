package io.github.cheshmakzanoon.portfolio.routeweaver;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityTest {
  Jwt token(Map<String, Object> overrides) {
    Map<String, Object> claims =
        new HashMap<>(
            Map.of(
                "iss",
                "https://local.example",
                "sub",
                "dispatcher",
                "aud",
                List.of("routeweaver"),
                "tenant",
                "demo",
                "exp",
                Instant.now().plusSeconds(60)));
    claims.putAll(overrides);
    return new Jwt(
        "signed",
        Instant.now(),
        claims.get("exp") instanceof Instant i ? i : null,
        Map.of("alg", "RS256"),
        claims);
  }

  @Test
  void requiresExplicitExpSubAudienceAndTypedTenant() {
    assertFalse(Security.claims("routeweaver").validate(token(Map.of())).hasErrors());
    assertTrue(Security.claims("routeweaver").validate(token(Map.of("tenant", 42))).hasErrors());
    assertTrue(Security.claims("routeweaver").validate(token(Map.of("sub", ""))).hasErrors());
    assertTrue(
        Security.claims("routeweaver")
            .validate(token(Map.of("aud", List.of("other"))))
            .hasErrors());
    Map<String, Object> missing = new HashMap<>();
    missing.put("exp", null);
    assertTrue(Security.claims("routeweaver").validate(token(missing)).hasErrors());
  }

  @Test
  void jsonRoundtripUsesIntegerUnitsAndZone() {
    Model.Problem p = PostgresIT.problem("demo");
    assertEquals(p.canonicalHash(), Json.read(Json.write(p), Model.Problem.class).canonicalHash());
    assertThrows(IllegalArgumentException.class, () -> Json.read("{bad}", Model.Problem.class));
  }

  @Test
  void integerUnitsRejectFractionalNullAndStringValuesAndTextRejectsNumbers() {
    for (String seconds : List.of("1.5", "1.0", "\"1\"", "null", "true")) {
      String body = "{\"seconds\":" + seconds + ",\"meters\":2}";
      assertThrows(IllegalArgumentException.class, () -> Json.read(body, Model.Arc.class), body);
    }
    assertEquals(new Model.Arc(1, 2), Json.read("{\"seconds\":1,\"meters\":2}", Model.Arc.class));
    String original = Json.write(PostgresIT.problem("demo"));
    for (String body :
        List.of(
            original.replace("\"version\":1,", "\"version\":1.5,"),
            original.replace("\"id\":\"p\"", "\"id\":42"))) {
      assertNotEquals(original, body);
      assertThrows(
          IllegalArgumentException.class, () -> Json.read(body, Model.Problem.class), body);
    }
  }

  @Test
  void malformedDuplicateAndTrailingJsonAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> Json.read("{\"x\":1,\"x\":2}", Map.class));
    assertThrows(IllegalArgumentException.class, () -> Json.read("{} {}", Map.class));
  }
}
