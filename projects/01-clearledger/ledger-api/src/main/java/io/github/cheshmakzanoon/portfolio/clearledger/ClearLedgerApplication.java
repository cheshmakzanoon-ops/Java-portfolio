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

  @Bean
  SecurityFilterChain security(HttpSecurity h) throws Exception {
    return h.csrf(c -> c.disable())
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
        .build();
  }
}
