package io.github.cheshmakzanoon.portfolio.faultline.input;

import java.io.*;
import java.util.*;
import java.util.jar.*;

/** Selects one logical application class per JAR manifest/target-release semantics. */
public final class MultiReleaseSelector {
  public SortedMap<String, byte[]> select(Map<String, byte[]> raw, int release) throws IOException {
    boolean multi = false;
    byte[] manifest = raw.get("META-INF/MANIFEST.MF");
    if (manifest != null)
      multi =
          "true"
              .equalsIgnoreCase(
                  new Manifest(new ByteArrayInputStream(manifest))
                      .getMainAttributes()
                      .getValue("Multi-Release"));
    Map<String, Integer> versions = new HashMap<>();
    SortedMap<String, byte[]> selected = new TreeMap<>();
    for (var e : raw.entrySet()) {
      String n = e.getKey();
      if (!n.endsWith(".class")) continue;
      int v = 0;
      if (n.startsWith("META-INF/versions/")) {
        if (!multi) continue;
        String tail = n.substring(18);
        int slash = tail.indexOf('/');
        if (slash < 1) throw new InputFailure("invalid multi-release entry");
        try {
          v = Integer.parseInt(tail.substring(0, slash));
        } catch (NumberFormatException ex) {
          throw new InputFailure("invalid multi-release version");
        }
        if (!Integer.toString(v).equals(tail.substring(0, slash)))
          throw new InputFailure("noncanonical multi-release version");
        if (v < 9 || v > release) continue;
        n = tail.substring(slash + 1);
      }
      if (v >= versions.getOrDefault(n, -1)) {
        versions.put(n, v);
        selected.put(n, e.getValue());
      }
    }
    return Collections.unmodifiableSortedMap(selected);
  }
}
