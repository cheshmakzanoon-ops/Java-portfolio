package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.util.*;

/** Stable binary request identity shared by cursors and the response cache. */
final class SearchRequestIdentity {
  private SearchRequestIdentity() {}

  static String digest(SearchEngine.Request request, QueryPlanner.Mode mode, String scoringVersion)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(
          2); // Versioned, length-prefixed UTF16 context; no delimiter or replacement aliases.
      writeContextString(out, request.text());
      out.writeInt(request.filters().size());
      for (var filter : new TreeMap<>(request.filters()).entrySet()) {
        writeContextString(out, filter.getKey());
        writeContextString(out, filter.getValue());
      }
      out.writeInt(request.pageSize());
      writeContextString(out, mode.name());
      writeContextString(out, scoringVersion);
    }
    return Digests.sha256(bytes.toByteArray());
  }

  private static void writeContextString(DataOutputStream out, String value) throws IOException {
    out.writeInt(value.length());
    out.writeChars(value);
  }
}
