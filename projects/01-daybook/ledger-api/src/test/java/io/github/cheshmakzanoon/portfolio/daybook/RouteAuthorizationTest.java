package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Scope rules for the authoritative read routes and period opening, on the finished chain. */
class RouteAuthorizationTest {
  private static final Map<String, String> SCOPES =
      Map.of(
          "read", "ledger.read",
          "write", "ledger.write",
          "reconcile", "ledger.reconcile",
          "admin", "ledger.admin");
  private static final String ID = "00000000-0000-0000-0000-0000000000aa";

  private AnnotationConfigWebApplicationContext context;
  private FilterChainProxy security;

  @EnableWebSecurity
  @EnableWebMvc
  static class Fixture {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
      return new DaybookApplication().security(http);
    }

    @Bean
    JwtDecoder decoder() {
      return token -> {
        String scope = SCOPES.get(token);
        if (scope == null) throw new BadJwtException("untrusted fixture token");
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("route-fixture")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("scope", scope)
            .build();
      };
    }
  }

  @BeforeEach
  void start() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(Fixture.class);
    context.refresh();
    security = context.getBean(FilterChainProxy.class);
  }

  @AfterEach
  void stop() {
    SecurityContextHolder.clearContext();
    if (context != null) context.close();
  }

  private int status(String method, String path, String token) throws Exception {
    var request = new MockHttpServletRequest(context.getServletContext(), method, path);
    request.setServletPath(path);
    if (token != null) request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    security.doFilter(
        request,
        response,
        (req, resp) -> ((jakarta.servlet.http.HttpServletResponse) resp).setStatus(204));
    return response.getStatus();
  }

  @Test
  void authoritativeReadsNeedOnlyTheReadScope() throws Exception {
    for (String path :
        new String[] {
          "/v1/trial-balance?asOf=2026-06-30",
          "/v1/accounts",
          "/v1/accounts/" + ID,
          "/v1/journals/" + ID,
          "/v1/periods"
        }) {
      assertEquals(204, status("GET", path, "read"), path);
      for (String other : new String[] {"write", "reconcile"})
        assertEquals(403, status("GET", path, other), path + " with " + other);
      assertEquals(401, status("GET", path, null), path);
      assertEquals(401, status("GET", path, "unknown"), path);
    }
  }

  @Test
  void openingAPeriodNeedsTheWriteScopeAndReadsDoNotGrantIt() throws Exception {
    assertEquals(204, status("POST", "/v1/periods", "write"));
    for (String other : new String[] {"read", "reconcile", "admin"})
      assertEquals(403, status("POST", "/v1/periods", other), other);
    assertEquals(401, status("POST", "/v1/periods", null));
  }

  @Test
  void reportingRoutesKeepTheirStricterScope() throws Exception {
    assertEquals(403, status("GET", "/v1/reports/accounts/" + ID, "read"));
    assertEquals(204, status("GET", "/v1/reports/accounts/" + ID, "admin"));
    assertEquals(403, status("GET", "/v1/reports/freshness", "read"));
  }
}
