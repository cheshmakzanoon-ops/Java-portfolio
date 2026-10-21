package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;

/** Conservative lexical API-topic hints, not type resolution or a behavioral correctness claim. */
public final class CodeFeatures {
  private CodeFeatures() {}

  private static final Map<String, String> TOPICS =
      Map.ofEntries(
          Map.entry("strict_utf8", "strict utf8 decode malformed encoding unicode text"),
          Map.entry("message_digest", "digest checksum hash cryptographic integrity"),
          Map.entry("mac", "signature signed authentication token integrity"),
          Map.entry("atomic_move", "atomic rename filesystem publication replace switch"),
          Map.entry("durable_force", "durable persist flush force fsync storage"),
          Map.entry("archive", "archive zip compressed entries expansion"),
          Map.entry("index_reader", "index generation snapshot reader search"),
          Map.entry("reader_lifetime", "lease reader lifetime references retire release close"),
          Map.entry("query_filter", "filter restriction query predicate candidate"),
          Map.entry("lexical_scan", "lexical parsing parser tokens text grammar expression input"));
  private static final Set<String> TYPES = Set.of("class", "interface", "record", "enum");
  private static final Set<String> API_NAMES =
      Set.of(
          "StandardCharsets",
          "CodingErrorAction",
          "MessageDigest",
          "Mac",
          "SecretKeySpec",
          "Files",
          "StandardCopyOption",
          "FileChannel",
          "ZipInputStream",
          "ZipFile",
          "DirectoryReader",
          "IndexSearcher",
          "AutoCloseable",
          "AtomicBoolean",
          "BooleanClause",
          "TermQuery",
          "Character");

  public static Set<String> features(String source) {
    List<String> t = DeclarationScanner.tokens(source);
    // Conservatively abstain when this file declares a type shadowing any recognized API name.
    for (int i = 1; i < t.size(); i++)
      if (TYPES.contains(t.get(i - 1)) && API_NAMES.contains(t.get(i))) return Set.of();
    Set<String> out = new TreeSet<>();
    if (has(t, "StandardCharsets", ".", "UTF_8")
        && has(t, "newDecoder", "(")
        && has(t, "CodingErrorAction", ".", "REPORT")) out.add("strict_utf8");
    if (has(t, "MessageDigest", ".", "getInstance", "(")) out.add("message_digest");
    if (has(t, "Mac", ".", "getInstance", "(") && has(t, "SecretKeySpec", "(")) out.add("mac");
    if (has(t, "Files", ".", "move", "(") && has(t, "StandardCopyOption", ".", "ATOMIC_MOVE"))
      out.add("atomic_move");
    if (has(t, "FileChannel", ".", "open", "(") && has(t, "force", "(")) out.add("durable_force");
    if (has(t, "ZipInputStream", "(") || has(t, "ZipFile", "(")) out.add("archive");
    if (has(t, "DirectoryReader", ".", "open", "(")
        || (has(t, "DirectoryReader") && has(t, "IndexSearcher", "("))) out.add("index_reader");
    if (has(t, "DirectoryReader")
        && has(t, "AutoCloseable")
        && has(t, "AtomicBoolean")
        && has(t, "compareAndSet", "(")) out.add("reader_lifetime");
    if (has(t, "BooleanClause", ".", "Occur", ".", "FILTER") && has(t, "TermQuery", "("))
      out.add("query_filter");
    if (has(t, "Character", ".", "isWhitespace", "(")
        && has(t, "substring", "(")
        && has(t, "indexOf", "(")) out.add("lexical_scan");
    return Collections.unmodifiableSet(out);
  }

  private static boolean has(List<String> tokens, String... pattern) {
    return Collections.indexOfSubList(tokens, List.of(pattern)) >= 0;
  }

  public static String topics(Set<String> features) {
    return features.stream()
        .sorted()
        .map(TOPICS::get)
        .collect(java.util.stream.Collectors.joining(" "));
  }

  public static String vocabulary() {
    return new TreeMap<>(TOPICS)
        .values().stream().collect(java.util.stream.Collectors.joining(" "));
  }
}
