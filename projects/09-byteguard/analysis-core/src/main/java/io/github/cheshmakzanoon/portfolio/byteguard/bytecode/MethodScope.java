package io.github.cheshmakzanoon.portfolio.byteguard.bytecode;

import java.security.*;
import java.util.HexFormat;

/** Fixed-size symbolic namespace; full readable identities remain in IR and witness metadata. */
public final class MethodScope {
  private MethodScope() {}

  public static String of(String method) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // Classfile modified UTF-8 preserves UTF-16 code units, including lone surrogates.
      // Feed those units directly so charset replacement cannot merge identities.
      for (int i = 0; i < method.length(); i++) {
        char unit = method.charAt(i);
        digest.update((byte) (unit >>> 8));
        digest.update((byte) unit);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
