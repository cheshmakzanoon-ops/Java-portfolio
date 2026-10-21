package io.github.cheshmakzanoon.portfolio.faultline.input;

import java.util.*;

/** Explicit application/library provenance with an ambiguity-rejecting duplicate policy. */
public final class ClasspathCatalog {
  private final SortedMap<String, InputManifest.Entry> entries = new TreeMap<>();

  public void register(InputManifest.Entry entry) throws InputFailure {
    if (entries.putIfAbsent(entry.logicalName(), entry) != null)
      throw new InputFailure("duplicate logical class: " + entry.logicalName());
  }

  public SortedMap<String, InputManifest.Entry> entries() {
    return Collections.unmodifiableSortedMap(entries);
  }
}
