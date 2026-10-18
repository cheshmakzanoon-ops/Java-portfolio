package io.github.cheshmakzanoon.portfolio.durableflow;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.JWSObject;
import org.springframework.security.oauth2.jwt.*;

/**
 * Inspect original signed JSON after standard verification: registered-claim conversion can coerce
 * sub.
 */
public final class StrictJwtDecoder implements JwtDecoder {
  private final JwtDecoder verified;
  private final String issuer, audience;
  private final ObjectMapper parser =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(16)
                      .maxStringLength(2048)
                      .maxNumberLength(20)
                      .build())
              .build());

  public StrictJwtDecoder(JwtDecoder verified, String issuer, String audience) {
    this.verified = verified;
    this.issuer = issuer;
    this.audience = audience;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    if (token == null || token.length() > 8192)
      throw new BadJwtException("bounded signed token required");
    Jwt jwt = verified.decode(token);
    try {
      byte[] signed = JWSObject.parse(token).getPayload().toBytes();
      if (signed.length > 4096) throw new BadJwtException("claim payload limit");
      JsonNode claims = parser.readTree(signed),
          subject = claims.get("sub"),
          tenant = claims.get("tenant"),
          expiry = claims.get("exp"),
          iss = claims.get("iss"),
          aud = claims.get("aud");
      if (subject == null
          || !subject.isTextual()
          || subject.textValue().isBlank()
          || subject.textValue().length() > 128
          || tenant == null
          || !tenant.isTextual()
          || !tenant.textValue().matches("[a-zA-Z0-9-]{1,64}")
          || expiry == null
          || !expiry.isIntegralNumber()
          || !expiry.canConvertToLong()
          || iss == null
          || !iss.isTextual()
          || !issuer.equals(iss.textValue()))
        throw new BadJwtException("invalid original claim types");
      boolean contains = false;
      if (aud != null && aud.isTextual()) contains = audience.equals(aud.textValue());
      else if (aud != null && aud.isArray()) {
        for (JsonNode value : aud) {
          if (!value.isTextual()) throw new BadJwtException("audience type");
          contains |= audience.equals(value.textValue());
        }
      }
      if (!contains) throw new BadJwtException("audience type");
      for (String optional : new String[] {"nbf", "iat"}) {
        JsonNode value = claims.get(optional);
        if (value != null && (!value.isIntegralNumber() || !value.canConvertToLong()))
          throw new BadJwtException("date type");
      }
      return jwt;
    } catch (JwtException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new BadJwtException("invalid signed claims", failure);
    }
  }
}
