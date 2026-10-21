package io.github.cheshmakzanoon.portfolio.faultline.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** A single, unambiguous, bounded configuration document; never a target class loader. */
public final class BoundedJson {
  private static ObjectMapper mapper(int maximumBytes) {
    return new ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxStringLength(maximumBytes)
                        .maxNumberLength(32)
                        .build())
                .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private BoundedJson() {}

  public static JsonNode object(Path path, int maximumBytes, String label) throws IOException {
    if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE)
      throw new IllegalArgumentException("positive bounded document size required");
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException(label + " must be a regular file");
    if (Files.size(path) > maximumBytes) throw new IOException(label + " exceeds byte budget");
    byte[] bytes;
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(maximumBytes + 1);
    }
    if (bytes.length > maximumBytes) throw new IOException(label + " exceeds byte budget");
    JsonNode document;
    try {
      document = mapper(maximumBytes).readTree(bytes);
    } catch (IOException malformed) {
      // Jackson diagnostics may quote source values. Keep configuration contents out of CLI logs.
      throw new IOException(label + " must contain one valid, bounded JSON object", malformed);
    }
    if (document == null || !document.isObject())
      throw new IOException(label + " must contain one JSON object");
    return document;
  }
}
