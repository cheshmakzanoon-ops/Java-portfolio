package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

public final class QueryPlanner {
  public enum Mode {
    BASELINE,
    SOURCE_AWARE,
    NO_IDENTIFIERS,
    NO_PATH,
    NO_PROSE,
    NO_CONCEPTS,
    NO_COVERAGE,
    NO_SIMILARITY,
    NO_CODE_FEATURES,
    NO_MEMBER_SCOPE,
    NO_API_DOCS,
    NO_RELATIONS,
    NO_MEMBER_USAGE;

    public static List<Mode> legacyValues() {
      return List.of(
          BASELINE,
          SOURCE_AWARE,
          NO_IDENTIFIERS,
          NO_PATH,
          NO_PROSE,
          NO_CONCEPTS,
          NO_COVERAGE,
          NO_SIMILARITY,
          NO_CODE_FEATURES);
    }
  }

  public static final String VERSION = "source-v9-bounded-structure";

  private final RankingProfile profile;
  private final RankingStrategy strategy;

  public QueryPlanner() {
    this(RankingProfile.current());
  }

  public QueryPlanner(RankingProfile profile) {
    this(profile, RankingStrategy.CLASSIC);
  }

  public QueryPlanner(RankingProfile profile, RankingStrategy strategy) {
    this.profile = Objects.requireNonNull(profile);
    this.strategy = Objects.requireNonNull(strategy);
  }

  public Query plan(QueryAst ast, Mode mode) {
    return plan(ast, mode, null);
  }

  public Query plan(QueryAst ast, Mode mode, String raw) {
    boolean navigational =
        ast instanceof QueryAst.Term term
            && term.field().equals("all")
            && !term.phrase()
            && term.value().matches("[\\p{L}_$][\\p{L}\\p{N}_$]{0,127}");
    Query lexical = plan(ast, mode, navigational, strategy.suppressIdentifierWeights(raw));
    if (mode != Mode.BASELINE && mode != Mode.NO_CONCEPTS && mode != Mode.NO_PROSE) {
      var related = SearchConcepts.expansion(raw);
      if (related.isPresent())
        lexical =
            new BooleanQuery.Builder()
                .add(lexical, BooleanClause.Occur.SHOULD)
                .add(new BoostQuery(related.get(), profile.concepts), BooleanClause.Occur.SHOULD)
                .build();
    }
    if (profile.codeFeatures > 0 && mode != Mode.BASELINE && mode != Mode.NO_CODE_FEATURES) {
      var hints = CodeFeatureQuery.plan(raw);
      if (hints.isPresent())
        lexical =
            new BooleanQuery.Builder()
                .add(lexical, BooleanClause.Occur.SHOULD)
                .add(new BoostQuery(hints.get(), profile.codeFeatures), BooleanClause.Occur.SHOULD)
                .build();
    }
    lexical.visit(new ClauseBudget());
    return lexical;
  }

  Map<String, Query> featureChannels(String raw) {
    QueryAst.parse(raw);
    Set<String> words = new LinkedHashSet<>();
    for (var part : CodeIdentifierAnalyzer.parts(raw)) {
      words.add(part.term());
      if (words.size() > 64) throw new IllegalArgumentException("feature term quota");
    }
    var symbol = new BooleanQuery.Builder();
    var declaration = new BooleanQuery.Builder();
    for (String word : words) {
      symbol.add(new TermQuery(new Term("symbol", word)), BooleanClause.Occur.SHOULD);
      declaration.add(new TermQuery(new Term("declaration", word)), BooleanClause.Occur.SHOULD);
    }
    Query identifiers =
        new BooleanQuery.Builder()
            .add(analyzed("identifiers", raw, false), BooleanClause.Occur.SHOULD)
            .add(symbol.build(), BooleanClause.Occur.SHOULD)
            .build();
    Map<String, Query> result = new LinkedHashMap<>();
    result.put("content", analyzed("content", raw, false));
    result.put("prose", prose(raw, false));
    result.put("identifiers", identifiers);
    result.put("path", analyzed("path_tokens", raw, false));
    result.put("code", CodeFeatureQuery.plan(raw).orElseGet(MatchNoDocsQuery::new));
    result.put("phrase", prose(raw, true));
    result.put("declaration", declaration.build());
    for (Query query : result.values()) query.visit(new ClauseBudget());
    return Collections.unmodifiableMap(result);
  }

  private static final class ClauseBudget extends QueryVisitor {
    private int terms;

    @Override
    public void consumeTerms(Query query, Term... values) {
      terms += values.length;
      if (terms > 768) throw new IllegalArgumentException("expanded query quota");
    }

    @Override
    public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
      return this;
    }
  }

  private Query plan(QueryAst ast, Mode mode, boolean navigational, boolean suppressIdentifiers) {
    if (ast instanceof QueryAst.BooleanNode n)
      return new BooleanQuery.Builder()
          .add(
              plan(n.left(), mode, navigational, suppressIdentifiers),
              n.or() ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST)
          .add(
              plan(n.right(), mode, navigational, suppressIdentifiers),
              n.or() ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST)
          .build();
    if (ast instanceof QueryAst.Negation n)
      return new BooleanQuery.Builder()
          .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
          .add(
              plan(n.child(), mode, navigational, suppressIdentifiers),
              BooleanClause.Occur.MUST_NOT)
          .build();
    QueryAst.Term t = (QueryAst.Term) ast;
    String value = t.value().toLowerCase(Locale.ROOT);
    if (!t.field().equals("all") && !t.field().equals("content"))
      return new TermQuery(new Term(t.field(), t.field().equals("symbol") ? value : t.value()));
    if (t.field().equals("content") || mode == Mode.BASELINE)
      return analyzed("content", value, t.phrase());
    BooleanQuery.Builder b =
        new BooleanQuery.Builder()
            .add(analyzed("content", value, t.phrase()), BooleanClause.Occur.SHOULD);
    if (mode != Mode.NO_PROSE)
      b.add(new BoostQuery(prose(value, t.phrase()), profile.prose), BooleanClause.Occur.SHOULD);
    if (mode != Mode.NO_IDENTIFIERS && !suppressIdentifiers) {
      if (navigational)
        b.add(
            new BoostQuery(new TermQuery(new Term("declaration", value)), profile.declarations),
            BooleanClause.Occur.SHOULD);
      b.add(
          new BoostQuery(analyzed("identifiers", value, t.phrase()), profile.identifiers),
          BooleanClause.Occur.SHOULD);
      b.add(
          new BoostQuery(new TermQuery(new Term("symbol", value)), profile.symbols),
          BooleanClause.Occur.SHOULD);
    }
    if (mode != Mode.NO_PATH)
      b.add(
          new BoostQuery(analyzed("path_tokens", value, t.phrase()), profile.paths),
          BooleanClause.Occur.SHOULD);
    return b.build();
  }

  private Query prose(String value, boolean phrase) {
    try (var analyzer = new SourceProseAnalyzer();
        var stream = analyzer.tokenStream("prose", value)) {
      var term =
          stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      var increment =
          stream.addAttribute(
              org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute.class);
      Map<Integer, Set<Term>> positions = new TreeMap<>();
      int position = -1;
      stream.reset();
      while (stream.incrementToken()) {
        position += increment.getPositionIncrement();
        positions
            .computeIfAbsent(position, ignored -> new LinkedHashSet<>())
            .add(new Term("prose", term.toString()));
      }
      stream.end();
      if (positions.isEmpty()) return new MatchNoDocsQuery();
      if (phrase) {
        var query = new MultiPhraseQuery.Builder();
        int first = positions.keySet().iterator().next();
        for (var entry : positions.entrySet())
          query.add(entry.getValue().toArray(Term[]::new), entry.getKey() - first);
        return query.build();
      }
      var query = new BooleanQuery.Builder();
      for (var alternatives : positions.values())
        for (Term word : alternatives) query.add(new TermQuery(word), BooleanClause.Occur.SHOULD);
      return query.build();
    } catch (java.io.IOException impossible) {
      throw new java.io.UncheckedIOException(impossible);
    }
  }

  private Query analyzed(String field, String value, boolean phrase) {
    List<CodeIdentifierAnalyzer.Part> parts = CodeIdentifierAnalyzer.parts(value);
    if (parts.isEmpty()) return new MatchNoDocsQuery();
    if (phrase) {
      PhraseQuery.Builder b = new PhraseQuery.Builder();
      int previous = -1;
      for (var p : parts)
        if (p.position() != previous) {
          b.add(new Term(field, p.term()), p.position() - 1);
          previous = p.position();
        }
      return b.build();
    }
    BooleanQuery.Builder b = new BooleanQuery.Builder();
    for (var p : parts) b.add(new TermQuery(new Term(field, p.term())), BooleanClause.Occur.SHOULD);
    return b.build();
  }
}
