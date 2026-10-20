package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RankingWave2Test {
  @Test
  void identifierLaneUsesTheGraphUtf16BoundaryForSupplementaryLetters() {
    String letter = new String(Character.toChars(0x10400));
    assertTrue(PlainTextQuery.identifier(letter.repeat(64)));
    assertTrue(PlainTextQuery.identifier(letter.repeat(63) + "ab"));
    assertFalse(PlainTextQuery.identifier(letter.repeat(64) + "a"));
    assertFalse(PlainTextQuery.identifier(letter.repeat(65)));
    assertTrue(PlainTextQuery.identifier("a".repeat(128)));
    assertFalse(PlainTextQuery.identifier("a".repeat(129)));
  }

  @TempDir Path root;
  private static final String HASH = "a".repeat(64);

  static LinearRankingModel model(int feature, double weight) {
    double[] means = new double[10], scales = new double[10], weights = new double[10];
    Arrays.fill(scales, 1);
    weights[feature] = weight;
    return new LinearRankingModel(means, scales, weights, HASH, "b".repeat(64), 1);
  }

  static double[] feature(int index, double value) {
    double[] result = new double[10];
    result[index] = value;
    return result;
  }

  SnapshotManifest sources(SearchEngine engine, String repository, Map<String, String> content)
      throws IOException {
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (var entry : content.entrySet())
      entries.add(
          new SnapshotManifest.Entry(
              entry.getKey(),
              engine.contentStore().put(entry.getValue().getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of("reader", "lease", "resolve"),
              "",
              ""));
    return new SnapshotManifest("t", repository, "v1", false, entries, Set.of());
  }

  @Test
  void syntaxGateUsesActualSyntaxAndPreservesExplicitSemantics() {
    for (String text :
        List.of(
            "reader lease",
            "reader\tlease",
            "Reader\u2003lease",
            "UTF-8 decoder",
            "alpha and beta")) assertTrue(PlainTextQuery.multiword(text), text);
    for (String text :
        List.of(
            "reader",
            "",
            " ",
            "reader AND lease",
            "reader OR lease",
            "NOT reader",
            "\"reader lease\"",
            "(reader lease)",
            "content:reader lease",
            "reader* lease",
            "reader? lease",
            "reader.lease other",
            "- reader",
            "reader\\lease other",
            "reader:")) assertFalse(PlainTextQuery.multiword(text), text);
    assertFalse(PlainTextQuery.multiword(null));
    assertTrue(PlainTextQuery.multiword("a ".repeat(64)));
    assertFalse(PlainTextQuery.multiword("a ".repeat(65)));
    assertThrows(IllegalArgumentException.class, () -> PlainTextQuery.multiword("x".repeat(513)));
    assertTrue(PlainTextQuery.identifier("HTTPReader_2"));
    assertFalse(PlainTextQuery.identifier(null));
    assertFalse(PlainTextQuery.identifier("x".repeat(129)));
    var classic = new QueryPlanner(RankingProfile.CODE_HIGH, RankingStrategy.CLASSIC);
    var gated = new QueryPlanner(RankingProfile.CODE_HIGH, RankingStrategy.SYNTAX_GATED);
    for (String raw :
        List.of(
            "reader", "reader AND lease", "(reader lease)", "symbol:reader", "\"reader lease\"")) {
      var ast = QueryAst.parse(raw);
      assertEquals(
          classic.plan(ast, QueryPlanner.Mode.SOURCE_AWARE, raw),
          gated.plan(ast, QueryPlanner.Mode.SOURCE_AWARE, raw),
          raw);
    }
    String raw = "reader lease";
    String traditional =
        classic.plan(QueryAst.parse(raw), QueryPlanner.Mode.SOURCE_AWARE, raw).toString();
    String natural =
        gated.plan(QueryAst.parse(raw), QueryPlanner.Mode.SOURCE_AWARE, raw).toString();
    assertTrue(traditional.contains("identifiers:"));
    assertFalse(natural.contains("identifiers:"));
    assertFalse(natural.contains("symbol:"));
    assertTrue(natural.contains("content:"));
    assertTrue(natural.contains("prose:"));
    assertEquals(
        classic.plan(QueryAst.parse(raw), QueryPlanner.Mode.BASELINE, raw),
        gated.plan(QueryAst.parse(raw), QueryPlanner.Mode.BASELINE, raw));
    assertEquals(
        classic.plan(QueryAst.parse(raw), QueryPlanner.Mode.SOURCE_AWARE),
        gated.plan(QueryAst.parse(raw), QueryPlanner.Mode.SOURCE_AWARE));
    assertFalse(RankingStrategy.CLASSIC.suppressIdentifierWeights(raw));
    assertTrue(RankingStrategy.PAIRWISE.suppressIdentifierWeights(raw));
    assertFalse(RankingStrategy.SYNTAX_GATED.declarationTier("reader"));
    assertTrue(RankingStrategy.DECLARATION_TIER.declarationTier("reader"));
    assertFalse(RankingStrategy.PAIRWISE.declarationTier(raw));
  }

  @Test
  void declarationTierBeatsMentionsAndPreservesEquallyRealOrderingDespiteFloatRounding()
      throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(
          access,
          "declarations",
          sources(
              engine,
              "r",
              Map.of(
                  "Z.java", "class Z { void resolve(){} }",
                  "A.java", "class A { void resolve(){} }",
                  "Mention.java",
                      "// void resolve(){}\nclass Mention {String example=\"void resolve(){}\";}")),
          GenerationBuilder.Fault.none(),
          () -> false);
      try (var lease = engine.generations("t").acquire()) {
        List<CandidateRetriever.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < lease.reader().maxDoc(); i++) {
          var doc = lease.reader().storedFields().document(i);
          float score =
              doc.get("path").equals("Mention.java")
                  ? 2_000_000
                  : doc.get("path").equals("Z.java") ? 1_000_000 : 999_999;
          candidates.add(new CandidateRetriever.Candidate(i, doc.get("key"), score));
        }
        var ranked =
            new Ranker(RankingProfile.CODE_HIGH, RankingStrategy.DECLARATION_TIER)
                .rank(candidates, lease, "resolve", QueryPlanner.Mode.SOURCE_AWARE);
        assertEquals(
            List.of("Z.java", "A.java", "Mention.java"),
            ranked.stream()
                .map(
                    c -> {
                      try {
                        return lease.reader().storedFields().document(c.document()).get("path");
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    })
                .toList());
        assertTrue(ranked.get(1).score() > ranked.get(2).score());
        assertTrue(ranked.get(0).features().contains("declarationTier=1"));
        assertTrue(ranked.get(2).features().contains("declarationTier=0"));
        var ablated =
            new Ranker(RankingProfile.CODE_HIGH, RankingStrategy.DECLARATION_TIER)
                .rank(candidates, lease, "resolve", QueryPlanner.Mode.NO_IDENTIFIERS);
        assertEquals(
            "Mention.java",
            lease.reader().storedFields().document(ablated.getFirst().document()).get("path"));
        assertFalse(ablated.getFirst().features().contains("declarationTier="));
      }
    }
  }

  @Test
  void numericModelIsBoundedChecksummedImmutableAndContributionOnly() throws Exception {
    var positive = model(6, 2);
    var negative = model(6, -2);
    var values = feature(6, .75);
    assertEquals(1.5, positive.score(values));
    assertEquals(-1.5, negative.score(values));
    assertNotEquals(positive.identity(), negative.identity());
    assertTrue(positive.explanation(values).contains("proseTermCoverageContribution=1.5"));
    assertFalse(positive.explanation(values).contains("avgdl"));
    positive.weights()[6] = 999;
    positive.means()[6] = 1;
    positive.scales()[6] = 100;
    assertEquals(1.5, positive.score(values));
    Path path = root.resolve("model.bin");
    positive.writeNew(path);
    assertThrows(FileAlreadyExistsException.class, () -> positive.writeNew(path));
    var loaded = LinearRankingModel.read(path);
    assertEquals(positive.identity(), loaded.identity());
    assertEquals(1.5, loaded.score(values));
    assertEquals(HASH, loaded.trainingExport());
    assertEquals("b".repeat(64), loaded.folds());
    assertEquals(1, loaded.l2());
    byte[] damaged = positive.bytes();
    damaged[20] ^= 1;
    Files.write(path, damaged);
    assertThrows(IOException.class, () -> LinearRankingModel.read(path));
    Files.write(path, new byte[8193]);
    assertThrows(IOException.class, () -> LinearRankingModel.read(path));
    Files.write(path, new byte[1]);
    assertThrows(IOException.class, () -> LinearRankingModel.read(path));
    Path link = root.resolve("model-link");
    Files.createSymbolicLink(link, path);
    assertThrows(IOException.class, () -> LinearRankingModel.read(link));
    assertThrows(IOException.class, () -> LinearRankingModel.read(root));
    assertThrows(IllegalArgumentException.class, () -> positive.score(new double[1]));
    assertThrows(
        IllegalArgumentException.class, () -> SearchFeatureSchema.checked(feature(0, Double.NaN)));
    assertThrows(IllegalArgumentException.class, () -> SearchFeatureSchema.checked(feature(0, -1)));
    assertThrows(IllegalArgumentException.class, () -> SearchFeatureSchema.checked(feature(9, 2)));
    assertThrows(
        IllegalArgumentException.class, () -> SearchFeatureSchema.checked(feature(0, 100)));
    double[] scales = new double[10];
    Arrays.fill(scales, 1);
    scales[0] = 0;
    assertThrows(
        IllegalArgumentException.class,
        () -> new LinearRankingModel(new double[10], scales, new double[10], HASH, HASH, .1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LinearRankingModel(new double[10], new double[1], new double[10], HASH, HASH, .1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LinearRankingModel(
                new double[10],
                positive.scales(),
                feature(0, Double.POSITIVE_INFINITY),
                HASH,
                HASH,
                .1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LinearRankingModel(
                new double[10], positive.scales(), new double[10], "bad", HASH, .1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LinearRankingModel(
                new double[10], positive.scales(), new double[10], HASH, "bad", .1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LinearRankingModel(
                new double[10], positive.scales(), new double[10], HASH, HASH, Double.NaN));
    double[] tiny = positive.scales();
    tiny[0] = 1e-6;
    var clipped = new LinearRankingModel(feature(0, 1), tiny, feature(0, 1), HASH, HASH, .1);
    assertEquals(-8, clipped.score(new double[10]));
    assertEquals(8, clipped.score(feature(0, SearchFeatureSchema.LOG_MAX)));
  }

  @Test
  void checksumValidMalformedModelHeadersAndTrailingBytesFail() throws Exception {
    byte[] valid = model(0, 1).bytes();
    for (int position : List.of(0, 7, 11, 22)) {
      byte[] body = Arrays.copyOf(valid, valid.length - 32);
      body[position] ^= 1;
      Path file = root.resolve("bad-" + position);
      writeChecksum(file, body);
      assertThrows(IOException.class, () -> LinearRankingModel.read(file));
    }
    byte[] extra = Arrays.copyOf(valid, valid.length - 31);
    extra[extra.length - 1] = 5;
    Path trailing = root.resolve("trailing");
    writeChecksum(trailing, extra);
    assertThrows(IOException.class, () -> LinearRankingModel.read(trailing));
  }

  void writeChecksum(Path file, byte[] body) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(body);
    out.write(HexFormat.of().parseHex(Digests.sha256(body)));
    Files.write(file, out.toByteArray());
  }

  @Test
  void immutableModelSnapshotBindsWarmCacheAndCursorAcrossRestart() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "denied"), true);
    auth.update("reader", "t", Set.of("r"), false);
    auth.update("revocable", "t", Set.of("r"), false);
    Path file = root.resolve("weights.bin");
    model(6, 1).writeNew(file);
    var first = RankingConfiguration.from("PAIRWISE", file.toString());
    String cursor;
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    try (var engine =
        new SearchEngine(
            root.resolve("data"),
            auth,
            QueryPlanner.Mode.SOURCE_AWARE,
            RankingProfile.CODE_HIGH,
            first,
            clock)) {
      engine.ingest(
          admin,
          "visible",
          sources(
              engine,
              "r",
              Map.of(
                  "Full.java", "class Full {void reader(){} void lease(){}}",
                  "Partial.java", "class Partial {void reader(){}}")),
          GenerationBuilder.Fault.none(),
          () -> false);
      engine.ingest(
          admin,
          "hidden",
          sources(
              engine,
              "denied",
              Map.of("denied-marker.java", "class Hidden {void reader(){} void lease(){}}")),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request = new SearchEngine.Request("reader lease", 1, null, Map.of());
      var response = engine.search("reader", request);
      assertEquals(2, response.totalHits());
      assertEquals("Full.java", response.results().getFirst().path());
      assertEquals(1, response.results().getFirst().score(), 1e-6);
      assertTrue(
          response
              .results()
              .getFirst()
              .explanation()
              .contains("proseTermCoverageContribution=1.0"));
      assertTrue(response.scoringVersion().contains(first.model().identity()));
      cursor = response.cursor();
      assertNotNull(cursor);
      Files.write(file, model(1, 1).bytes());
      var cached = engine.search("reader", request);
      assertAll(
          () -> assertEquals(response.generationId(), cached.generationId()),
          () -> assertEquals(response.scoringVersion(), cached.scoringVersion()),
          () -> assertEquals(response.totalHits(), cached.totalHits()),
          () -> assertEquals(response.relation(), cached.relation()),
          () -> assertEquals(response.results(), cached.results()),
          () -> assertEquals(response.facets(), cached.facets()));
      var codec = new SearchCursor(Files.readAllBytes(root.resolve("data/cursor.key")), clock);
      String requestDigest =
          SearchRequestIdentity.digest(
              request, QueryPlanner.Mode.SOURCE_AWARE, response.scoringVersion());
      var expectedPosition = new SearchCursor.Position(response.generationId(), 1);
      assertEquals(
          expectedPosition, codec.decode(response.cursor(), auth.current("reader"), requestDigest));
      assertEquals(
          expectedPosition, codec.decode(cached.cursor(), auth.current("reader"), requestDigest));
      assertEquals(1, engine.cacheHits());
      var audit = engine.auditCandidates("reader", request, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals(2, audit.candidates().size());
      assertEquals(SearchFeatureSchema.VERSION, audit.featureSchema());
      assertFalse(audit.candidates().toString().contains("denied-marker"));
      var firstVector =
          audit.candidates().stream()
              .filter(c -> c.path().equals("Full.java"))
              .findFirst()
              .orElseThrow();
      firstVector.features()[6] = 0;
      assertEquals(1, firstVector.features()[6]);
      for (var mode :
          List.of(
              QueryPlanner.Mode.NO_IDENTIFIERS,
              QueryPlanner.Mode.NO_PATH,
              QueryPlanner.Mode.NO_PROSE,
              QueryPlanner.Mode.NO_CODE_FEATURES,
              QueryPlanner.Mode.NO_COVERAGE)) {
        var ablation = engine.auditCandidates("reader", request, mode);
        for (var row : ablation.candidates()) {
          double[] f = row.features();
          if (mode == QueryPlanner.Mode.NO_IDENTIFIERS) {
            assertEquals(0, f[3]);
            assertEquals(0, f[9]);
          }
          if (mode == QueryPlanner.Mode.NO_PATH) assertEquals(0, f[4]);
          if (mode == QueryPlanner.Mode.NO_PROSE)
            for (int index : List.of(2, 6, 7, 8)) assertEquals(0, f[index]);
          if (mode == QueryPlanner.Mode.NO_CODE_FEATURES) assertEquals(0, f[5]);
          if (mode == QueryPlanner.Mode.NO_COVERAGE) {
            assertEquals(0, f[6]);
            assertEquals(0, f[7]);
          }
        }
      }
      try (var lease = engine.generations("t").acquire()) {
        int denied = -1;
        for (int i = 0; i < lease.reader().maxDoc(); i++)
          if (lease.reader().storedFields().document(i).get("path").equals("denied-marker.java"))
            denied = i;
        assertTrue(denied >= 0);
        var forged = List.of(new CandidateRetriever.Candidate(denied, "opaque", 1));
        var error =
            assertThrows(
                SecurityException.class,
                () ->
                    new SearchFeatures()
                        .extract(
                            forged,
                            lease,
                            "reader lease",
                            QueryPlanner.Mode.SOURCE_AWARE,
                            RankingProfile.CODE_HIGH,
                            auth,
                            auth.current("reader")));
        assertFalse(error.getMessage().contains("denied-marker"));
        var invalid = List.of(new CandidateRetriever.Candidate(-1, "opaque", 1));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SearchFeatures()
                    .extract(
                        invalid,
                        lease,
                        "reader lease",
                        QueryPlanner.Mode.SOURCE_AWARE,
                        RankingProfile.CODE_HIGH,
                        auth,
                        auth.current("reader")));
      }
      var revocable = engine.search("revocable", request);
      assertEquals(2, revocable.totalHits());
      auth.update("revocable", "t", Set.of(), false);
      assertEquals(0, engine.search("revocable", request).totalHits());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              engine.search(
                  "revocable",
                  new SearchEngine.Request("reader lease", 1, revocable.cursor(), Map.of())));
    }
    assertEquals(
        2,
        auth.current("reader").version(),
        "the model test must not rely on a permission-version change");
    try (var changed =
        new SearchEngine(
            root.resolve("data"),
            auth,
            QueryPlanner.Mode.SOURCE_AWARE,
            RankingProfile.CODE_HIGH,
            RankingConfiguration.from("PAIRWISE", file.toString()),
            clock)) {
      assertNotEquals(first.identity(RankingProfile.CODE_HIGH), changed.rankingIdentity());
      var fresh =
          changed.search("reader", new SearchEngine.Request("reader lease", 1, null, Map.of()));
      assertFalse(fresh.scoringVersion().contains(first.model().identity()));
      assertTrue(fresh.scoringVersion().contains(LinearRankingModel.read(file).identity()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              changed.search(
                  "reader", new SearchEngine.Request("reader lease", 1, cursor, Map.of())));
    }
  }

  @Test
  void learnedRankerUsesFiniteScoresAndStableKeyTiesWithoutIdentityFeatures() {
    var vectors =
        List.of(
            new SearchFeatures.Vector(
                new CandidateRetriever.Candidate(0, "z", 100), new double[10]),
            new SearchFeatures.Vector(new CandidateRetriever.Candidate(1, "a", 1), new double[10]));
    var result = new LearnedRanker().rank(vectors, model(0, 0));
    assertEquals(
        List.of("a", "z"), result.stream().map(CandidateRetriever.Candidate::key).toList());
    assertEquals(0, result.getFirst().score());
    assertThrows(
        IllegalArgumentException.class,
        () -> new LearnedRanker().rank(Collections.nCopies(501, vectors.getFirst()), model(0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingConfiguration(RankingStrategy.PAIRWISE, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingConfiguration(RankingStrategy.CLASSIC, model(0, 1)));
    assertEquals(
        RankingConfiguration.classic(), new RankingConfiguration(RankingStrategy.CLASSIC, null));
    assertThrows(IllegalArgumentException.class, () -> RankingConfiguration.from("PAIRWISE", null));
    assertThrows(
        IllegalArgumentException.class, () -> RankingConfiguration.from("CLASSIC", "unexpected"));
  }

  @Test
  void sharedFeaturesHandleSparseMultiLeafScorersMissingVectorsAndUnorderedCandidates()
      throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), false);
    try (var directory = new org.apache.lucene.store.ByteBuffersDirectory();
        var identifier = new CodeIdentifierAnalyzer();
        var prose = new SourceProseAnalyzer();
        var analyzer =
            new org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper(
                identifier, Map.of("prose", prose, "code_features", prose))) {
      try (var writer =
          new org.apache.lucene.index.IndexWriter(
              directory,
              new org.apache.lucene.index.IndexWriterConfig(analyzer)
                  .setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE))) {
        for (int i = 0; i < 3; i++) {
          String content = List.of("reader", "reader lease", "unrelated").get(i);
          var entry =
              new SnapshotManifest.Entry(
                  "Doc" + i + ".java", HASH, "java", "source", List.of("reader"), "", "");
          var document = DocumentMapper.map(new SourceDocument("t", "r", "v1", entry, content));
          if (i == 2) document.removeFields("prose");
          writer.addDocument(document);
          writer.commit();
        }
      }
      try (var reader = org.apache.lucene.index.DirectoryReader.open(directory);
          var lease = new GenerationLease("manual", reader, () -> {})) {
        assertEquals(3, reader.leaves().size());
        var candidates =
            List.of(
                new CandidateRetriever.Candidate(2, "third", 0),
                new CandidateRetriever.Candidate(0, "first", 1),
                new CandidateRetriever.Candidate(1, "second", 2));
        var features = new SearchFeatures();
        var vectors =
            features.extract(
                candidates,
                lease,
                "reader lease",
                QueryPlanner.Mode.SOURCE_AWARE,
                RankingProfile.CODE_HIGH,
                auth,
                access);
        assertEquals(
            List.of("third", "first", "second"),
            vectors.stream().map(v -> v.candidate().key()).toList());
        assertEquals(0, vectors.get(0).values()[6]);
        assertEquals(0, vectors.get(0).values()[7]);
        assertEquals(0, vectors.get(0).values()[1]);
        assertEquals(0, vectors.get(0).values()[8]);
        assertEquals(.5, vectors.get(1).values()[6]);
        assertEquals(1, vectors.get(2).values()[6]);
        assertEquals(0, vectors.get(1).values()[8]);
        assertEquals(1, vectors.get(2).values()[8]);
        assertEquals(Math.log1p(2), vectors.get(2).values()[0]);
        assertTrue(
            features
                .extract(
                    List.of(),
                    lease,
                    "reader lease",
                    QueryPlanner.Mode.SOURCE_AWARE,
                    RankingProfile.CODE_HIGH,
                    auth,
                    access)
                .isEmpty());
        for (var invalid :
            List.of(
                Collections.nCopies(501, candidates.getFirst()),
                List.of(candidates.getFirst(), candidates.getFirst()),
                List.of(new CandidateRetriever.Candidate(3, "bad", 1)),
                List.of(new CandidateRetriever.Candidate(0, "bad", Float.NaN)),
                List.of(new CandidateRetriever.Candidate(0, "bad", -1)))) {
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  features.extract(
                      invalid,
                      lease,
                      "reader lease",
                      QueryPlanner.Mode.SOURCE_AWARE,
                      RankingProfile.CODE_HIGH,
                      auth,
                      access));
        }
        String manyTerms =
            java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> "term" + i)
                .collect(java.util.stream.Collectors.joining(" "));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                features.extract(
                    candidates,
                    lease,
                    manyTerms,
                    QueryPlanner.Mode.SOURCE_AWARE,
                    RankingProfile.CODE_HIGH,
                    auth,
                    access));
        auth.update("reader", "t", Set.of(), false);
        assertThrows(
            SecurityException.class,
            () ->
                features.extract(
                    candidates,
                    lease,
                    "reader lease",
                    QueryPlanner.Mode.SOURCE_AWARE,
                    RankingProfile.CODE_HIGH,
                    auth,
                    access));
      }
    }
  }

  @Test
  void pairwiseBypassesSingleIdentifiersExplicitSyntaxAndContentBaseline() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    Path data = root.resolve("bypass");
    Map<String, List<String>> paths = new TreeMap<>();
    Map<String, List<Float>> scores = new TreeMap<>();
    try (var base =
        new SearchEngine(
            data,
            auth,
            QueryPlanner.Mode.SOURCE_AWARE,
            RankingProfile.CODE_HIGH,
            new RankingConfiguration(RankingStrategy.DECLARATION_TIER, null))) {
      base.ingest(
          access,
          "bypass",
          sources(
              base,
              "r",
              Map.of(
                  "A.java",
                  "class A {void reader(){} void lease(){}}",
                  "B.java",
                  "class B {void reader(){}}")),
          GenerationBuilder.Fault.none(),
          () -> false);
      for (String raw :
          List.of("reader", "reader AND lease", "content:reader", "\"reader lease\"")) {
        var response = base.search("reader", new SearchEngine.Request(raw, 20, null, Map.of()));
        paths.put(raw, response.results().stream().map(SearchEngine.Hit::path).toList());
        scores.put(raw, response.results().stream().map(SearchEngine.Hit::score).toList());
      }
      var baseline =
          base.search(
              "reader",
              new SearchEngine.Request("reader lease", 20, null, Map.of()),
              QueryPlanner.Mode.BASELINE);
      paths.put("BASELINE", baseline.results().stream().map(SearchEngine.Hit::path).toList());
      scores.put("BASELINE", baseline.results().stream().map(SearchEngine.Hit::score).toList());
    }
    try (var learned =
        new SearchEngine(
            data,
            auth,
            QueryPlanner.Mode.SOURCE_AWARE,
            RankingProfile.CODE_HIGH,
            new RankingConfiguration(RankingStrategy.PAIRWISE, model(6, -100)))) {
      for (String raw : paths.keySet()) {
        var response =
            raw.equals("BASELINE")
                ? learned.search(
                    "reader",
                    new SearchEngine.Request("reader lease", 20, null, Map.of()),
                    QueryPlanner.Mode.BASELINE)
                : learned.search("reader", new SearchEngine.Request(raw, 20, null, Map.of()));
        assertEquals(
            paths.get(raw), response.results().stream().map(SearchEngine.Hit::path).toList(), raw);
        assertEquals(
            scores.get(raw),
            response.results().stream().map(SearchEngine.Hit::score).toList(),
            raw);
        assertTrue(
            response.results().stream().noneMatch(h -> h.explanation().contains("linearModel=")),
            raw);
      }
    }
  }
}
