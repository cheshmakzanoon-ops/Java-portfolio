package io.github.cheshmakzanoon.portfolio.clearledger;

import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class ClearLedgerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClearLedgerApplication.class, args);
  }

  @Bean
  LedgerStore ledgerStore(org.springframework.core.env.Environment env) {
    String url =
        env.getProperty(
            "ledger.url",
            System.getenv()
                .getOrDefault("LEDGER_URL", "jdbc:postgresql://127.0.0.1:18101/clearledger"));
    if ("true".equals(System.getenv("LEDGER_MIGRATE")))
      LedgerStore.migrate(
          url,
          "postgres",
          System.getenv().getOrDefault("LEDGER_ADMIN_PASSWORD", "local-only-admin"));
    return new LedgerStore(
        url,
        "clearledger_runtime",
        System.getenv().getOrDefault("LEDGER_PASSWORD", "local-only-clearledger"));
  }

  @Bean
  JwtDecoder decoder(org.springframework.core.env.Environment env) {
    String issuer = env.getProperty("ledger.issuer", "http://127.0.0.1:18109/dev-issuer");
    boolean dev = env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"));
    if (dev) {
      String address = env.getProperty("server.address", "127.0.0.1");
      if (!Set.of("127.0.0.1", "::1", "localhost").contains(address))
        throw new IllegalStateException("DEV_REQUIRES_LOOPBACK");
      var decoder =
          NimbusJwtDecoder.withSecretKey(
                  new SecretKeySpec(
                      "clearledger-disposable-local-demo-key-32-bytes"
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      decoder.setJwtValidator(validators(issuer));
      return decoder;
    }
    return protectedDecoder(issuer);
  }

  public static JwtDecoder protectedDecoder(String issuer) {
    var d = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
    d.setJwtValidator(validators(issuer));
    return d;
  }

  static OAuth2TokenValidator<Jwt> validators(String issuer) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        jwt -> {
          try {
            UUID.fromString(jwt.getClaimAsString("tenant"));
            if (!jwt.getAudience().contains("clearledger")
                || jwt.getExpiresAt() == null
                || jwt.getSubject() == null) throw new IllegalArgumentException();
            return OAuth2TokenValidatorResult.success();
          } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
          }
        });
  }

  @Bean
  SecurityFilterChain security(HttpSecurity h) throws Exception {
    return h.csrf(c -> c.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/health")
                    .permitAll()
                    .requestMatchers("/v1/reports/**")
                    .hasAuthority("SCOPE_ledger.admin")
                    .requestMatchers("/v1/reconciliation/**", "/v1/statements/**")
                    .hasAuthority("SCOPE_ledger.reconcile")
                    .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/**")
                    .hasAuthority("SCOPE_ledger.read")
                    .requestMatchers("/v1/**")
                    .hasAuthority("SCOPE_ledger.write")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }
}
