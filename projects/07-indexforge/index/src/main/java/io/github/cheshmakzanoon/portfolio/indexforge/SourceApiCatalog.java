package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Immutable actual-JDK-prose catalog. It contains no hand-authored query/topic expansions. */
public final class SourceApiCatalog implements SourceSymbolGraph.Platform {
  public static final String SCHEMA = "jdk-api-prose-v1";
  public static final int MAX_BYTES = 33_554_432;

  public record Entry(
      String owner,
      String name,
      List<String> parameters,
      String returns,
      Set<String> terms,
      boolean field) {
    public Entry {
      SourceFacts.text(owner);
      SourceFacts.text(name);
      SourceFacts.text(returns);
      parameters = List.copyOf(parameters);
      terms = Collections.unmodifiableSet(new TreeSet<>(terms));
      if (parameters.size() > 64 || terms.size() > 128)
        throw new IllegalArgumentException("API entry quota");
      parameters.forEach(SourceFacts::text);
      terms.forEach(SourceFacts::text);
    }
  }

  private final String sourceHash, workerHash, jdk, license, identity;
  private final Set<String> types;
  private final List<Entry> entries;
  private final Map<String, List<Entry>> methods = new HashMap<>();
  private final Map<String, Entry> fields = new HashMap<>();

  SourceApiCatalog(
      String sourceHash,
      String workerHash,
      String jdk,
      String license,
      Set<String> types,
      List<Entry> entries)
      throws IOException {
    if (!sourceHash.matches("[a-f0-9]{64}")
        || !workerHash.matches("[a-f0-9]{64}")
        || jdk.length() > 256
        || license.length() > 131_072
        || types.size() > 16384
        || entries.size() > 131072) throw new IOException("API catalog metadata quota");
    this.sourceHash = sourceHash;
    this.workerHash = workerHash;
    this.jdk = jdk;
    this.license = license;
    types.forEach(
        type -> {
          SourceFacts.text(type);
          if (type.isEmpty()) throw new IllegalArgumentException("empty API type");
        });
    this.types = Collections.unmodifiableSet(new TreeSet<>(types));
    this.entries = List.copyOf(entries);
    Set<String> signatures = new HashSet<>();
    for (var entry : entries) {
      if (!types.contains(entry.owner())) throw new IOException("API entry owner not in catalog");
      StringBuilder key = new StringBuilder();
      for (String value :
          List.of(
              entry.owner(),
              entry.name(),
              Boolean.toString(entry.field()),
              Integer.toString(entry.parameters().size())))
        key.append(value.length()).append(':').append(value);
      for (String value : entry.parameters()) key.append(value.length()).append(':').append(value);
      String signature = key.toString();
      if (!signatures.add(signature)) throw new IOException("duplicate API signature");
      if (entry.field()) {
        if (!entry.parameters().isEmpty()
            || fields.put(entry.owner() + "#" + entry.name(), entry) != null)
          throw new IOException("API field signature");
      } else
        methods
            .computeIfAbsent(entry.owner() + "#" + entry.name(), k -> new ArrayList<>())
            .add(entry);
    }
    identity = Digests.sha256(payload());
  }

  @Override
  public Set<String> types() {
    return types;
  }

  @Override
  public Set<String> returns(String owner, String method, int arity) {
    Set<String> out = new TreeSet<>();
    for (var e : methods.getOrDefault(owner + "#" + method, List.of()))
      if (e.parameters().size() == arity) {
        if (e.returns().isEmpty()) return Set.of();
        out.add(e.returns());
      }
    return Collections.unmodifiableSet(out);
  }

  @Override
  public String fieldType(String owner, String field) {
    var entry = fields.get(owner + "#" + field);
    return entry == null ? null : entry.returns();
  }

  /** Exact catalog field only; inherited or spelling-based matches are not fabricated. */
  public Optional<Entry> field(String owner, String name) {
    return Optional.ofNullable(fields.get(owner + "#" + name));
  }

  /** API semantic evidence requires one exact supported signature, not an overload prose union. */
  public Optional<Entry> unique(String owner, String name, List<String> arguments) {
    var matching =
        methods.getOrDefault(owner + "#" + name, List.of()).stream()
            .filter(e -> e.parameters().equals(arguments))
            .toList();
    return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
  }

  public String identity() {
    return identity;
  }

  public String sourceHash() {
    return sourceHash;
  }

  public String workerHash() {
    return workerHash;
  }

  public String jdk() {
    return jdk;
  }

  public String license() {
    return license;
  }

  static SourceApiCatalog fromParsed(
      List<SourceCatalogFacts.Unit> units,
      String sourceHash,
      String workerHash,
      String jdk,
      String license)
      throws IOException {
    Set<String> types = new TreeSet<>();
    for (var unit : units)
      for (var type : unit.types()) {
        if (!types.add(type.name())) throw new IOException("duplicate JDK API type");
        if (types.size() > 16384) throw new IOException("API catalog type quota");
      }
    List<Entry> entries = new ArrayList<>();
    for (var unit : units)
      for (var type : unit.types())
        for (var member : type.members()) {
          String returns = resolve(member.returns(), type.name(), unit, types);
          List<String> parameters =
              member.parameters().stream()
                  .map(
                      p -> {
                        String resolved = resolve(p, type.name(), unit, types);
                        // An unresolved formal remains a distinct unsupported signature. Merging
                        // all
                        // generics/array element types into "" would falsely collapse overloads.
                        return resolved.isEmpty()
                            ? "unsupported-formal:" + Digests.sha256(p)
                            : resolved;
                      })
                  .toList();
          entries.add(
              new Entry(
                  type.name(),
                  member.name(),
                  parameters,
                  returns,
                  terms(type.description() + " " + member.description()),
                  member.field()));
          if (entries.size() > 131072) throw new IOException("API entry quota");
        }
    entries.sort(
        Comparator.comparing(Entry::owner)
            .thenComparing(Entry::name)
            .thenComparingInt(e -> e.parameters().size())
            .thenComparing(e -> String.join("\u0000", e.parameters()))
            .thenComparing(Entry::field));
    return new SourceApiCatalog(sourceHash, workerHash, jdk, license, types, entries);
  }

  private static String resolve(
      String raw, String owner, SourceCatalogFacts.Unit unit, Set<String> types) {
    String name = raw.strip();
    int arrays = 0;
    while (name.endsWith("[]")) {
      arrays++;
      name = name.substring(0, name.length() - 2);
    }
    int generic = name.indexOf('<');
    if (generic >= 0) name = name.substring(0, generic);
    if (name.length() > 256 || name.contains(" ") || name.contains("?") || name.contains("@"))
      return "";
    if (Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")
        .contains(name)) return name + "[]".repeat(arrays);
    if (types.contains(name)) return name + "[]".repeat(arrays);
    Set<String> options = new HashSet<>();
    for (String imported : unit.imports())
      if (!imported.endsWith(".*")
          && imported.substring(imported.lastIndexOf('.') + 1).equals(name)) options.add(imported);
    if (options.size() == 1) {
      String match = options.iterator().next();
      return types.contains(match) ? match + "[]".repeat(arrays) : "";
    }
    if (options.size() > 1) return "";
    String enclosing = owner;
    for (int depth = 0; depth < 16 && enclosing.contains("."); depth++) {
      String nested = enclosing + "." + name;
      if (types.contains(nested)) options.add(nested);
      enclosing = enclosing.substring(0, enclosing.lastIndexOf('.'));
    }
    if (types.contains(unit.packageName() + "." + name))
      options.add(unit.packageName() + "." + name);
    if (types.contains("java.lang." + name)) options.add("java.lang." + name);
    for (String imported : unit.imports())
      if (imported.endsWith(".*")) {
        String candidate = imported.substring(0, imported.length() - 1) + name;
        if (types.contains(candidate)) options.add(candidate);
      }
    return options.size() == 1 ? options.iterator().next() + "[]".repeat(arrays) : "";
  }

  static Set<String> terms(String text) throws IOException {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    try (var analyzer = new SourceProseAnalyzer();
        var tokens = analyzer.tokenStream("prose", text)) {
      var term = tokens.addAttribute(CharTermAttribute.class);
      tokens.reset();
      while (tokens.incrementToken()) {
        out.add(term.toString());
        if (out.size() == 128) break;
      }
      tokens.end();
    }
    return out;
  }

  private byte[] payload() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeUTF(SCHEMA);
      out.writeUTF(sourceHash);
      out.writeUTF(workerHash);
      out.writeUTF(jdk);
      byte[] legal = license.getBytes(StandardCharsets.UTF_8);
      out.writeInt(legal.length);
      out.write(legal);
      out.writeInt(types.size());
      for (String type : types) out.writeUTF(type);
      out.writeInt(entries.size());
      for (var e : entries) {
        out.writeUTF(e.owner());
        out.writeUTF(e.name());
        out.writeInt(e.parameters().size());
        for (String p : e.parameters()) out.writeUTF(p);
        out.writeUTF(e.returns());
        out.writeBoolean(e.field());
        out.writeInt(e.terms().size());
        for (String t : e.terms()) out.writeUTF(t);
        if (bytes.size() > MAX_BYTES - 32) throw new IOException("API catalog byte quota");
      }
    }
    if (bytes.size() > MAX_BYTES - 32) throw new IOException("API catalog byte quota");
    return bytes.toByteArray();
  }

  public void writeNew(Path file) throws IOException {
    byte[] body = payload();
    var bytes = new ByteArrayOutputStream();
    bytes.write(body);
    bytes.write(HexFormat.of().parseHex(Digests.sha256(body)));
    Files.write(file, bytes.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  public static SourceApiCatalog read(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
      throw new IOException("API catalog regular file required");
    byte[] data;
    try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      data = in.readNBytes(MAX_BYTES + 1);
    }
    if (data.length < 32 || data.length > MAX_BYTES)
      throw new IOException("API catalog byte quota");
    byte[] body = Arrays.copyOf(data, data.length - 32);
    if (!MessageDigest.isEqual(
        HexFormat.of().parseHex(Digests.sha256(body)),
        Arrays.copyOfRange(data, data.length - 32, data.length)))
      throw new IOException("API catalog checksum");
    try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
      if (!in.readUTF().equals(SCHEMA)) throw new IOException("API catalog schema");
      String source = in.readUTF(), worker = in.readUTF(), jdk = in.readUTF();
      int legalLength = count(in.readInt(), 524288);
      byte[] legal = in.readNBytes(legalLength);
      if (legal.length != legalLength) throw new EOFException("API license truncated");
      Set<String> types = new TreeSet<>();
      for (int n = count(in.readInt(), 16384); n > 0; n--)
        if (!types.add(in.readUTF())) throw new IOException("duplicate API type");
      List<Entry> entries = new ArrayList<>();
      for (int n = count(in.readInt(), 131072); n > 0; n--) {
        String owner = in.readUTF(), name = in.readUTF();
        List<String> parameters = new ArrayList<>();
        for (int p = count(in.readInt(), 64); p > 0; p--) parameters.add(in.readUTF());
        String returns = in.readUTF();
        boolean field = in.readBoolean();
        Set<String> terms = new TreeSet<>();
        for (int t = count(in.readInt(), 128); t > 0; t--)
          if (!terms.add(in.readUTF())) throw new IOException("duplicate API term");
        entries.add(new Entry(owner, name, parameters, returns, terms, field));
      }
      if (in.read() != -1) throw new IOException("API catalog trailing bytes");
      var result =
          new SourceApiCatalog(
              source, worker, jdk, new String(legal, StandardCharsets.UTF_8), types, entries);
      if (!Arrays.equals(body, result.payload())) throw new IOException("noncanonical API catalog");
      return result;
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid API catalog", invalid);
    }
  }

  private static int count(int n, int max) throws IOException {
    if (n < 0 || n > max) throw new IOException("API catalog count quota");
    return n;
  }
}
