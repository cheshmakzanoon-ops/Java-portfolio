package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exercises the finished filter chain; matcher-only tests miss resource-server CSRF overrides. */
class ConfiguredCsrfTest {
  private AnnotationConfigWebApplicationContext context;
  private FilterChainProxy security;
  private final AtomicInteger applicationCalls = new AtomicInteger();

  // A lite fixture avoids importing database beans or changing the production authentication chain.
  @EnableWebSecurity
  @EnableWebMvc
  static class SecurityFixture {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
      return new DecisionApplication().security(http);
    }

    @Bean
    JwtDecoder decoder() {
      // Signature/claim validation has separate tests. This fixture isolates CSRF and filter order.
      return token -> {
        if (!"trusted-bearer".equals(token)) throw new BadJwtException("untrusted fixture token");
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("csrf-fixture")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("scope", "decision.read decision.write")
            .build();
      };
    }
  }

  @BeforeEach
  void startConfiguredSecurity() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(SecurityFixture.class);
    context.refresh();
    security = context.getBean(FilterChainProxy.class);
  }

  @AfterEach
  void closeConfiguredSecurity() {
    SecurityContextHolder.clearContext();
    if (context != null) context.close();
  }

  @Test
  void bearerMatcherCannotExemptAmbientCookiesOnAnyMutationMethod() throws Exception {
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
      var request = bearer(method);
      request.setCookies(new Cookie("session", "private-cookie-canary"));
      assertDenied(dispatch(request));
    }
    assertEquals(0, applicationCalls.get());
  }

  @Test
  void rawCookieHeadersRemainProtectedWhenServletCookieParsingDropsThem() throws Exception {
    for (String cookie : List.of("session=ambient", "$Version=invalid", "broken-cookie")) {
      var request = bearer("POST");
      request.addHeader("Cookie", cookie);
      assertNull(request.getCookies(), "fixture must exercise raw headers independently");
      assertDenied(dispatch(request));
    }
    assertEquals(0, applicationCalls.get());
  }

  @Test
  void explicitBearerMutationsAndSafeCookieRequestsKeepWorking() throws Exception {
    for (String method : List.of("POST", "PUT", "PATCH", "DELETE"))
      assertEquals(204, dispatch(bearer(method)).getStatus());
    for (String method : List.of("GET", "HEAD", "OPTIONS")) {
      var request = bearer(method);
      request.setCookies(new Cookie("session", "ambient"));
      request.addHeader("Cookie", "session=ambient");
      assertEquals(204, dispatch(request).getStatus());
    }
    assertEquals(7, applicationCalls.get());
  }

  @Test
  void browserBasicAndAnonymousCookieDenialsDoNotRedispatchIntoAuthentication() throws Exception {
    for (String browserHeader : List.of("Origin", "Sec-Fetch-Site")) {
      var request = request("POST");
      request.addHeader("Authorization", "bAsIc cHJpdmF0ZTpjYW5hcnk=");
      request.addHeader(browserHeader, "cross-site");
      assertDenied(dispatch(request));
    }
    var anonymous = request("POST");
    anonymous.setCookies(new Cookie("session", "private-cookie-canary"));
    assertDenied(dispatch(anonymous));
    assertEquals(0, applicationCalls.get());
  }

  @Test
  void validSessionCsrfTokenAllowsAuthenticatedCookieWriteAndInvalidTokenDoesNot()
      throws Exception {
    var read = bearer("GET");
    var token = new AtomicReference<CsrfToken>();
    security.doFilter(
        read,
        new MockHttpServletResponse(),
        (request, response) -> {
          var value = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
          value.getToken(); // Materialize the deferred, masked token and its repository session.
          token.set(value);
        });
    assertNotNull(read.getSession(false));
    var write = bearer("POST");
    write.setSession(read.getSession(false));
    write.setCookies(new Cookie("JSESSIONID", read.getSession(false).getId()));
    write.addHeader(token.get().getHeaderName(), token.get().getToken());
    assertEquals(204, dispatch(write).getStatus());

    var invalid = bearer("POST");
    invalid.setSession(read.getSession(false));
    invalid.setCookies(new Cookie("JSESSIONID", read.getSession(false).getId()));
    invalid.addHeader(token.get().getHeaderName(), "private-token-canary");
    assertDenied(dispatch(invalid));
    assertEquals(1, applicationCalls.get());
  }

  @Test
  void bypassingCsrfForExplicitCredentialsStillRequiresAuthentication() throws Exception {
    assertEquals(401, dispatch(request("POST")).getStatus());
    var invalid = request("POST");
    invalid.addHeader("Authorization", "Bearer untrusted-bearer");
    assertEquals(401, dispatch(invalid).getStatus());
    assertEquals(0, applicationCalls.get());
  }

  private MockHttpServletRequest request(String method) {
    var request = new MockHttpServletRequest(context.getServletContext(), method, "/v1/csrf-probe");
    request.setServletPath("/v1/csrf-probe");
    return request;
  }

  private MockHttpServletRequest bearer(String method) {
    var request = request(method);
    request.addHeader("Authorization", "Bearer trusted-bearer");
    return request;
  }

  private MockHttpServletResponse dispatch(MockHttpServletRequest request) throws Exception {
    var response = new MockHttpServletResponse();
    security.doFilter(
        request,
        response,
        (req, resp) -> {
          applicationCalls.incrementAndGet();
          ((jakarta.servlet.http.HttpServletResponse) resp).setStatus(204);
        });
    return response;
  }

  private void assertDenied(MockHttpServletResponse response) throws Exception {
    assertEquals(403, response.getStatus());
    assertNull(response.getErrorMessage(), "sendError would dispatch through authentication again");
    assertNull(response.getHeader("WWW-Authenticate"));
    assertTrue(response.getContentType().startsWith("application/"));
    String body = response.getContentAsString();
    assertFalse(body.isBlank());
    assertFalse(body.contains("private-"));
    assertTrue(body.length() < 256, "denial must remain a bounded generic response");
  }
}
