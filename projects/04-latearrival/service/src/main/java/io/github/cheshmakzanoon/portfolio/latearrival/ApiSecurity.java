package io.github.cheshmakzanoon.portfolio.latearrival;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ApiSecurity {
  @Bean
  SecurityFilterChain security(HttpSecurity http, Environment env) throws Exception {
    String mode = env.getProperty("pulse.security", "local");
    // GET-only telemetry API: retain Spring's CSRF defaults rather than disabling protection.
    // Never create a web session for the bearer-authenticated production resource server.
    http.sessionManagement(
        session ->
            session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    if (mode.equals("local")) {
      String address = env.getProperty("server.address", "127.0.0.1");
      if (!address.equals("127.0.0.1") && !address.equals("::1"))
        throw new IllegalStateException("LOCAL_PROFILE_REQUIRES_LOOPBACK");
      http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
    } else if (mode.equals("protected")) {
      String issuer = env.getRequiredProperty("pulse.oidc.issuer");
      String audience = env.getRequiredProperty("pulse.oidc.audience");
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(env.getRequiredProperty("pulse.oidc.jwks")).build();
      OAuth2TokenValidator<Jwt> aud =
          jwt ->
              java.util.Optional.ofNullable(jwt.getAudience())
                      .orElse(java.util.List.of())
                      .contains(audience)
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(
                      new OAuth2Error("invalid_token", "audience", null));
      OAuth2TokenValidator<Jwt> required =
          jwt ->
              jwt.getExpiresAt() != null
                      && jwt.getSubject() != null
                      && !jwt.getSubject().isBlank()
                      && jwt.getClaim("tenant") instanceof String tenant
                      && Engine.identifier(tenant)
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(
                      new OAuth2Error("invalid_token", "required_claims", null));
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer), aud, required));
      http.authorizeHttpRequests(
              a ->
                  a.requestMatchers("/health")
                      .permitAll()
                      .anyRequest()
                      .hasAuthority("SCOPE_telemetry:read"))
          .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(StrictIdentityDecoder.wrap(decoder))));
    } else throw new IllegalStateException("UNKNOWN_SECURITY_MODE");
    return http.build();
  }
}
