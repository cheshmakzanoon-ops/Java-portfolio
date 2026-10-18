package io.github.cheshmakzanoon.portfolio.pulsestream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Enforce original signed JSON types after signature and standard claim validation. */
final class StrictIdentityDecoder {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(16384)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private StrictIdentityDecoder() {}

  static JwtDecoder wrap(JwtDecoder verifiedDecoder) {
    return token -> {
      if (token == null || token.length() > 16384) throw new BadJwtException("JWT size limit");
      var verified = verifiedDecoder.decode(token);
      try {
        var compact = token.split("\\.", -1);
        if (compact.length != 3) throw new IllegalArgumentException();
        Map<?, ?> raw = JSON.readValue(Base64.getUrlDecoder().decode(compact[1]), Map.class);
        if (!(raw.get("sub") instanceof String subject)
            || subject.isBlank()
            || subject.length() > 255
            || !(raw.get("tenant") instanceof String tenant)
            || !Engine.identifier(tenant)
            || !(raw.get("exp") instanceof Number)) throw new IllegalArgumentException();
        return verified;
      } catch (java.io.IOException | RuntimeException invalid) {
        throw new BadJwtException("Original signed identity claims invalid", invalid);
      }
    };
  }
}
