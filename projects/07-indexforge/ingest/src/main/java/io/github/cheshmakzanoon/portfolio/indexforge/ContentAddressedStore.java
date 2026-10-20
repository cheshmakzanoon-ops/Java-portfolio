package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

public final class ContentAddressedStore {
  public static final int MAX_OBJECT_BYTES = 1_048_576;
  private final Path root;

  public ContentAddressedStore(Path root) throws IOException {
    this.root = root;
    Files.createDirectories(root);
  }

  public String put(byte[] content) throws IOException {
    if (content == null || content.length > MAX_OBJECT_BYTES)
      throw new IOException("content too large or missing");
    String h = Digests.sha256(content);
    Path p = root.resolve(h);
    if (Files.exists(p)) {
      read(h);
      return h;
    }
    DurableFiles.atomicWrite(p, content);
    return h;
  }

  public long size(String digest) throws IOException {
    if (!digest.matches("[a-f0-9]{64}")) throw new IOException("invalid digest");
    long bytes = Files.size(root.resolve(digest));
    if (bytes > MAX_OBJECT_BYTES) throw new IOException("content too large");
    return bytes;
  }

  public byte[] read(String digest) throws IOException {
    size(digest);
    Path p = root.resolve(digest);
    byte[] b;
    try (var input = Files.newInputStream(p)) {
      b = input.readNBytes(MAX_OBJECT_BYTES + 1);
    }
    if (b.length > MAX_OBJECT_BYTES) throw new IOException("content too large");
    if (!Digests.sha256(b).equals(digest)) throw new IOException("content digest mismatch");
    return b;
  }

  public String text(String digest) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(read(digest)))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("invalid UTF-8", e);
    }
  }
}
