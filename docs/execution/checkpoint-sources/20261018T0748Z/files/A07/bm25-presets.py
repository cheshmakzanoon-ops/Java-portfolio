from pathlib import Path
b=Path('projects/07-indexforge')
def p(m,n):return b/m/'src/main/java/io/github/cheshmakzanoon/portfolio/indexforge'/f'{n}.java'
x=p('index','GenerationLease');s=x.read_text().replace('  private final DirectoryReader reader;','  private final DirectoryReader reader;\n  private final IndexSearcher searcher;').replace('    this.reader = reader;','    this.reader = reader;\n    this.searcher = new IndexSearcher(reader);').replace('return new IndexSearcher(reader);','return searcher;');x.write_text(s)
x=p('query','RankingProfile');s=x.read_text().replace('COORDINATED(3, 8, 2, 32, 1.5f, .75f, 8);','''COORDINATED(3, 8, 2, 32, 1.5f, .75f, 8),
  BM25_NO_LENGTH(1.2f, 0),
  BM25_LOW_LENGTH(1.2f, .25f),
  BM25_FULL_LENGTH(1.2f, 1),
  BM25_LOW_SATURATION(.6f, .75f),
  BM25_HIGH_SATURATION(2, .75f),
  BM25_BOOLEAN(0, .75f);''').replace('public final float identifiers, symbols, paths, declarations, prose, concepts, coverage;','''public final float identifiers, symbols, paths, declarations, prose, concepts, coverage;
  public final float k1, b;

  RankingProfile(float k1, float b) {
    this(2, 6, 1, 32, 2, 2, 4, k1, b);
  }''');s=s.replace('''      float coverage) {
    this.identifiers''','''      float coverage) {
    this(identifiers, symbols, paths, declarations, prose, concepts, coverage, 1.2f, .75f);
  }

  RankingProfile(float identifiers, float symbols, float paths, float declarations,
      float prose, float concepts, float coverage, float k1, float b) {
    this.k1 = k1;
    this.b = b;
    this.identifiers''');x.write_text(s)
x=p('query','QueryPlanner');s=x.read_text().replace('    NO_COVERAGE\n','    NO_COVERAGE,\n    NO_SIMILARITY\n').replace('source-v5-coverage','source-v6-bm25');x.write_text(s)
x=p('query','SearchEngine');s=x.read_text().replace('''        String cacheKey =''','''        if (mode != QueryPlanner.Mode.BASELINE && mode != QueryPlanner.Mode.NO_SIMILARITY)
          lease.searcher().setSimilarity(new org.apache.lucene.search.similarities.BM25Similarity(
              rankingProfile.k1, rankingProfile.b));
        String cacheKey =''',1);x.write_text(s)
x=p('service','IndexForgeApplication');s=x.read_text().replace('''QueryPlanner.Mode.valueOf(env.getProperty("indexforge.ranking-mode", "BASELINE")));''','''QueryPlanner.Mode.valueOf(env.getProperty("indexforge.ranking-mode", "BASELINE")),
        RankingProfile.valueOf(env.getProperty("indexforge.ranking-profile", "CURRENT")));''');x.write_text(s)
x=p('evaluation','RelevanceEvaluator');s=x.read_text().replace('''            "RankingProfile",''','''            "RankingProfile",
            "GenerationLease",''');a=s.index('        Path opened = output.resolve("HELDOUT-OPENED.txt");');z=s.index('        for (var mode : QueryPlanner.Mode.values()) {',a);s=s[:a]+'''        EvaluationSeal.claim(fixtures, output, decision);
'''+s[z:];x.write_text(s)
p('evaluation','EvaluationSeal').write_text('''package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.IOException;
import java.nio.file.*;

/** Shared fixture identity prevents accidentally opening a new held-out model in another output. */
public final class EvaluationSeal {
  private EvaluationSeal() {}

  public static void claim(Path fixtures, Path output, String decision) throws IOException {
    if (decision.length() > 4096) throw new IOException("held-out decision quota");
    Files.createDirectories(output);
    claimOne(fixtures.resolve("HELDOUT-OPENED.txt"), decision);
    claimOne(output.resolve("HELDOUT-OPENED.txt"), decision);
  }

  private static void claimOne(Path path, String decision) throws IOException {
    if (Files.exists(path)) {
      if (Files.size(path) > 4096 || !Files.readString(path).equals(decision))
        throw new IOException("held-out already opened for a different or corrupt model identity");
    } else Files.writeString(path, decision, StandardOpenOption.CREATE_NEW);
  }
}
''')
