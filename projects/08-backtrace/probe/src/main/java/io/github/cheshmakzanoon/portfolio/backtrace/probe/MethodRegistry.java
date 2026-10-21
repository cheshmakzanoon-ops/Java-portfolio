package io.github.cheshmakzanoon.portfolio.backtrace.probe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Registration happens during transformation, never on the application method hot path. */
public final class MethodRegistry {
  private final int limit;
  private final Map<String, Integer> identities = new TreeMap<>();
  private final Map<Integer, String> names = new TreeMap<>();
  private long overflow;

  public MethodRegistry(int limit) {
    if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("method limit 1..10000");
    this.limit = limit;
  }

  public synchronized int register(String identity) {
    String key = stableIdentity(identity);
    Integer old = identities.get(key);
    if (old != null) return old;
    if (identities.size() >= limit) {
      overflow++;
      return -1;
    }
    int id = identities.size();
    identities.put(key, id);
    String bounded =
        identity.length() <= 256
            ? identity
            : identity.substring(0, 191) + "#" + stableIdentity(identity);
    names.put(id, bounded);
    return id;
  }

  public synchronized Map<Integer, String> snapshot() {
    return Map.copyOf(names);
  }

  public synchronized long overflow() {
    return overflow;
  }

  public static String stableIdentity(String signature) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(signature.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
