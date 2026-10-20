package io.github.cheshmakzanoon.portfolio.tenantshield;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class DecisionApplication {
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
    SpringApplication.run(DecisionApplication.class, args);
  }

  @Bean
  HikariDataSource runtimeDataSource(Environment e) {
    HikariConfig c = new HikariConfig();
    c.setJdbcUrl(e.getRequiredProperty("tenantshield.db.url"));
    c.setUsername(e.getProperty("tenantshield.db.user", "tenantshield_runtime"));
    c.setPassword(e.getRequiredProperty("tenantshield.db.password"));
    c.setMaximumPoolSize(8);
    c.addDataSourceProperty("socketTimeout", "5");
    c.setMinimumIdle(1);
    c.setConnectionTimeout(3000);
    c.setValidationTimeout(2000);
    c.setConnectionInitSql("SET statement_timeout='5000'");
    HikariDataSource ds = new HikariDataSource(c);
    try (var connection = ds.getConnection()) {
      RuntimeDatabase.verify(connection);
    } catch (java.sql.SQLException | RuntimeException ex) {
      ds.close();
      throw new IllegalStateException("runtime role validation failed", ex);
    }
    return ds;
  }

  @Bean
  PolicyStore store(HikariDataSource ds) {
    return new PolicyStore(ds);
  }

  @Bean
  JwtDecoder jwtDecoder(Environment e) {
    return trustedDecoder(
        e.getRequiredProperty("tenantshield.oidc.issuer"),
        e.getRequiredProperty("tenantshield.oidc.jwks"),
        e.getProperty("tenantshield.oidc.audience", "tenantshield"));
  }

  public static JwtDecoder trustedDecoder(String issuer, String jwks, String audience) {
    // The configured URI is fixed at startup. No unverified claim can trigger issuer discovery.
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    OAuth2TokenValidator<Jwt> required =
        jwt -> {
          boolean ok =
              jwt.getExpiresAt() != null
                  && jwt.getIssuedAt() != null
                  && !jwt.getIssuedAt().isAfter(Instant.now())
                  && jwt.getSubject() != null
                  && !jwt.getSubject().isBlank()
                  && jwt.getAudience() != null
                  && jwt.getAudience().contains(audience);
          return ok
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "required token claims invalid", null));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer), required));
    return token -> {
      if (token == null || token.length() > 16384) throw new BadJwtException("JWT size limit");
      Jwt verified = decoder.decode(token);
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
      } catch (BadJwtException e) {
        throw e;
      } catch (java.text.ParseException | tools.jackson.core.JacksonException e) {
        throw new BadJwtException("Invalid original signed claim types", e);
      }
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .authorizeHttpRequests(
            a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
        .exceptionHandling(e -> e.authenticationEntryPoint(DecisionApplication::unauthorized))
        .oauth2ResourceServer(
            o -> o.authenticationEntryPoint(DecisionApplication::unauthorized).jwt(j -> {}))
        .build();
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
