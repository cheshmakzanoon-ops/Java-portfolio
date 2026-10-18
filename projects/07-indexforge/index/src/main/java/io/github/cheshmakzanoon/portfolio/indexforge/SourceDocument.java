package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

public record SourceDocument(
    String tenant,
    String repository,
    String revision,
    SnapshotManifest.Entry entry,
    String content) {
  public String key() {
    return tenant + "/" + repository + "/" + entry.path();
  }
}
