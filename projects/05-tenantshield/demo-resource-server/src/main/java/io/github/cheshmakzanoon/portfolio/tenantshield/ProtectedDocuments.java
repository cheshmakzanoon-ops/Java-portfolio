package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class ProtectedDocuments {
  private static final tools.jackson.databind.json.JsonMapper ORIGINAL_CLAIMS =
      tools.jackson.databind.json.JsonMapper.builder(
              tools.jackson.core.json.JsonFactory.builder()
                  .streamReadConstraints(
                      tools.jackson.core.StreamReadConstraints.builder()
                          .maxDocumentLength(16384)
                          .maxNestingDepth(16)
                          .maxTokenCount(2048)
                          .maxStringLength(4096)
                          .maxNumberLength(64)
                          .build())
                  .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public static void main(String[] args) {
    SpringApplication.run(ProtectedDocuments.class, args);
  }

  private final DocumentAuthorization authorization;

  public ProtectedDocuments(Environment e) {
    authorization =
        new DocumentAuthorization(
            e.getProperty("tenantshield.decision-url", "http://127.0.0.1:18500"));
  }

  @Bean
  JwtDecoder decoder(Environment e) {
    String issuer = e.getRequiredProperty("tenantshield.oidc.issuer");
    String audience = e.getProperty("tenantshield.oidc.audience", "tenantshield");
    NimbusJwtDecoder d =
        NimbusJwtDecoder.withJwkSetUri(e.getRequiredProperty("tenantshield.oidc.jwks"))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtIssuerValidator(issuer),
            new JwtTimestampValidator(Duration.ZERO),
            jwt ->
                jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                        && jwt.getAudience() != null
                        && jwt.getAudience().contains(audience)
                        && jwt.getExpiresAt() != null
                        && jwt.getIssuedAt() != null
                        && !jwt.getIssuedAt().isAfter(Instant.now())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return token -> {
      if (token == null || token.length() > 16384) throw new BadJwtException("JWT size limit");
      Jwt verified = d.decode(token);
      // Inspect the original signed JSON only AFTER the mature decoder verifies trust and
      // signature.
      // Nimbus normalizes numeric subjects to strings before its claim-set converter runs.
      try {
        var raw =
            ORIGINAL_CLAIMS.readTree(
                com.nimbusds.jose.JWSObject.parse(token).getPayload().toBytes());
        var subject = raw.get("sub");
        if (!raw.isObject()
            || subject == null
            || !subject.isString()
            || subject.asString().isBlank()
            || subject.asString().length() > 128
            || !subject.asString().equals(verified.getSubject()))
          throw new BadJwtException("Original signed subject must be a bounded nonblank string");
        return verified;
      } catch (BadJwtException failure) {
        throw failure;
      } catch (java.text.ParseException | tools.jackson.core.JacksonException failure) {
        throw new BadJwtException("Invalid original signed claim types", failure);
      }
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity h) throws Exception {
    // This resource server only exposes GET; Spring's default CSRF protection stays on.
    return h.authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(ProtectedDocuments::unauthorized))
        .oauth2ResourceServer(
            o -> o.authenticationEntryPoint(ProtectedDocuments::unauthorized).jwt(j -> {}))
        .build();
  }

  @GetMapping("/v1/tenants/{tenant}/documents/{id}")
  public ResponseEntity<?> read(
      @PathVariable String tenant, @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
    if (!tenant.matches("[A-Za-z0-9_-]{1,128}") || !id.matches("[A-Za-z0-9:_-]{1,128}"))
      return problem(400, "invalid document reference");
    try {
      DocumentAuthorization.Decision decision =
          authorization.decide(tenant, id, jwt.getTokenValue());
      if (decision.allowed())
        return ResponseEntity.ok(
            Map.of("id", id, "content", "Synthetic portfolio document", "authorization", decision));
      return problem(403, "document access denied");
    } catch (RuntimeException e) {
      return problem(503, "authorization unavailable; operation refused");
    }
  }

  private static ResponseEntity<ProblemDetail> problem(int status, String detail) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
  }

  private static void unauthorized(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      org.springframework.security.core.AuthenticationException failure)
      throws java.io.IOException {
    response.setStatus(401);
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/problem+json");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"A valid trusted bearer token is required\"}");
  }
}
