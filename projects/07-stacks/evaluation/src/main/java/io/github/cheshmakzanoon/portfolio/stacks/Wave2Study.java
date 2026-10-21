package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Explicit, non-overwriting wave2 phases. No phase opens held-out queries or first-final seals. */
public final class Wave2Study {
  private Wave2Study() {}

  private static final List<RankingStrategy> FAMILIES =
      List.of(
          RankingStrategy.CLASSIC,
          RankingStrategy.SYNTAX_GATED,
          RankingStrategy.DECLARATION_TIER,
          RankingStrategy.PAIRWISE);
  private static final String[] NAMES = {"C0", "C1", "D1", "C2"};

  public static void run(String phase, Path data, Path fixtures, Path output) throws IOException {
    RelevanceEvaluator.verifyFreeze(fixtures);
    verifyWave(fixtures.resolve("ranking-wave2"));
    switch (phase) {
      case "audit" -> audit(data, fixtures, output);
      case "fit" -> fit(fixtures, output);
      case "validate" -> validate(data, fixtures, output);
      default ->
          throw new IllegalArgumentException(
              "wave2 supports audit, fit, validate only; no final authorization implied");
    }
  }

  private static String codeIdentity() throws IOException {
    return RelevanceEvaluator.modelFingerprint("ranking-wave2/shared-v1");
  }

  private static void verifyWave(Path wave) throws IOException {
    List<String> manifest = Files.readAllLines(wave.resolve("FREEZE.sha256"));
    if (manifest.size() < 4 || manifest.size() > 12)
      throw new IOException("wave2 freeze manifest quota");
    Set<String> seen = new HashSet<>();
    for (String row : manifest) {
      String[] pair = row.split("  ", -1);
      if (pair.length != 2
          || !pair[0].matches("[a-f0-9]{64}")
          || !Set.of(
                  "PREREGISTRATION.md",
                  "protocol-freeze.json",
                  "folds.json",
                  "folds.tsv",
                  "comparator-reference.tsv")
              .contains(pair[1])
          || !seen.add(pair[1])
          || !Digests.sha256(wave.resolve(pair[1])).equals(pair[0]))
        throw new IOException("wave2 preregistration identity mismatch");
    }
    if (seen.size() != 5) throw new IOException("incomplete wave2 freeze manifest");
  }

  private static void audit(Path data, Path fixtures, Path output) throws IOException {
    List<Wave2Inputs.Header> headers = Wave2Inputs.headers(fixtures, Set.of("development"));
    if (headers.size() != 40) throw new IOException("forty development queries required");
    Files.createDirectories(output);
    Path stage = Files.createDirectory(output.resolve("audit"));
    List<FeaturePacket.Query> exported = new ArrayList<>();
    AuthorizationFilter authorization = new AuthorizationFilter();
    var access = authorization.update("wave2-evaluator", "evaluation", Set.of("corpus"), true);
    String rankingIdentity;
    try (SearchEngine engine =
        engine(data.resolve("audit"), authorization, RankingStrategy.DECLARATION_TIER, null)) {
      ingest(engine, access, fixtures);
      rankingIdentity = engine.rankingIdentity();
      for (var query : headers) {
        var candidates =
            engine.auditCandidates(
                access.principal(),
                new SearchEngine.Request(query.text(), 20, null, Map.of()),
                QueryPlanner.Mode.SOURCE_AWARE);
        exported.add(new FeaturePacket.Query(query, candidates.candidates()));
      }
    }
    Path packet = stage.resolve("features.tsv");
    new FeaturePacket(exported).writeNew(packet);
    // No judgment parser is called until after this immutable label-blind export is on disk.
    SourceStudyFiles.write(
        stage.resolve("identity.properties"),
        Map.of(
            "code",
            codeIdentity(),
            "fixture",
            Digests.sha256(fixtures.resolve("FREEZE.sha256")),
            "protocol",
            Digests.sha256(fixtures.resolve("ranking-wave2/FREEZE.sha256")),
            "features",
            Digests.sha256(packet),
            "schema",
            SearchFeatureSchema.VERSION,
            "ranking",
            rankingIdentity));
  }

  private static Map<String, String> verifiedAudit(Path fixtures, Path output) throws IOException {
    var identity = SourceStudyFiles.read(output.resolve("audit/identity.properties"));
    SourceStudyFiles.check(identity, "code", codeIdentity());
    SourceStudyFiles.check(identity, "fixture", Digests.sha256(fixtures.resolve("FREEZE.sha256")));
    SourceStudyFiles.check(
        identity, "protocol", Digests.sha256(fixtures.resolve("ranking-wave2/FREEZE.sha256")));
    SourceStudyFiles.check(
        identity, "features", Digests.sha256(output.resolve("audit/features.tsv")));
    SourceStudyFiles.check(identity, "schema", SearchFeatureSchema.VERSION);
    return identity;
  }

  private static void fit(Path fixtures, Path output) throws IOException {
    var identity = verifiedAudit(fixtures, output);
    FeaturePacket packet = FeaturePacket.read(output.resolve("audit/features.tsv"));
    var headers = packet.queries().stream().map(FeaturePacket.Query::header).toList();
    if (!headers.equals(Wave2Inputs.headers(fixtures, Set.of("development"))))
      throw new IOException("feature export query content differs from frozen development headers");
    var labels = Wave2Inputs.labels(fixtures, headers);
    var development = packet.attachDevelopmentLabels(labels);
    var assignments = GroupedFolds.assign(development);
    verifyFolds(fixtures.resolve("ranking-wave2/folds.tsv"), development, assignments);
    Path stage = Files.createDirectory(output.resolve("fit"));
    var result =
        PairwiseSelection.select(
            development,
            labels,
            identifierIds(headers),
            assignments,
            identity.get("features"),
            Digests.sha256(fixtures.resolve("ranking-wave2/folds.tsv")));
    List<String> reports = new ArrayList<>();
    reports.add("status=" + result.status());
    reports.add("D1 comparator=" + result.comparator());
    for (var query : development) {
      long grade3 = query.rows().stream().filter(r -> r.grade() == 3).count();
      var metric =
          RelevanceMetrics.evaluate(
              PairwiseSelection.basePaths(List.of(query)).get(query.id()), labels.get(query.id()));
      var ideal =
          query.rows().stream()
              .sorted(
                  Comparator.comparingInt(PairwiseData.Row::grade)
                      .reversed()
                      .thenComparing(PairwiseData.Row::key))
              .map(PairwiseData.Row::key)
              .limit(20)
              .toList();
      reports.add(
          "audit "
              + query.id()
              + " retrieved="
              + query.rows().size()
              + " grade3="
              + grade3
              + " actual="
              + metric
              + " retrievedOracle="
              + RelevanceMetrics.evaluate(ideal, labels.get(query.id())));
    }
    for (var fold : result.folds()) {
      String name = "l2-" + fold.l2() + "-fold-" + fold.fold();
      reports.add(name + " failure=" + fold.failure() + " fit=" + fitDescription(fold.fit()));
      if (fold.fit() != null) fold.fit().model().writeNew(stage.resolve(name + ".bin"));
      SourceStudyFiles.paths(stage.resolve(name + "-paths.tsv"), fold.paths());
    }
    for (var trial : result.trials()) reports.add("trial=" + trial);
    if (result.selected() != null)
      result.selected().model().writeNew(stage.resolve("selected-model.bin"));
    Files.writeString(
        stage.resolve("report.txt"),
        String.join("\n", reports) + "\n",
        StandardOpenOption.CREATE_NEW);
    SourceStudyFiles.write(
        stage.resolve("identity.properties"),
        Map.of(
            "code",
            identity.get("code"),
            "audit",
            Digests.sha256(output.resolve("audit/identity.properties")),
            "report",
            Digests.sha256(stage.resolve("report.txt")),
            "status",
            result.status(),
            "model",
            result.selected() == null
                ? "NONE"
                : Digests.sha256(stage.resolve("selected-model.bin"))));
  }

  private static String fitDescription(PairwiseTrainer.Result fit) {
    return fit == null
        ? "NONE"
        : "model="
            + fit.model().identity()
            + ",iterations="
            + fit.iterations()
            + ",loss="
            + fit.initialLoss()
            + "->"
            + fit.finalLoss()
            + ",pairs="
            + fit.pairs()
            + ",eligibleQueries="
            + fit.eligibleQueries()
            + ",seed="
            + fit.seed();
  }

  static void verifyFolds(
      Path path, List<PairwiseData.Query> queries, Map<String, Integer> expected)
      throws IOException {
    List<String> rows = Files.readAllLines(path);
    if (rows.size() != 41 || !rows.getFirst().equals("query\tjudgmentVectorSha256\tfold"))
      throw new IOException("declared forty-query fold file required");
    Map<String, PairwiseData.Query> byId =
        queries.stream().collect(Collectors.toMap(PairwiseData.Query::id, q -> q));
    Set<String> seen = new HashSet<>();
    for (String line : rows.subList(1, rows.size())) {
      String[] fields = line.split("\t", -1);
      try {
        if (fields.length != 3
            || !seen.add(fields[0])
            || !byId.containsKey(fields[0])
            || !byId.get(fields[0]).group().equals(fields[1])
            || !Objects.equals(expected.get(fields[0]), Integer.valueOf(fields[2])))
          throw new IOException("declared fold/group identity differs");
      } catch (NumberFormatException invalid) {
        throw new IOException("invalid declared fold", invalid);
      }
    }
    if (!seen.equals(expected.keySet())) throw new IOException("missing fold identity");
  }

  private record Row(
      String family,
      QueryPlanner.Mode mode,
      Wave2Inputs.Header header,
      RelevanceMetrics.Values metric,
      List<String> paths) {}

  private static void validate(Path data, Path fixtures, Path output) throws IOException {
    var audit = verifiedAudit(fixtures, output);
    var fit = SourceStudyFiles.read(output.resolve("fit/identity.properties"));
    SourceStudyFiles.check(fit, "code", codeIdentity());
    SourceStudyFiles.check(
        fit, "audit", Digests.sha256(output.resolve("audit/identity.properties")));
    SourceStudyFiles.check(fit, "report", Digests.sha256(output.resolve("fit/report.txt")));
    String modelHash = fit.get("model");
    if (modelHash == null) throw new IOException("missing fitted model status");
    LinearRankingModel model = null;
    if (!modelHash.equals("NONE")) {
      SourceStudyFiles.check(
          fit, "model", Digests.sha256(output.resolve("fit/selected-model.bin")));
      model = LinearRankingModel.read(output.resolve("fit/selected-model.bin"));
      if (!model.trainingExport().equals(audit.get("features"))
          || !model.folds().equals(Digests.sha256(fixtures.resolve("ranking-wave2/folds.tsv"))))
        throw new IOException("fitted model provenance differs");
    } else if (Files.exists(output.resolve("fit/selected-model.bin")))
      throw new IOException("undeclared model artifact");
    Path stage = Files.createDirectory(output.resolve("validation"));
    // This records the whole frozen model/phase boundary BEFORE validation query/grade parsing.
    SourceStudyFiles.write(
        stage.resolve("PRE-VALIDATION.properties"),
        Map.of(
            "code",
            codeIdentity(),
            "model",
            modelHash,
            "audit",
            Digests.sha256(output.resolve("audit/identity.properties")),
            "fit",
            Digests.sha256(output.resolve("fit/identity.properties")),
            "protocol",
            audit.get("protocol"),
            "families",
            model == null ? "C0,C1,D1" : "C0,C1,D1,C2"));
    var headers = Wave2Inputs.headers(fixtures, Set.of("development", "validation"));
    if (headers.size() != 60)
      throw new IOException("sixty development/validation headers required");
    var labels = Wave2Inputs.evaluationLabels(fixtures, headers);
    List<Row> rows = new ArrayList<>();
    List<String> identities = new ArrayList<>();
    for (int index = 0; index < FAMILIES.size(); index++) {
      if (FAMILIES.get(index) == RankingStrategy.PAIRWISE && model == null) continue;
      AuthorizationFilter authorization = new AuthorizationFilter();
      var access = authorization.update("wave2-evaluator", "evaluation", Set.of("corpus"), true);
      try (SearchEngine engine =
          engine(
              data.resolve(NAMES[index]),
              authorization,
              FAMILIES.get(index),
              FAMILIES.get(index) == RankingStrategy.PAIRWISE ? model : null)) {
        ingest(engine, access, fixtures);
        identities.add(
            NAMES[index] + "=" + RelevanceEvaluator.modelFingerprint(engine.rankingIdentity()));
        for (var mode : QueryPlanner.Mode.legacyValues())
          for (var query : headers) {
            var response =
                engine.search(
                    access.principal(),
                    new SearchEngine.Request(query.text(), 20, null, Map.of()),
                    mode);
            var paths = response.results().stream().map(SearchEngine.Hit::path).toList();
            rows.add(
                new Row(
                    NAMES[index],
                    mode,
                    query,
                    RelevanceMetrics.evaluate(paths, labels.get(query.id())),
                    paths));
          }
      }
    }
    writeRows(stage.resolve("per-query.tsv"), rows);
    Files.writeString(
        stage.resolve("family-identities.txt"),
        String.join("\n", identities) + "\n",
        StandardOpenOption.CREATE_NEW);
    try {
      verifyComparator(fixtures.resolve("ranking-wave2/comparator-reference.tsv"), rows);
    } catch (IOException mismatch) {
      Files.writeString(
          stage.resolve("COMPARATOR-FAILED.txt"),
          mismatch.getMessage() + "\n",
          StandardOpenOption.CREATE_NEW);
      throw mismatch;
    }
    for (Row row : rows)
      if (!row.family().equals("C0") && row.mode() == QueryPlanner.Mode.BASELINE) {
        Row original =
            rows.stream()
                .filter(
                    r ->
                        r.family().equals("C0")
                            && r.mode() == row.mode()
                            && r.header().id().equals(row.header().id()))
                .findFirst()
                .orElseThrow();
        if (!row.paths().equals(original.paths()) || !row.metric().equals(original.metric()))
          throw new IOException("content BASELINE changed across families");
      }
    List<String> summary = new ArrayList<>();
    for (String family : NAMES)
      if (rows.stream().anyMatch(r -> r.family().equals(family)))
        for (var mode : QueryPlanner.Mode.legacyValues())
          for (String split : List.of("development", "validation"))
            summary.add(
                family + " " + mode + " " + split + " " + aggregate(rows, family, mode, split));
    var baseline = aggregate(rows, "C0", QueryPlanner.Mode.BASELINE, "validation");
    var c0 = aggregate(rows, "C0", QueryPlanner.Mode.SOURCE_AWARE, "validation");
    String selected = "ORIGINAL_BASELINE";
    var selectedMetric = baseline;
    for (String family : NAMES) {
      if (rows.stream().noneMatch(r -> r.family().equals(family))) continue;
      var metric = aggregate(rows, family, QueryPlanner.Mode.SOURCE_AWARE, "validation");
      if (metric.dominates(baseline)
          && metric.dominates(c0)
          && metric.objective() > selectedMetric.objective()) {
        selected = family;
        selectedMetric = metric;
      }
    }
    summary.add("Selected=" + selected + " " + selectedMetric);
    summary.add(
        "No held-out opened. Independent review and explicit versioned final authorization remain required.");
    Files.writeString(
        stage.resolve("summary.txt"),
        String.join("\n", summary) + "\n",
        StandardOpenOption.CREATE_NEW);
    SourceStudyFiles.write(
        stage.resolve("selection.properties"),
        Map.of(
            "family",
            selected,
            "profile",
            "CODE_HIGH",
            "mode",
            selected.equals("ORIGINAL_BASELINE") ? "BASELINE" : "SOURCE_AWARE",
            "strategy",
            selected.equals("ORIGINAL_BASELINE")
                ? "CLASSIC"
                : FAMILIES.get(List.of(NAMES).indexOf(selected)).name(),
            "model",
            selected.equals("C2") ? modelHash : "NONE",
            "code",
            codeIdentity(),
            "rows",
            Digests.sha256(stage.resolve("per-query.tsv")),
            "summary",
            Digests.sha256(stage.resolve("summary.txt")),
            "boundary",
            Digests.sha256(stage.resolve("PRE-VALIDATION.properties"))));
  }

  private static PairwiseSelection.Metrics aggregate(
      List<Row> rows, String family, QueryPlanner.Mode mode, String split) {
    var selected =
        rows.stream()
            .filter(
                r ->
                    r.family().equals(family)
                        && r.mode() == mode
                        && r.header().split().equals(split))
            .toList();
    double ndcg = 0, idMrr = 0, recall = 0;
    int ids = 0;
    for (Row row : selected) {
      ndcg += row.metric().ndcg10();
      recall += row.metric().recall20();
      if (row.header().type().equals("identifier")) {
        ids++;
        idMrr += row.metric().mrr10();
      }
    }
    if (selected.isEmpty() || ids == 0)
      throw new IllegalArgumentException("missing validation/development split");
    return new PairwiseSelection.Metrics(
        ndcg / selected.size(), idMrr / ids, recall / selected.size());
  }

  private static void verifyComparator(Path reference, List<Row> rows) throws IOException {
    Map<String, Row> actual =
        rows.stream()
            .filter(r -> r.family().equals("C0"))
            .collect(Collectors.toMap(r -> r.mode() + "/" + r.header().id(), r -> r));
    List<String> expected = Files.readAllLines(reference);
    if (expected.size() != 541 || actual.size() != 540)
      throw new IOException("frozen comparator row count differs");
    for (String line : expected.subList(1, expected.size())) {
      String[] fields = line.split("\t", -1);
      try {
        Row row = fields.length == 8 ? actual.remove(fields[0] + "/" + fields[1]) : null;
        if (row == null
            || !row.header().split().equals(fields[2])
            || !row.header().type().equals(fields[3])
            || Double.compare(row.metric().ndcg10(), Double.parseDouble(fields[4])) != 0
            || Double.compare(row.metric().mrr10(), Double.parseDouble(fields[5])) != 0
            || Double.compare(row.metric().recall20(), Double.parseDouble(fields[6])) != 0
            || !String.join("|", row.paths()).equals(fields[7]))
          throw new IOException("C0 frozen24 metric/path replay differs");
      } catch (NumberFormatException invalid) {
        throw new IOException("invalid comparator reference", invalid);
      }
    }
    if (!actual.isEmpty()) throw new IOException("missing comparator rows");
  }

  private static SearchEngine engine(
      Path data,
      AuthorizationFilter authorization,
      RankingStrategy strategy,
      LinearRankingModel model)
      throws IOException {
    return new SearchEngine(
        data,
        authorization,
        QueryPlanner.Mode.SOURCE_AWARE,
        RankingProfile.CODE_HIGH,
        new RankingConfiguration(strategy, model));
  }

  private static void ingest(SearchEngine engine, AuthorizationFilter.Access access, Path fixtures)
      throws IOException {
    Path corpus = fixtures.resolve("corpus.tsv");
    if (Files.size(corpus) > 8_388_608) throw new IOException("authored corpus byte quota");
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (String line : Files.readAllLines(corpus)) {
      String[] fields = line.split("\t", -1);
      if (fields.length != 3 || entries.size() >= 1000)
        throw new IOException("corpus record quota");
      entries.add(
          new SnapshotManifest.Entry(
              fields[0],
              engine.contentStore().put(Base64.getDecoder().decode(fields[2])),
              fields[0].endsWith(".java") ? "java" : "text",
              "source",
              Arrays.stream(fields[1].split(" ")).limit(256).toList(),
              "",
              ""));
    }
    engine.ingest(
        access,
        "wave2-" + Digests.sha256(corpus),
        new SnapshotManifest("evaluation", "corpus", "frozen-v3", true, entries, Set.of()),
        GenerationBuilder.Fault.none(),
        () -> false);
  }

  private static Set<String> identifierIds(List<Wave2Inputs.Header> headers) {
    return headers.stream()
        .filter(q -> q.type().equals("identifier"))
        .map(Wave2Inputs.Header::id)
        .collect(Collectors.toSet());
  }

  private static void writeRows(Path path, List<Row> rows) throws IOException {
    List<String> lines =
        new ArrayList<>(
            List.of("family\tmode\tquery\tsplit\ttype\tndcg10\tmrr10\trecall20\treturnedPaths"));
    for (Row row : rows)
      lines.add(
          row.family()
              + "\t"
              + row.mode()
              + "\t"
              + row.header().id()
              + "\t"
              + row.header().split()
              + "\t"
              + row.header().type()
              + "\t"
              + row.metric().ndcg10()
              + "\t"
              + row.metric().mrr10()
              + "\t"
              + row.metric().recall20()
              + "\t"
              + String.join("|", row.paths()));
    Files.write(path, lines, StandardOpenOption.CREATE_NEW);
  }
}
