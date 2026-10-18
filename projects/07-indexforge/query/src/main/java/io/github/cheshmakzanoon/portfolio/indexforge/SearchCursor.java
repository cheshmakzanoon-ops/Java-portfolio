package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SearchCursor {
  public record Position(String generation, int offset) {}

  private final byte[] key;
  private final Clock clock;

  public SearchCursor(byte[] key, Clock clock) {
    if (key.length < 32) throw new IllegalArgumentException("cursor key");
    this.key = key.clone();
    this.clock = clock;
  }

  public String encode(AuthorizationFilter.Access a, String digest, String generation, int offset) {
    String payload =
        a.tenant()
            + "\n"
            + a.principal()
            + "\n"
            + a.version()
            + "\n"
            + digest
            + "\n"
            + generation
            + "\n"
            + offset
            + "\n"
            + (clock.instant().getEpochSecond() + 300);
    byte[] b = payload.getBytes(StandardCharsets.UTF_8);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(b));
  }

  public Position decode(String token, AuthorizationFilter.Access a, String digest) {
    if (token == null || token.length() > 2048) throw new IllegalArgumentException("cursor");
    try {
      String[] pieces = token.split("\\.");
      if (pieces.length != 2) throw new IllegalArgumentException("cursor");
      byte[] b = Base64.getUrlDecoder().decode(pieces[0]);
      if (!MessageDigest.isEqual(sign(b), Base64.getUrlDecoder().decode(pieces[1])))
        throw new IllegalArgumentException("cursor signature");
      String[] p = new String(b, StandardCharsets.UTF_8).split("\n");
      if (p.length != 7
          || !p[0].equals(a.tenant())
          || !p[1].equals(a.principal())
          || Long.parseLong(p[2]) != a.version()
          || !p[3].equals(digest)
          || Long.parseLong(p[6]) <= clock.instant().getEpochSecond()
          || !p[4].matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("cursor context");
      int offset = Integer.parseInt(p[5]);
      if (offset < 0 || offset > 500) throw new IllegalArgumentException("cursor offset");
      return new Position(p[4], offset);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid cursor");
    }
  }

  private byte[] sign(byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
