package io.github.cheshmakzanoon.portfolio.faultline.input;

import java.util.*;

public record InputManifest(
    SortedMap<String, Entry> classes, List<String> diagnostics, long expandedBytes) {
  /**
   * Immutable byte ownership. Analysis clients must not alter accepted class bytes after their
   * provenance digest was calculated or change values through the manifest's public map.
   */
  public record Entry(String logicalName, byte[] bytes, String sha256, boolean application) {
    public Entry {
      bytes = Objects.requireNonNull(bytes, "class bytes").clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Entry entry
          && Objects.equals(logicalName, entry.logicalName)
          && Objects.equals(sha256, entry.sha256)
          && application == entry.application
          && Arrays.equals(bytes, entry.bytes);
    }

    @Override
    public int hashCode() {
      int hash = Objects.hashCode(logicalName);
      hash = 31 * hash + Arrays.hashCode(bytes);
      hash = 31 * hash + Objects.hashCode(sha256);
      return 31 * hash + Boolean.hashCode(application);
    }
  }

  public InputManifest {
    classes = Collections.unmodifiableSortedMap(new TreeMap<>(classes));
    diagnostics = List.copyOf(diagnostics);
  }
}
