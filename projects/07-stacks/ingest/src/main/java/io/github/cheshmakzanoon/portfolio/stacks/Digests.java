package io.github.cheshmakzanoon.portfolio.stacks;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Digests {
  private Digests() {}

  public static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(java.nio.file.Path path) throws java.io.IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = java.nio.file.Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
