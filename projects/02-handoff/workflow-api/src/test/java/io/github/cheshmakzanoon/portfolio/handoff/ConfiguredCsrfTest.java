package io.github.cheshmakzanoon.portfolio.handoff;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exercises the finished filter chain; matcher-only tests miss resource-server CSRF overrides. */
class ConfiguredCsrfTest {
  private AnnotationConfigWebApplicationContext context;
  private FilterChainProxy security;
  private LocalIssuer issuer;
  private final AtomicInteger applicationCalls = new AtomicInteger();

  // A lite fixture avoids importing database beans or changing the production authentication chain.
  @EnableWebSecurity
  @EnableWebMvc
  static class SecurityFixture {
    @Bean
    SecurityFilterChain security(HttpSecurity http, Environment environment) throws Exception {
      return new Application(null).security(http, environment);
    }

    @Bean
    @Profile("dev")
    UserDetailsService users(Environment environment) {
      return new Application(null).users(environment);
    }
  }

  @BeforeEach
  void startConfiguredSecurity() throws Exception {
    issuer = new LocalIssuer();
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "csrf-test", Map.of("OIDC_ISSUER", issuer.issuer, "OIDC_AUDIENCE", "handoff")));
    context.register(SecurityFixture.class);
    context.refresh();
    security = context.getBean(FilterChainProxy.class);
  }

  @AfterEach
  void closeConfiguredSecurity() {
    SecurityContextHolder.clearContext();
    if (context != null) context.close();
    if (issuer != null) issuer.close();
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

  @Test
  void devBasicRetainsCliWritesWhileRejectingBrowserManagedCredentials() throws Exception {
    context.close();
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.getEnvironment().setActiveProfiles("dev");
    context.register(SecurityFixture.class);
    context.refresh();
    security = context.getBean(FilterChainProxy.class);
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("deployer:disposable-local-only".getBytes(StandardCharsets.UTF_8));
    var cli = request("POST");
    cli.addHeader("Authorization", basic);
    assertEquals(204, dispatch(cli).getStatus());
    var browser = request("POST");
    browser.addHeader("Authorization", basic);
    browser.addHeader("Origin", "https://unrelated.example");
    assertDenied(dispatch(browser));
    var cookie = request("POST");
    cookie.addHeader("Authorization", basic);
    cookie.addHeader("Cookie", "session=ambient");
    assertDenied(dispatch(cookie));
    assertEquals(1, applicationCalls.get());
  }

  private MockHttpServletRequest request(String method) {
    var request = new MockHttpServletRequest(context.getServletContext(), method, "/v1/csrf-probe");
    request.setServletPath("/v1/csrf-probe");
    return request;
  }

  private MockHttpServletRequest bearer(String method) throws Exception {
    var request = request(method);
    request.addHeader("Authorization", "Bearer " + issuer.token());
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

  /** Loopback issuer exercises the production protected decoder without any database dependency. */
  private static final class LocalIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;
    private final String issuer;

    LocalIssuer() throws Exception {
      key = new RSAKeyGenerator(2048).keyID("csrf-fixture").generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      issuer = "http://127.0.0.1:" + server.getAddress().getPort();
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      byte[] discovery =
          mapper.writeValueAsBytes(
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
                  List.of("RS256")));
      byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      for (var endpoint :
          Map.of("/.well-known/openid-configuration", discovery, "/jwks", keys).entrySet()) {
        server.createContext(
            endpoint.getKey(),
            exchange -> {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, endpoint.getValue().length);
              exchange.getResponseBody().write(endpoint.getValue());
              exchange.close();
            });
      }
      server.start();
    }

    String token() throws Exception {
      var signed =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              new JWTClaimsSet.Builder()
                  .issuer(issuer)
                  .subject("csrf-fixture")
                  .audience("handoff")
                  .claim("tenant", "demo")
                  .claim("scope", "deployer")
                  .issueTime(Date.from(Instant.now()))
                  .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                  .build());
      signed.sign(new RSASSASigner(key));
      return signed.serialize();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
