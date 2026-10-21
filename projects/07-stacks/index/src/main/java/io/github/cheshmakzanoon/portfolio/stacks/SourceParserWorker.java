package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Fixed framed worker; source text cannot select commands, paths, classes or compiler phases. */
public final class SourceParserWorker {
  private SourceParserWorker() {}

  static byte[] ready() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(0x49465244);
      out.writeInt(1);
      out.writeUTF(SourceFacts.SCHEMA);
      out.writeUTF(SourceCatalogWire.SCHEMA);
    }
    if (bytes.size() > 256) throw new IOException("parser readiness quota");
    return bytes.toByteArray();
  }

  static void readReady(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 1 || length > 256) throw new IOException("parser readiness frame quota");
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) throw new EOFException("parser readiness truncated");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 0x49465244
          || in.readInt() != 1
          || !in.readUTF().equals(SourceFacts.SCHEMA)
          || !in.readUTF().equals(SourceCatalogWire.SCHEMA)
          || in.read() != -1) throw new IOException("parser readiness protocol/schema mismatch");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 0) throw new IllegalArgumentException("parser worker takes no arguments");
    try (var input = new DataInputStream(new BufferedInputStream(System.in));
        var output = new DataOutputStream(new BufferedOutputStream(System.out))) {
      // Fixed trusted syntax only: readiness includes compiler/DocTrees initialization, never
      // caller source, analysis, bytecode generation or annotation processing.
      if (!SourceJavaParser.parse("final class Warmup {}").declarationInventoryKnown())
        throw new IOException("parser warmup failed");
      SourceCatalogParser.parse(
          "/** Warmup documentation. */ public class Warmup { /** Opens observations. */ public void open(){} }");
      byte[] readiness = ready();
      output.writeInt(readiness.length);
      output.write(readiness);
      output.flush();
      for (int request = 0; request < 64; request++) {
        int length;
        try {
          length = input.readInt();
        } catch (EOFException ended) {
          return;
        }
        boolean catalog = length == -2;
        if (catalog) length = input.readInt();
        if (length < 0 || length > (catalog ? 2_097_152 : SourceFacts.SOURCE_BYTES))
          throw new IOException("parser input quota");
        byte[] source = input.readNBytes(length);
        if (source.length != length) throw new EOFException("parser frame truncated");
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
                .toString();
        byte[] result =
            catalog
                ? SourceCatalogWire.encode(SourceCatalogParser.parse(text))
                : SourceWire.encode(SourceJavaParser.parse(text));
        output.writeInt(result.length);
        output.write(result);
        output.flush();
      }
    }
  }
}
