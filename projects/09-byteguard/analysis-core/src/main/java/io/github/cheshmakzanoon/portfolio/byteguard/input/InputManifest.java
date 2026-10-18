package io.github.cheshmakzanoon.portfolio.byteguard.input;

import java.util.*;

public record InputManifest(
    SortedMap<String, Entry> classes, List<String> diagnostics, long expandedBytes) {
  public record Entry(String logicalName, byte[] bytes, String sha256, boolean application) {}

  public InputManifest {
    classes = Collections.unmodifiableSortedMap(new TreeMap<>(classes));
    diagnostics = List.copyOf(diagnostics);
  }
}
