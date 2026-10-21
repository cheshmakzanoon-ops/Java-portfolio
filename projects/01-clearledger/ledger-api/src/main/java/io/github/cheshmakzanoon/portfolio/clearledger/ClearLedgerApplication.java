package io.github.cheshmakzanoon.portfolio.clearledger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class ClearLedgerApplication {
  @Bean
  org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictRequestJson() {
    return builder ->
        builder
            .withCoercionConfig(
                tools.jackson.databind.type.LogicalType.Textual,
                config -> {
                  for (var shape :
                      java.util.List.of(
                          tools.jackson.databind.cfg.CoercionInputShape.Integer,
                          tools.jackson.databind.cfg.CoercionInputShape.Float,
                          tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
                })
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
  }

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
      preserveIdentityTypes(decoder);
      decoder.setJwtValidator(validators(issuer));
      return StrictIdentityDecoder.wrap(decoder);
    }
    return protectedDecoder(issuer);
  }

  public static JwtDecoder protectedDecoder(String issuer) {
    var d = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
    preserveIdentityTypes(d);
    d.setJwtValidator(validators(issuer));
    return StrictIdentityDecoder.wrap(d);
  }

  static void preserveIdentityTypes(NimbusJwtDecoder decoder) {
    var defaults = MappedJwtClaimSetConverter.withDefaults(Map.of());
    decoder.setClaimSetConverter(
        raw -> {
          var converted = new HashMap<String, Object>(defaults.convert(raw));
          for (String name : List.of("sub", "tenant")) {
            if (raw.containsKey(name)) converted.put(name, raw.get(name));
            else converted.remove(name);
          }
          return converted;
        });
  }

  static OAuth2TokenValidator<Jwt> validators(String issuer) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        jwt -> {
          try {
            Object subject = jwt.getClaims().get("sub"), tenant = jwt.getClaims().get("tenant");
            if (!(subject instanceof String sub)
                || !LedgerText.valid(sub)
                || sub.isBlank()
                || sub.length() > 255
                || !(tenant instanceof String id)
                || id.length() != 36
                || !UUID.fromString(id).toString().equalsIgnoreCase(id))
              throw new IllegalArgumentException();
            if (!jwt.getAudience().contains("clearledger")
                || jwt.getExpiresAt() == null
                || jwt.getSubject() == null) throw new IllegalArgumentException();
            return OAuth2TokenValidatorResult.success();
          } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
          }
        });
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
  SecurityFilterChain security(HttpSecurity h) throws Exception {
    return h.csrf(csrf -> csrf.requireCsrfProtectionMatcher(ClearLedgerApplication::csrfForAmbientCredentials))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/health")
                    .permitAll()
                    .requestMatchers("/v1/reports/replay")
                    .hasAuthority("SCOPE_ledger.system")
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
        .sessionManagement(
            session ->
                session.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .build();
  }
}
