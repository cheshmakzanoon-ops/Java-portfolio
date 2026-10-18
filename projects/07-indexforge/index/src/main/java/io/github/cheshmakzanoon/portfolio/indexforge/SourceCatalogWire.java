package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Catalog worker frames:<=256KiB compressed and<=2MiB decoded, no object deserialization. */
public final class SourceCatalogWire {
  public static final int FRAME_LIMIT = 262_144, RAW_LIMIT = 2_097_152;

  private SourceCatalogWire() {}

  public static byte[] encode(SourceCatalogFacts.Unit unit) throws IOException {
    var raw = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(raw)) {
      out.writeUTF("jdk-api-source-v1");
      out.writeUTF(unit.packageName());
      strings(out, unit.imports());
      out.writeInt(unit.types().size());
      for (var type : unit.types()) {
        out.writeUTF(type.name());
        out.writeUTF(type.description());
        out.writeInt(type.members().size());
        for (var member : type.members()) {
          out.writeUTF(member.name());
          strings(out, member.parameters());
          out.writeUTF(member.returns());
          out.writeUTF(member.description());
          out.writeBoolean(member.field());
          if (raw.size() > RAW_LIMIT) throw new IOException("API raw frame quota");
        }
      }
    }
    if (raw.size() > RAW_LIMIT) throw new IOException("API raw frame quota");
    var zipped = new ByteArrayOutputStream();
    try (var zip = new DeflaterOutputStream(zipped)) {
      zip.write(raw.toByteArray());
    }
    if (zipped.size() > FRAME_LIMIT) throw new IOException("API compressed frame quota");
    return zipped.toByteArray();
  }

  public static SourceCatalogFacts.Unit decode(byte[] bytes) throws IOException {
    if (bytes.length > FRAME_LIMIT) throw new IOException("API frame quota");
    byte[] raw;
    try (var zip = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
      raw = zip.readNBytes(RAW_LIMIT + 1);
    }
    if (raw.length > RAW_LIMIT) throw new IOException("API expanded frame quota");
    try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
      if (!in.readUTF().equals("jdk-api-source-v1")) throw new IOException("API worker schema");
      String pkg = in.readUTF();
      var imports = strings(in, 256);
      List<SourceCatalogFacts.Type> types = new ArrayList<>();
      for (int n = count(in.readInt(), 128); n > 0; n--) {
        String name = in.readUTF(), doc = in.readUTF();
        List<SourceCatalogFacts.Member> members = new ArrayList<>();
        for (int m = count(in.readInt(), 4096); m > 0; m--)
          members.add(
              new SourceCatalogFacts.Member(
                  in.readUTF(), strings(in, 64), in.readUTF(), in.readUTF(), in.readBoolean()));
        types.add(new SourceCatalogFacts.Type(name, doc, members));
      }
      if (in.read() != -1) throw new IOException("API frame trailing data");
      return new SourceCatalogFacts.Unit(pkg, imports, types);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid API frame", invalid);
    }
  }

  private static void strings(DataOutputStream out, List<String> strings) throws IOException {
    out.writeInt(strings.size());
    for (String s : strings) out.writeUTF(s);
  }

  private static List<String> strings(DataInputStream in, int max) throws IOException {
    List<String> out = new ArrayList<>();
    for (int n = count(in.readInt(), max); n > 0; n--) out.add(in.readUTF());
    return out;
  }

  private static int count(int n, int max) throws IOException {
    if (n < 0 || n > max) throw new IOException("API worker count quota");
    return n;
  }
}
