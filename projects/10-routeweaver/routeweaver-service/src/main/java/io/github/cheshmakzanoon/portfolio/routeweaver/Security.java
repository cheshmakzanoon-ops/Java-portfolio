package io.github.cheshmakzanoon.portfolio.routeweaver;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
  public static final int BODY_LIMIT = 16 * 1024 * 1024;

  public static OAuth2TokenValidator<Jwt> claims(String audience) {
    return jwt -> {
      Object tenant = jwt.getClaims().get("tenant"),
          sub = jwt.getClaims().get("sub"),
          aud = jwt.getClaims().get("aud");
      boolean typed =
          aud instanceof String
              || aud instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
      boolean valid =
          jwt.getExpiresAt() != null
              && sub instanceof String s
              && !s.isBlank()
              && tenant instanceof String t
              && t.matches("[a-zA-Z0-9_.-]{1,200}")
              && typed
              && jwt.getAudience() != null
              && jwt.getAudience().contains(audience);
      return valid
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "required exp/sub/aud/typed tenant invalid", null));
    };
  }

  public static void preserveClaimTypes(NimbusJwtDecoder decoder) {
    Map<String, org.springframework.core.convert.converter.Converter<Object, ?>> overrides =
        new HashMap<>();
    overrides.put("sub", value -> value);
    overrides.put("tenant", value -> value);
    overrides.put("aud", value -> value);
    decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(overrides));
  }

  public static JwtDecoder protectedDecoder(String issuer, String audience) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    preserveClaimTypes(decoder);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), claims(audience)));
    // Nimbus normalizes registered claims before its claim-set converter. Preserve raw JSON types
    // by checking the signed payload after cryptographic/issuer/time verification succeeds.
    return token -> {
      if (token == null || token.length() > 16384)
        throw new BadJwtException("JWT exceeds bounded size");
      Jwt verified = decoder.decode(token);
      try {
        String[] pieces = token.split("\\.", -1);
        Map<?, ?> raw =
            Json.read(
                new String(
                    Base64.getUrlDecoder().decode(pieces[1]),
                    java.nio.charset.StandardCharsets.UTF_8),
                Map.class);
        Object subject = raw.get("sub"), tenant = raw.get("tenant"), aud = raw.get("aud");
        boolean audienceType =
            aud instanceof String
                || aud instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
        if (!(subject instanceof String value)
            || value.isBlank()
            || !(tenant instanceof String t)
            || !t.matches("[a-zA-Z0-9_.-]{1,200}")
            || !audienceType
            || !(raw.get("exp") instanceof Number))
          throw new BadJwtException("invalid raw claim types");
        return verified;
      } catch (BadJwtException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new BadJwtException("invalid raw JWT payload", e);
      }
    };
  }

  /**
   * Bearer tokens are supplied explicitly, so they are not ambient browser credentials. Preserve
   * CSRF checks for cookies and browser-sent Basic credentials without changing CLI/Bearer APIs.
   */
  static boolean csrfForAmbientCredentials(jakarta.servlet.http.HttpServletRequest request) {
    if (java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod()))
      return false;
    if (request.getCookies() != null && request.getCookies().length > 0) return true;
    String cookie = request.getHeader("Cookie");
    if (cookie != null && !cookie.isBlank()) return true;
    String authorization = request.getHeader("Authorization");
    return authorization != null
        && authorization.regionMatches(true, 0, "Basic ", 0, 6)
        && (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null);
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      @Value("${routeweaver.auth-mode:protected}") String mode,
      @Value("${server.address:127.0.0.1}") String address,
      @Value("${routeweaver.issuer:}") String issuer,
      @Value("${routeweaver.audience:routeweaver}") String audience)
      throws Exception {
    http.csrf(
            csrf ->
                csrf.requireCsrfProtectionMatcher(Security::csrfForAmbientCredentials)
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<CsrfFilter>() {
                          @Override
                          public <O extends CsrfFilter> O postProcess(O filter) {
                            // Resource-server setup adds a bearer CSRF ignore rule. Restore the
                            // ambient-credential boundary on the fully assembled filter.
                            filter.setRequireCsrfProtectionMatcher(
                                Security::csrfForAmbientCredentials);
                            filter.setAccessDeniedHandler(
                                (request, response, denied) -> {
                                  // Complete the response without an authenticated /error dispatch.
                                  response.setStatus(403);
                                  response.setContentType("application/json");
                                  response.getWriter().write("{\"code\":\"FORBIDDEN\"}");
                                });
                            return filter;
                          }
                        }))
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    policy ->
                        policy.policyDirectives(
                            "default-src 'self'; object-src 'none'; base-uri 'none';"
                                + " frame-ancestors 'none'; form-action 'self'")))
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    if ("dev".equals(mode)) {
      if (!InetAddress.getByName(address).isLoopbackAddress())
        throw new IllegalArgumentException(
            "trusted disposable dev profile requires loopback binding");
      http.addFilterBefore(
          new OncePerRequestFilter() {
            protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
              if (!trustedLocalRequest(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"UNTRUSTED_LOCAL_REQUEST\"}");
                return;
              }
              var auth =
                  UsernamePasswordAuthenticationToken.authenticated(
                      "trusted-loopback-demo",
                      null,
                      List.of(
                          new SimpleGrantedAuthority("SCOPE_routeweaver.read"),
                          new SimpleGrantedAuthority("SCOPE_routeweaver.write")));
              SecurityContextHolder.getContext().setAuthentication(auth);
              chain.doFilter(request, response);
            }
          },
          AuthorizationFilter.class);
    } else {
      if (issuer.isBlank())
        throw new IllegalArgumentException(
            "protected profile requires routeweaver.issuer OIDC URL");
      JwtDecoder decoder = protectedDecoder(issuer, audience);
      http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)));
    }
    http.addFilterAfter(new BodyBound(), AuthorizationFilter.class)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/health", "/", "/index.html", "/app.js", "/styles.css")
                    .permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/**")
                    .hasAuthority("SCOPE_routeweaver.read")
                    .anyRequest()
                    .hasAuthority("SCOPE_routeweaver.write"));
    return http.build();
  }

  /**
   * Loopback binding does not prevent a browser on an unrelated site from reaching a local API.
   * Validate the Host against DNS rebinding and reject cross-origin browser requests before
   * granting the disposable demo identity. Local command-line clients need no Origin header.
   */
  static boolean trustedLocalRequest(HttpServletRequest request) {
    String host = request.getServerName();
    if (!Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host)) return false;
    if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) return false;
    String origin = request.getHeader("Origin");
    if (origin == null) return true;
    try {
      URI value = URI.create(origin);
      int port = value.getPort();
      if (port == -1) port = "https".equals(value.getScheme()) ? 443 : 80;
      return request.getScheme().equals(value.getScheme())
          && host.equalsIgnoreCase(value.getHost())
          && request.getServerPort() == port
          && value.getRawUserInfo() == null
          && (value.getRawPath() == null || value.getRawPath().isEmpty())
          && value.getRawQuery() == null
          && value.getRawFragment() == null;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  /** Bound raw bytes before JSON parsing, including chunked requests and false Content-Length. */
  public static final class BodyBound extends OncePerRequestFilter {
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      if (!Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
        chain.doFilter(request, response);
        return;
      }
      if (request.getContentLengthLong() > BODY_LIMIT) {
        response.sendError(413);
        return;
      }
      byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
      if (bytes.length > BODY_LIMIT) {
        response.sendError(413);
        return;
      }
      chain.doFilter(
          new HttpServletRequestWrapper(request) {
            public ServletInputStream getInputStream() {
              ByteArrayInputStream in = new ByteArrayInputStream(bytes);
              return new ServletInputStream() {
                public int read() {
                  return in.read();
                }

                public boolean isFinished() {
                  return in.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("synchronous bounded body");
                }
              };
            }

            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          },
          response);
    }
  }
}
