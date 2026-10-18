package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public record SnapshotManifest(
    String tenantId,
    String repositoryId,
    String revision,
    boolean complete,
    List<Entry> entries,
    Set<String> deletions) {
  public static final int SCHEMA = 1;

  public record Entry(
      String path,
      String sha256,
      String language,
      String kind,
      List<String> symbols,
      String coordinates,
      String version) {
    public Entry {
      path = normalize(path);
      if (!sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid digest");
      if (symbols.size() > 256) throw new IllegalArgumentException("symbol quota");
      symbols = List.copyOf(symbols);
      for (String symbol : symbols)
        if (symbol.isEmpty() || symbol.length() > 256)
          throw new IllegalArgumentException("symbol bounds");
      Objects.requireNonNull(language);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(coordinates);
      Objects.requireNonNull(version);
      if (language.length() > 32
          || kind.length() > 32
          || coordinates.length() > 256
          || version.length() > 128) throw new IllegalArgumentException("metadata field quota");
    }
  }

  public SnapshotManifest {
    if (entries.size() > 100_000 || deletions.size() > 100_000)
      throw new IllegalArgumentException("manifest quota");
    if (!tenantId.matches("[a-zA-Z0-9_-]{1,64}")
        || !repositoryId.matches("[a-zA-Z0-9_-]{1,64}")
        || revision.length() > 128) throw new IllegalArgumentException("invalid identity");
    entries = entries.stream().sorted(Comparator.comparing(Entry::path)).toList();
    TreeSet<String> ds = new TreeSet<>();
    deletions.forEach(p -> ds.add(normalize(p)));
    deletions = Collections.unmodifiableSet(ds);
    Set<String> seen = new HashSet<>();
    for (Entry e : entries)
      if (!seen.add(e.path()) || deletions.contains(e.path()))
        throw new IllegalArgumentException("duplicate or deleted entry");
    long metadata = 1024;
    for (Entry e : entries) {
      metadata +=
          128
              + 3L
                  * (e.path().length()
                      + e.language().length()
                      + e.kind().length()
                      + e.coordinates().length()
                      + e.version().length());
      for (String symbol : e.symbols()) metadata += 2 + 3L * symbol.length();
    }
    for (String deleted : deletions) metadata += 2 + 3L * deleted.length();
    if (metadata > 64L * 1024 * 1024)
      throw new IllegalArgumentException("manifest metadata byte quota");
  }

  public static String normalize(String path) {
    if (path == null
        || path.isBlank()
        || path.length() > 1024
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0
        || path.startsWith("/")
        || path.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("unsafe relative path");
    Path p = Path.of(path);
    if (p.isAbsolute()) throw new IllegalArgumentException("absolute path");
    for (Path part : p)
      if (part.toString().equals("..") || part.toString().equals("."))
        throw new IllegalArgumentException("path escape");
    String n = p.normalize().toString().replace('\\', '/');
    if (!n.equals(path)) throw new IllegalArgumentException("noncanonical path");
    return n;
  }

  public byte[] bytes() {
    try {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      try (DataOutputStream o = new DataOutputStream(b)) {
        o.writeInt(SCHEMA);
        o.writeUTF(tenantId);
        o.writeUTF(repositoryId);
        o.writeUTF(revision);
        o.writeBoolean(complete);
        var sorted = entries.stream().sorted(Comparator.comparing(Entry::path)).toList();
        o.writeInt(sorted.size());
        for (Entry e : sorted) {
          o.writeUTF(e.path);
          o.writeUTF(e.sha256);
          o.writeUTF(e.language);
          o.writeUTF(e.kind);
          o.writeInt(e.symbols.size());
          for (String s : e.symbols) o.writeUTF(s);
          o.writeUTF(e.coordinates);
          o.writeUTF(e.version);
        }
        o.writeInt(deletions.size());
        for (String d : new TreeSet<>(deletions)) o.writeUTF(d);
      }
      return b.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String digest() {
    return Digests.sha256(bytes());
  }

  public static SnapshotManifest read(byte[] bytes) throws IOException {
    if (bytes.length > 64 * 1024 * 1024) throw new IOException("manifest too large");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != SCHEMA) throw new IOException("schema mismatch");
      String t = in.readUTF(), r = in.readUTF(), v = in.readUTF();
      boolean full = in.readBoolean();
      int n = bounded(in.readInt(), 100_000);
      List<Entry> es = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        String p = in.readUTF(), h = in.readUTF(), l = in.readUTF(), k = in.readUTF();
        int sc = bounded(in.readInt(), 256);
        List<String> sy = new ArrayList<>();
        for (int j = 0; j < sc; j++) sy.add(in.readUTF());
        es.add(new Entry(p, h, l, k, sy, in.readUTF(), in.readUTF()));
      }
      int dc = bounded(in.readInt(), 100_000);
      Set<String> ds = new HashSet<>();
      for (int i = 0; i < dc; i++)
        if (!ds.add(in.readUTF())) throw new IOException("duplicate deletion");
      if (in.read() != -1) throw new IOException("trailing metadata");
      return new SnapshotManifest(t, r, v, full, es, ds);
    } catch (IllegalArgumentException | EOFException e) {
      throw new IOException("corrupt manifest", e);
    }
  }

  private static int bounded(int value, int max) throws IOException {
    if (value < 0 || value > max) throw new IOException("metadata quota");
    return value;
  }
}
