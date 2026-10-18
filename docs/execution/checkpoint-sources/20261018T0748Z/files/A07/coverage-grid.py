from pathlib import Path
base=Path('projects/07-indexforge')
def p(module,name):return base/module/'src/main/java/io/github/cheshmakzanoon/portfolio/indexforge'/f'{name}.java'
p('query','RankingProfile').write_text('''package io.github.cheshmakzanoon.portfolio.indexforge;

/** Bounded, preregistered conventional field-weight and term-coverage experiment. */
public enum RankingProfile {
  CURRENT(3, 8, 2, 32, 1.5f, .75f, 0),
  CONTEXTUAL(2, 6, 1, 32, 2, 2, 4),
  SEMANTIC(1, 4, 1, 24, 3, 4, 4),
  NAVIGATIONAL(2, 12, 4, 64, 1.5f, 1, 2),
  CONTENT_FOCUSED(1, 2, .5f, 16, 1, .5f, 4),
  COORDINATED(3, 8, 2, 32, 1.5f, .75f, 8);

  public final float identifiers, symbols, paths, declarations, prose, concepts, coverage;

  RankingProfile(float identifiers, float symbols, float paths, float declarations,
      float prose, float concepts, float coverage) {
    this.identifiers = identifiers;
    this.symbols = symbols;
    this.paths = paths;
    this.declarations = declarations;
    this.prose = prose;
    this.concepts = concepts;
    this.coverage = coverage;
  }

  public static RankingProfile current() {
    return valueOf(System.getProperty("indexforge.ranking-profile", "CURRENT"));
  }
}
''')
x=p('index','DocumentMapper');s=x.read_text().replace('  private DocumentMapper() {}','''  private static final FieldType PROSE;
  static {
    PROSE = new FieldType(TextField.TYPE_NOT_STORED);
    PROSE.setStoreTermVectors(true);
    PROSE.freeze();
  }
  private DocumentMapper() {}''').replace('new TextField("prose", s.content(), Field.Store.NO)','new Field("prose", s.content(), PROSE)');x.write_text(s)
x=p('query','QueryPlanner');s=x.read_text().replace('    NO_CONCEPTS\n','    NO_CONCEPTS,\n    NO_COVERAGE\n').replace('"source-v4-concepts"','"source-v5-coverage"');s=s.replace('  public Query plan(QueryAst ast, Mode mode) {','''  private final RankingProfile profile;
  public QueryPlanner() { this(RankingProfile.current()); }
  public QueryPlanner(RankingProfile profile) { this.profile = Objects.requireNonNull(profile); }

  public Query plan(QueryAst ast, Mode mode) {''').replace('new BoostQuery(related.get(), .75f)','new BoostQuery(related.get(), profile.concepts)').replace('prose(value, t.phrase()), 1.5f','prose(value, t.phrase()), profile.prose').replace('new Term("declaration", value)), 32f','new Term("declaration", value)), profile.declarations').replace('analyzed("identifiers", value, t.phrase()), 3f','analyzed("identifiers", value, t.phrase()), profile.identifiers').replace('new Term("symbol", value)), 8f','new Term("symbol", value)), profile.symbols').replace('analyzed("path_tokens", value, t.phrase()), 2f','analyzed("path_tokens", value, t.phrase()), profile.paths');x.write_text(s)
x=p('query','Ranker');s=x.read_text().replace('import java.util.*;','''import java.util.*;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;''');s=s.replace('public final class Ranker {','''public final class Ranker {
  private final RankingProfile profile;
  public Ranker() { this(RankingProfile.current()); }
  public Ranker(RankingProfile profile) { this.profile = Objects.requireNonNull(profile); }

  private static Set<String> coverageTerms(String query) throws IOException {
    if (!query.contains(" ") || !query.matches("[\\\\p{L}\\\\p{N}_$ -]+")
        || java.util.regex.Pattern.compile("(?:^| )(?:AND|OR|NOT)(?: |$)").matcher(query).find())
      return Set.of();
    Set<String> terms = new TreeSet<>();
    try (var analyzer = new SourceProseAnalyzer(); var stream = analyzer.tokenStream("prose", query)) {
      var term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        terms.add(term.toString());
        if (terms.size() > 64) throw new IllegalArgumentException("coverage query quota");
      }
      stream.end();
    }
    return terms;
  }
''');s=s.replace('''    List<CandidateRetriever.Candidate> scored = new ArrayList<>();''','''    Set<String> queryTerms = profile.coverage > 0 && mode != QueryPlanner.Mode.BASELINE
        && mode != QueryPlanner.Mode.NO_COVERAGE && mode != QueryPlanner.Mode.NO_PROSE
        ? coverageTerms(query) : Set.of();
    List<CandidateRetriever.Candidate> scored = new ArrayList<>();''');s=s.replace('''      scored.add(
''','''      if (!queryTerms.isEmpty()) {
        Terms vector = lease.reader().termVectors().get(candidate.document(), "prose");
        if (vector != null) {
          var terms = vector.iterator();
          int matched = 0;
          for (String term : queryTerms) if (terms.seekExact(new BytesRef(term))) matched++;
          float coverage = (float) matched / queryTerms.size();
          float coverageBonus = profile.coverage * coverage * coverage;
          bonus += coverageBonus;
          features += " queryTermCoverage=" + coverage + " coverageBonus=" + coverageBonus;
        }
      }
      scored.add(
''');x.write_text(s)
x=p('query','SearchEngine');s=x.read_text().replace('  private final QueryPlanner.Mode defaultMode;','  private final QueryPlanner.Mode defaultMode;\n  private final RankingProfile rankingProfile;');s=s.replace('''    this.root = root;
    this.defaultMode = Objects.requireNonNull(defaultMode);''','''    this(root, authorization, defaultMode, RankingProfile.current());
  }

  public SearchEngine(Path root, AuthorizationFilter authorization, QueryPlanner.Mode defaultMode,
      RankingProfile rankingProfile) throws IOException {
    this.root = root;
    this.defaultMode = Objects.requireNonNull(defaultMode);
    this.rankingProfile = Objects.requireNonNull(rankingProfile);''');s=s.replace('new QueryPlanner().plan(ast, mode, request.text())','new QueryPlanner(rankingProfile).plan(ast, mode, request.text())').replace('''            new Ranker()
''','''            new Ranker(rankingProfile)
''').replace('''mode == QueryPlanner.Mode.BASELINE ? "bm25-content-v1" : QueryPlanner.VERSION''','''mode == QueryPlanner.Mode.BASELINE ? "bm25-content-v1"
                    : QueryPlanner.VERSION + "-" + rankingProfile.name()''');x.write_text(s)
x=p('evaluation','RelevanceEvaluator');s=x.read_text().replace('''            "RelevanceEvaluator",''','''            "RelevanceEvaluator",
            "RankingProfile",''').replace('''    return Digests.sha256(model.toString());''','''    model.append("profile:").append(RankingProfile.current().name());
    return Digests.sha256(model.toString());''');s=s.replace('''      Map<String, Double> validationScores = new HashMap<>();''','''      Map<String, Double> validationScores = new HashMap<>();
      Map<String, Double> validationIdentifier = new HashMap<>();''');s=s.replace('''          int n = 0;
          for (Judged q : queries)''','''          int n = 0, identifiers = 0;
          double identifierMrr = 0;
          for (Judged q : queries)''',1);s=s.replace('''              mrr += m.mrr10();
              recall += m.recall20();''','''              mrr += m.mrr10();
              if (q.type().equals("identifier")) { identifierMrr += m.mrr10(); identifiers++; }
              recall += m.recall20();''',1);s=s.replace('''          if (split.equals("validation")) validationScores.put(mode.name(), ndcg / n);''','''          summary.add(mode + " " + split + " identifierMRR10=" + identifierMrr / identifiers);
          if (split.equals("validation")) {
            validationScores.put(mode.name(), ndcg / n);
            validationIdentifier.put(mode.name(), identifierMrr / identifiers);
          }''');s=s.replace('''          Arrays.stream(QueryPlanner.Mode.values())
              .max(''','''          Arrays.stream(QueryPlanner.Mode.values())
              .filter(mode -> validationIdentifier.get(mode.name()) >= validationIdentifier.get("BASELINE")
                  && validationScores.get(mode.name()) >= validationScores.get("BASELINE"))
              .max(''',1);s=s.replace('''mode -> validationScores.get(mode.name()))''','''mode -> Math.min(validationScores.get(mode.name()) / .75,
                              validationIdentifier.get(mode.name()) / .90))''',1);s=s.replace('''              + selected
              + "\\nModel SHA256: "''','''              + selected
              + "\\nRanking profile: " + RankingProfile.current().name()
              + "\\nObjective: maximize min(validation nDCG10/.75, identifierMRR10/.90), no baseline regression\\nModel SHA256: "''');x.write_text(s)
