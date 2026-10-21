package io.github.cheshmakzanoon.portfolio.indexforge;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(Environment env, ObjectProvider<LocalOidcIssuer> local) {
    LocalOidcIssuer dev = local.getIfAvailable();
    String issuer = dev == null ? env.getRequiredProperty("indexforge.issuer") : dev.issuer();
    String jwks = dev == null ? env.getRequiredProperty("indexforge.jwks") : issuer + "/jwks";
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), strictClaims("indexforge")));
    return token -> {
      if (token == null || token.length() > 16384) throw new BadJwtException("token bound");
      Jwt jwt =
          decoder.decode(token); // Cryptography, issuer and timestamps precede raw-type inspection.
      try {
        Map<String, Object> raw =
            com.nimbusds.jose.JWSObject.parse(token).getPayload().toJSONObject();
        Object sub = raw.get("sub"),
            tenant = raw.get("tenant"),
            aud = raw.get("aud"),
            expiration = raw.get("exp"),
            scope = raw.get("scope");
        boolean audienceType =
            aud instanceof String
                || aud instanceof List<?> list
                    && !list.isEmpty()
                    && list.stream()
                        .allMatch(value -> value instanceof String text && !text.isBlank());
        if (!(sub instanceof String subject)
            || subject.isBlank()
            || subject.length() > 256
            || !(tenant instanceof String t)
            || !t.matches("[a-zA-Z0-9_-]{1,64}")
            || !audienceType
            || !(expiration instanceof Number)
            || !(scope instanceof String)) throw new BadJwtException("signed claim type");
        return jwt;
      } catch (java.text.ParseException | RuntimeException invalid) {
        throw new BadJwtException("signed claims invalid", invalid);
      }
    };
  }

  public static OAuth2TokenValidator<Jwt> strictClaims(String audience) {
    return jwt -> {
      try {
        Object tenant = jwt.getClaims().get("tenant");
        Object subject = jwt.getClaims().get("sub"), audienceClaim = jwt.getClaims().get("aud");
        boolean audienceType =
            audienceClaim instanceof String
                || audienceClaim instanceof List<?> list
                    && !list.isEmpty()
                    && list.stream().allMatch(value -> value instanceof String);
        Object scope = jwt.getClaims().get("scope");
        if (!(subject instanceof String sub)
            || sub.isBlank()
            || !audienceType
            || jwt.getExpiresAt() == null
            || jwt.getSubject() == null
            || jwt.getSubject().isBlank()
            || jwt.getAudience() == null
            || !jwt.getAudience().contains(audience)
            || !(tenant instanceof String t)
            || !t.matches("[a-zA-Z0-9_-]{1,64}")
            || !(scope instanceof String s)
            || !Arrays.asList(s.split(" ")).contains("search")
            || !jwt.getExpiresAt().isAfter(Instant.now())) return invalid();
        return OAuth2TokenValidatorResult.success();
      } catch (RuntimeException e) {
        return invalid();
      }
    };
  }

  private static OAuth2TokenValidatorResult invalid() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "required claims invalid", null));
  }


  /**
   * Bearer tokens are supplied explicitly, so they are not ambient browser credentials. Preserve
   * CSRF checks for cookies and browser-sent Basic credentials without changing CLI/Bearer APIs.
   */
  static boolean csrfForAmbientCredentials(jakarta.servlet.http.HttpServletRequest request) {
    if (java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod()))
      return false;
    if (request.getCookies() != null && request.getCookies().length > 0) return true;
    String authorization = request.getHeader("Authorization");
    return authorization != null
        && authorization.regionMatches(true, 0, "Basic ", 0, 6)
        && (request.getHeader("Origin") != null
            || request.getHeader("Sec-Fetch-Site") != null);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(SecurityConfiguration::csrfForAmbientCredentials))
        .authorizeHttpRequests(
            requests ->
                requests.requestMatchers("/health").permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }
}
