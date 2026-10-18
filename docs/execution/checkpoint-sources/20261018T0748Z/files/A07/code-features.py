from pathlib import Path
b=Path('projects/07-indexforge')
def p(m,n):return b/m/'src/main/java/io/github/cheshmakzanoon/portfolio/indexforge'/f'{n}.java'
x=p('index','DeclarationScanner');s=x.read_text();s=s.replace('''  public static Set<String> names(String source) {''','''  static List<String> tokens(String source) {''',1);needle='''    Set<String> names = new LinkedHashSet<>();''';s=s.replace(needle,'''    return List.copyOf(tokens);
  }

  public static Set<String> names(String source) {
    List<String> tokens = tokens(source);
    Set<String> names = new LinkedHashSet<>();''',1);x.write_text(s)
p('index','CodeFeatures').write_text('''package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Conservative lexical API-topic hints, not type resolution or a behavioral correctness claim. */
public final class CodeFeatures {
  private CodeFeatures() {}
  private static final Map<String, String> TOPICS = Map.ofEntries(
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
  private static final Set<String> API_NAMES = Set.of("StandardCharsets", "CodingErrorAction",
      "MessageDigest", "Mac", "SecretKeySpec", "Files", "StandardCopyOption", "FileChannel",
      "ZipInputStream", "ZipFile", "DirectoryReader", "IndexSearcher", "AutoCloseable",
      "AtomicBoolean", "BooleanClause", "TermQuery", "Character");

  public static Set<String> features(String source) {
    List<String> t = DeclarationScanner.tokens(source);
    // Conservatively abstain when this file declares a type shadowing any recognized API name.
    for (int i = 1; i < t.size(); i++)
      if (Set.of("class", "interface", "record", "enum").contains(t.get(i - 1))
          && API_NAMES.contains(t.get(i))) return Set.of();
    Set<String> out = new TreeSet<>();
    if (has(t, "StandardCharsets", ".", "UTF_8") && has(t, "newDecoder", "(")
        && has(t, "CodingErrorAction", ".", "REPORT")) out.add("strict_utf8");
    if (has(t, "MessageDigest", ".", "getInstance", "(")) out.add("message_digest");
    if (has(t, "Mac", ".", "getInstance", "(") && has(t, "SecretKeySpec", "(")) out.add("mac");
    if (has(t, "Files", ".", "move", "(") && has(t, "StandardCopyOption", ".", "ATOMIC_MOVE")) out.add("atomic_move");
    if (has(t, "FileChannel", ".", "open", "(") && has(t, "force", "(")) out.add("durable_force");
    if (has(t, "ZipInputStream", "(") || has(t, "ZipFile", "(")) out.add("archive");
    if (has(t, "DirectoryReader", ".", "open", "(")
        || (has(t, "DirectoryReader") && has(t, "IndexSearcher", "("))) out.add("index_reader");
    if (has(t, "DirectoryReader") && has(t, "AutoCloseable") && has(t, "AtomicBoolean")
        && has(t, "compareAndSet", "(")) out.add("reader_lifetime");
    if (has(t, "BooleanClause", ".", "Occur", ".", "FILTER") && has(t, "TermQuery", "(")) out.add("query_filter");
    if (has(t, "Character", ".", "isWhitespace", "(") && has(t, "substring", "(")
        && has(t, "indexOf", "(")) out.add("lexical_scan");
    return Collections.unmodifiableSet(out);
  }

  private static boolean has(List<String> tokens, String... pattern) {
    return Collections.indexOfSubList(tokens, List.of(pattern)) >= 0;
  }

  public static String topics(Set<String> features) {
    return features.stream().sorted().map(TOPICS::get).collect(java.util.stream.Collectors.joining(" "));
  }

  public static String vocabulary() {
    return new TreeMap<>(TOPICS).values().stream().collect(java.util.stream.Collectors.joining(" "));
  }
}
''')
x=p('index','DocumentMapper');s=x.read_text().replace('''      for (String declaration : DeclarationScanner.names(s.content()))''','''      d.add(new TextField("code_features", CodeFeatures.topics(CodeFeatures.features(s.content())), Field.Store.NO));
      for (String declaration : DeclarationScanner.names(s.content()))''');x.write_text(s)
x=p('index','GenerationBuilder');s=x.read_text().replace('Map.of("prose", prose)','Map.of("prose", prose, "code_features", prose)');x.write_text(s)
x=p('query','RankingProfile');s=x.read_text().replace('  BM25_BOOLEAN(0, .75f);','''  BM25_BOOLEAN(0, .75f),
  CODE_NONE(0), CODE_LOW(1), CODE_MEDIUM(4), CODE_HIGH(12);''').replace('  public final float k1, b;','''  public final float k1, b;
  public final float codeFeatures;

  RankingProfile(float codeFeatures) {
    this(2, 6, 1, 32, 2, 2, 0, 2, .75f, codeFeatures);
  }''');s=s.replace('''      float b) {
    this.k1 = k1;''','''      float b) {
    this(identifiers, symbols, paths, declarations, prose, concepts, coverage, k1, b, 0);
  }

  RankingProfile(float identifiers, float symbols, float paths, float declarations,
      float prose, float concepts, float coverage, float k1, float b, float codeFeatures) {
    this.codeFeatures = codeFeatures;
    this.k1 = k1;''',1);x.write_text(s)
p('query','CodeFeatureQuery').write_text('''package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.util.*;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

/** Soft topic evidence from generic API token patterns; two distinct topic terms are required. */
public final class CodeFeatureQuery {
  private CodeFeatureQuery() {}
  private static final Set<String> VOCABULARY = normalized(CodeFeatures.vocabulary());

  private static Set<String> normalized(String text) {
    Set<String> terms = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer(); var stream = analyzer.tokenStream("code_features", text)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) terms.add(term.toString());
      stream.end();
    } catch (IOException failure) { throw new UncheckedIOException(failure); }
    return terms;
  }

  public static Optional<Query> plan(String raw) {
    if (raw == null) return Optional.empty();
    if (raw.length() > 512) throw new IllegalArgumentException("code hint query quota");
    if (!raw.contains(" ") || !raw.matches("[\\\\p{L}\\\\p{N}_$ -]+")
        || raw.matches(".*\\\\b(?:AND|OR|NOT)\\\\b.*")) return Optional.empty();
    Set<String> terms = normalized(raw);
    terms.retainAll(VOCABULARY);
    if (terms.size() < 2) return Optional.empty();
    var query = new BooleanQuery.Builder().setMinimumNumberShouldMatch(2);
    for (String term : terms) query.add(new TermQuery(new Term("code_features", term)), BooleanClause.Occur.SHOULD);
    return Optional.of(query.build());
  }
}
''')
x=p('query','QueryPlanner');s=x.read_text().replace('    NO_SIMILARITY\n','    NO_SIMILARITY,\n    NO_CODE_FEATURES\n').replace('source-v6-bm25','source-v7-code-heuristics');needle='''    lexical.visit(new ClauseBudget());''';s=s.replace(needle,'''    if (profile.codeFeatures > 0 && mode != Mode.BASELINE && mode != Mode.NO_CODE_FEATURES) {
      var hints = CodeFeatureQuery.plan(raw);
      if (hints.isPresent()) lexical = new BooleanQuery.Builder()
          .add(lexical, BooleanClause.Occur.SHOULD)
          .add(new BoostQuery(hints.get(), profile.codeFeatures), BooleanClause.Occur.SHOULD).build();
    }
    lexical.visit(new ClauseBudget());''');x.write_text(s)
x=p('evaluation','RelevanceEvaluator');s=x.read_text().replace('''            "GenerationLease",''','''            "GenerationLease",
            "CodeFeatures",
            "CodeFeatureQuery",''');x.write_text(s)
