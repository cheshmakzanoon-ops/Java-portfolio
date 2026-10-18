package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Finite versioned audit→DEV-fit→VAL operator. No held-out command or implicit final opening. */
public final class SourceWave3Study {
  private SourceWave3Study() {}

  private static final List<String> FAMILY_ORDER =
      List.of("ORIGINAL_BASELINE", "R0", "R1", "R2", "R3");
  private static final Set<String> PROTOCOL_FILES =
      Set.of(
          "PREREGISTRATION.md",
          "folds.tsv",
          "comparator-reference.tsv",
          "r0-reference.tsv",
          "IMPLEMENTATION-ADDENDUM-1.md",
          "IMPLEMENTATION-ADDENDUM-2.md",
          "IMPLEMENTATION-ADDENDUM-3.md",
          "IMPLEMENTATION-ADDENDUM-4.md",
          "IMPLEMENTATION-ADDENDUM-5.md");

  public static void run(String phase, Path data, Path fixtures, Path output, Path catalogFile)
      throws IOException {
    if (!Set.of("audit", "fit", "validate").contains(phase))
      throw new IllegalArgumentException(
          "wave3 supports only audit,fit,validate; no held-out access");
    RelevanceEvaluator.verifyFreeze(fixtures);
    verifyProtocol(fixtures.resolve("ranking-wave3"));
    var catalog = SourceApiCatalog.read(catalogFile);
    if (!catalog
        .sourceHash()
        .equals(SourceStudyFiles.hash(Path.of(System.getProperty("java.home"), "lib/src.zip"))))
      throw new IOException("wave3 requires this installed JDK source catalog");
    // Construction validates worker bytes/JDK even in fit, before labels are opened.
    new RankingConfiguration(RankingStrategy.SOURCE_RELATIONS, auditModel(), catalog);
    switch (phase) {
      case "audit" -> audit(data, fixtures, output, catalogFile, catalog);
      case "fit" -> fit(fixtures, output, catalogFile, catalog);
      case "validate" -> validate(data, fixtures, output, catalogFile, catalog);
      default -> throw new AssertionError(phase);
    }
  }

  static void verifyProtocol(Path wave) throws IOException {
    Path freeze = wave.resolve("FREEZE.sha256");
    if (Files.size(freeze) > 4096) throw new IOException("source protocol manifest quota");
    Set<String> seen = new HashSet<>();
    for (String line : Files.readAllLines(freeze)) {
      var fields = line.split("  ", -1);
      if (fields.length != 2
          || !fields[0].matches("[a-f0-9]{64}")
          || !PROTOCOL_FILES.contains(fields[1])
          || !seen.add(fields[1])
          || !SourceStudyFiles.hash(wave.resolve(fields[1])).equals(fields[0]))
        throw new IOException("source protocol freeze mismatch");
    }
    if (!seen.equals(PROTOCOL_FILES)) throw new IOException("incomplete source protocol freeze");
  }

  private static LinearRankingModel auditModel() {
    double[] scales = new double[16];
    Arrays.fill(scales, 1);
    return new LinearRankingModel(
        new double[16], scales, new double[16], "0".repeat(64), "0".repeat(64), 1);
  }

  private static String code() throws IOException {
    var origin = SourceWave3Study.class.getProtectionDomain().getCodeSource().getLocation();
    // Also cover every new phase/fitter/packet/nested class in exploded Maven development runs.
    return RelevanceEvaluator.modelFingerprint(
        "source-wave3/phases-v1/"
            + RankingCodeIdentity.fingerprint(RankingCodeIdentity.artifactPath(origin)));
  }

  private static Map<String, String> baseIdentity(
      Path fixtures, Path catalogFile, SourceApiCatalog catalog) throws IOException {
    return Map.of(
        "code",
        code(),
        "fixture",
        SourceStudyFiles.hash(fixtures.resolve("FREEZE.sha256")),
        "protocol",
        SourceStudyFiles.hash(fixtures.resolve("ranking-wave3/FREEZE.sha256")),
        "catalog",
        SourceStudyFiles.hash(catalogFile),
        "catalogIdentity",
        catalog.identity(),
        "schema",
        SearchFeatureSchema.version(16),
        "interactions",
        SourceRelations.SCHEMA);
  }

  private static void audit(
      Path data, Path fixtures, Path output, Path catalogFile, SourceApiCatalog catalog)
      throws IOException {
    var headers = Wave2Inputs.headers(fixtures, Set.of("development"));
    if (headers.size() != 40) throw new IOException("forty DEV source headers required");
    Files.createDirectories(output);
    Path stage = Files.createDirectory(output.resolve("audit"));
    var packets = new ArrayList<SourceFeaturePacket.Query>();
    Map<String, List<String>> baseline = new TreeMap<>();
    var auth = new AuthorizationFilter();
    var access = auth.update("wave3-evaluator", "evaluation", Set.of("corpus"), true);
    String ranking;
    try (var engine =
        engine(
            data.resolve("audit"), auth, RankingStrategy.SOURCE_RELATIONS, auditModel(), catalog)) {
      ingest(engine, access, fixtures);
      ranking = engine.rankingIdentity();
      for (var header : headers) {
        var request = new SearchEngine.Request(header.text(), 20, null, Map.of());
        var observed =
            engine.auditSourceCandidates(
                access.principal(), request, QueryPlanner.Mode.SOURCE_AWARE);
        packets.add(
            new SourceFeaturePacket.Query(
                header,
                observed.base().candidates(),
                observed.usageKeys(),
                observed.usageReason()));
        baseline.put(
            header.id(),
            engine
                .search(access.principal(), request, QueryPlanner.Mode.BASELINE)
                .results()
                .stream()
                .map(SearchEngine.Hit::path)
                .toList());
      }
    }
    // No grade parser is called until these label-blind files are immutable on disk.
    new SourceFeaturePacket(packets).writeNew(stage.resolve("features.tsv"));
    SourceStudyFiles.paths(stage.resolve("baseline-paths.tsv"), baseline);
    Map<String, String> identity = new TreeMap<>(baseIdentity(fixtures, catalogFile, catalog));
    identity.put("ranking", ranking);
    identity.put("features", SourceStudyFiles.hash(stage.resolve("features.tsv")));
    identity.put("baseline", SourceStudyFiles.hash(stage.resolve("baseline-paths.tsv")));
    SourceStudyFiles.write(stage.resolve("identity.properties"), identity);
  }

  private static Map<String, String> verifiedAudit(
      Path fixtures, Path output, Path catalogFile, SourceApiCatalog catalog) throws IOException {
    var identity = SourceStudyFiles.read(output.resolve("audit/identity.properties"));
    for (var entry : baseIdentity(fixtures, catalogFile, catalog).entrySet())
      SourceStudyFiles.check(identity, entry.getKey(), entry.getValue());
    SourceStudyFiles.check(
        identity, "features", SourceStudyFiles.hash(output.resolve("audit/features.tsv")));
    SourceStudyFiles.check(
        identity, "baseline", SourceStudyFiles.hash(output.resolve("audit/baseline-paths.tsv")));
    return identity;
  }

  private static void fit(Path fixtures, Path output, Path catalogFile, SourceApiCatalog catalog)
      throws IOException {
    var identity = verifiedAudit(fixtures, output, catalogFile, catalog);
    var packet = SourceFeaturePacket.read(output.resolve("audit/features.tsv"));
    var headers = packet.queries().stream().map(SourceFeaturePacket.Query::header).toList();
    if (!headers.equals(Wave2Inputs.headers(fixtures, Set.of("development"))))
      throw new IOException("source DEV export header differs");
    var labels = Wave2Inputs.labels(fixtures, headers);
    var full = packet.attachDevelopmentLabels(labels, 16);
    var assignments = GroupedFolds.assign(full);
    Wave2Study.verifyFolds(fixtures.resolve("ranking-wave3/folds.tsv"), full, assignments);
    var identifiers = identifierIds(headers);
    var base = PairwiseSelection.metrics(PairwiseSelection.basePaths(full), labels, identifiers);
    var original =
        PairwiseSelection.metrics(
            SourceStudyFiles.paths(output.resolve("audit/baseline-paths.tsv"), labels.keySet()),
            labels,
            identifiers);
    Path stage = Files.createDirectory(output.resolve("fit"));
    var report = new ArrayList<String>();
    report.add("R0=" + base);
    report.add("ORIGINAL_BASELINE=" + original);
    var selector = new SourcePairwiseSelection(); // Shared entire900s budget across BOTH families.
    Map<String, SourcePairwiseSelection.Result> results = new LinkedHashMap<>();
    for (String family : List.of("R1", "R2")) {
      int dimensions = family.equals("R1") ? 12 : 16;
      var result =
          selector.select(
              packet.attachDevelopmentLabels(labels, dimensions),
              labels,
              identifiers,
              assignments,
              identity.get("features"),
              SourceStudyFiles.hash(fixtures.resolve("ranking-wave3/folds.tsv")),
              base,
              original);
      results.put(family, result);
      report.add(family + " status=" + result.status());
      for (var fold : result.folds()) {
        String name = family + "-l2-" + fold.l2() + "-fold-" + fold.fold();
        report.add(name + " failure=" + fold.failure() + " fit=" + describe(fold.fit()));
        if (fold.fit() != null) fold.fit().model().writeNew(stage.resolve(name + ".bin"));
        SourceStudyFiles.paths(stage.resolve(name + "-paths.tsv"), fold.paths());
      }
      for (var trial : result.trials()) {
        report.add(
            family
                + " trial="
                + trial.l2()
                + " metrics="
                + trial.metrics()
                + " failure="
                + trial.failure());
        SourceStudyFiles.paths(
            stage.resolve(family + "-l2-" + trial.l2() + "-oof.tsv"), trial.outOfFoldPaths());
      }
      if (result.selected() != null)
        result.selected().model().writeNew(stage.resolve(family + "-selected.bin"));
      SourceStudyFiles.paths(
          stage.resolve(family + "-selected-oof.tsv"), result.selectedOutOfFoldPaths());
    }
    var r2 = results.get("R2");
    boolean r3Eligible = false;
    if (r2.selected() != null) {
      var paths = new TreeMap<>(r2.selectedOutOfFoldPaths());
      for (var query : packet.queries())
        if (PlainTextQuery.identifier(query.header().text()))
          paths.put(query.header().id(), query.usagePaths().stream().limit(20).toList());
      var metric = PairwiseSelection.metrics(paths, labels, identifiers);
      r3Eligible = metric.dominates(base) && metric.dominates(original);
      report.add(
          "R3 fixedR2model="
              + r2.selected().model().identity()
              + " metrics="
              + metric
              + " eligible="
              + r3Eligible);
      SourceStudyFiles.paths(stage.resolve("R3-oof.tsv"), paths);
    } else report.add("R3=INELIGIBLE_NO_ELIGIBLE_R2_MODEL");
    // Preserve audit retrieval coverage, full ideal denominators and bypass reasons without a new
    // fit.
    for (var query : packet.queries())
      report.add(
          "usage "
              + query.header().id()
              + " "
              + query.usageReason()
              + " candidates="
              + query.candidates().size());
    Files.write(stage.resolve("report.txt"), report, StandardOpenOption.CREATE_NEW);
    Map<String, String> fitIdentity = new TreeMap<>();
    fitIdentity.put("code", code());
    fitIdentity.put("audit", SourceStudyFiles.hash(output.resolve("audit/identity.properties")));
    fitIdentity.put(
        "R1",
        results.get("R1").selected() == null
            ? "NONE"
            : SourceStudyFiles.hash(stage.resolve("R1-selected.bin")));
    fitIdentity.put(
        "R2",
        r2.selected() == null ? "NONE" : SourceStudyFiles.hash(stage.resolve("R2-selected.bin")));
    fitIdentity.put("R3", Boolean.toString(r3Eligible));
    for (var entry : SourceStudyFiles.manifest(stage).entrySet())
      fitIdentity.put("file." + entry.getKey(), entry.getValue());
    SourceStudyFiles.write(stage.resolve("identity.properties"), fitIdentity);
  }

  private static String describe(PairwiseTrainer.Result fit) {
    return fit == null
        ? "NONE"
        : "model="
            + fit.model().identity()
            + ",iterations="
            + fit.iterations()
            + ",loss="
            + fit.initialLoss()
            + "→"
            + fit.finalLoss()
            + ",pairs="
            + fit.pairs()
            + ",queries="
            + fit.eligibleQueries();
  }

  private record Row(
      String family,
      QueryPlanner.Mode mode,
      Wave2Inputs.Header header,
      RelevanceMetrics.Values metric,
      List<String> paths) {}

  private static void validate(
      Path data, Path fixtures, Path output, Path catalogFile, SourceApiCatalog catalog)
      throws IOException {
    verifiedAudit(fixtures, output, catalogFile, catalog);
    var fit = SourceStudyFiles.read(output.resolve("fit/identity.properties"));
    SourceStudyFiles.check(fit, "code", code());
    SourceStudyFiles.check(
        fit, "audit", SourceStudyFiles.hash(output.resolve("audit/identity.properties")));
    var actual = SourceStudyFiles.manifest(output.resolve("fit"));
    Set<String> recorded =
        fit.keySet().stream()
            .filter(k -> k.startsWith("file."))
            .map(k -> k.substring(5))
            .collect(Collectors.toSet());
    if (!recorded.equals(actual.keySet()))
      throw new IOException("source fit artifact inventory changed");
    for (var entry : actual.entrySet())
      SourceStudyFiles.check(fit, "file." + entry.getKey(), entry.getValue());
    Map<String, LinearRankingModel> models = new HashMap<>();
    for (String family : List.of("R1", "R2")) {
      String hash = fit.get(family);
      if (hash == null) throw new IOException("source fit model declaration missing");
      if (hash.equals("NONE")) continue;
      var path = output.resolve("fit/" + family + "-selected.bin");
      SourceStudyFiles.check(fit, family, SourceStudyFiles.hash(path));
      var model = LinearRankingModel.read(path);
      if (model.dimensions() != (family.equals("R1") ? 12 : 16)
          || !model
              .trainingExport()
              .equals(SourceStudyFiles.hash(output.resolve("audit/features.tsv")))
          || !model
              .folds()
              .equals(SourceStudyFiles.hash(fixtures.resolve("ranking-wave3/folds.tsv"))))
        throw new IOException("source model provenance differs");
      models.put(family, model);
    }
    if (!Set.of("true", "false").contains(fit.get("R3")))
      throw new IOException("source R3 eligibility declaration");
    if (fit.get("R3").equals("true")) {
      if (!models.containsKey("R2")) throw new IOException("source R3 missing exact R2 model");
      models.put("R3", models.get("R2"));
    }
    var families =
        FAMILY_ORDER.stream()
            .filter(f -> f.equals("ORIGINAL_BASELINE") || f.equals("R0") || models.containsKey(f))
            .toList();
    Path stage = Files.createDirectory(output.resolve("validation"));
    Map<String, String> boundary = new TreeMap<>(baseIdentity(fixtures, catalogFile, catalog));
    boundary.put("audit", SourceStudyFiles.hash(output.resolve("audit/identity.properties")));
    boundary.put("fit", SourceStudyFiles.hash(output.resolve("fit/identity.properties")));
    boundary.put("families", String.join(",", families));
    for (var entry : models.entrySet())
      boundary.put("model." + entry.getKey(), entry.getValue().identity());
    SourceStudyFiles.write(stage.resolve("PRE-VALIDATION.properties"), boundary);
    // Only now may the20validation queries/grades be parsed. No code path accepts held-out.
    var headers = Wave2Inputs.headers(fixtures, Set.of("development", "validation"));
    if (headers.size() != 60) throw new IOException("sixty DEV/VAL headers required");
    var labels = Wave2Inputs.evaluationLabels(fixtures, headers);
    var rows = new ArrayList<Row>();
    var identities = new TreeMap<String, String>();
    for (String family : families) {
      var auth = new AuthorizationFilter();
      var access = auth.update("wave3-evaluator", "evaluation", Set.of("corpus"), true);
      RankingStrategy strategy = strategy(family);
      try (var engine =
          engine(
              data.resolve(family),
              auth,
              strategy,
              models.get(family),
              strategy.structural() ? catalog : null)) {
        ingest(engine, access, fixtures);
        identities.put(family, engine.rankingIdentity());
        for (var mode : QueryPlanner.Mode.values())
          for (var header : headers) {
            var response =
                engine.search(
                    access.principal(),
                    new SearchEngine.Request(header.text(), 20, null, Map.of()),
                    mode);
            var paths = response.results().stream().map(SearchEngine.Hit::path).toList();
            rows.add(
                new Row(
                    family,
                    mode,
                    header,
                    RelevanceMetrics.evaluate(paths, labels.get(header.id())),
                    paths));
          }
      }
    }
    writeRows(stage.resolve("per-query.tsv"), rows);
    SourceStudyFiles.write(stage.resolve("family-identities.properties"), identities);
    try {
      replay(fixtures.resolve("ranking-wave3/comparator-reference.tsv"), rows, "ORIGINAL_BASELINE");
      replay(fixtures.resolve("ranking-wave3/r0-reference.tsv"), rows, "R0");
      verifyBaseline(rows);
    } catch (IOException failed) {
      Files.writeString(
          stage.resolve("REPLAY-FAILED.txt"),
          failed.getMessage() + "\n",
          StandardOpenOption.CREATE_NEW);
      throw failed;
    }
    var summaries = new ArrayList<String>();
    for (String family : families)
      for (var mode : QueryPlanner.Mode.values())
        for (String split : List.of("development", "validation"))
          summaries.add(
              family + " " + mode + " " + split + " " + aggregate(rows, family, mode, split));
    var baseline = aggregate(rows, "ORIGINAL_BASELINE", QueryPlanner.Mode.BASELINE, "validation");
    var r0 = aggregate(rows, "R0", QueryPlanner.Mode.SOURCE_AWARE, "validation");
    String chosen = "ORIGINAL_BASELINE";
    var best = baseline;
    for (String family : families) {
      if (family.equals("ORIGINAL_BASELINE")) continue;
      var metric = aggregate(rows, family, QueryPlanner.Mode.SOURCE_AWARE, "validation");
      if (metric.dominates(baseline)
          && metric.dominates(r0)
          && metric.objective() > best.objective()) {
        chosen = family;
        best = metric;
      }
    }
    summaries.add("Selected=" + chosen + " " + best);
    summaries.add(
        "Repeated DEV/VAL use; two prior aggregate held-out exposures. No new held-out opened or authorized. Authored corpus only; no diagnostic winner adoption.");
    Files.write(stage.resolve("summary.txt"), summaries, StandardOpenOption.CREATE_NEW);
    SourceStudyFiles.write(
        stage.resolve("selection.properties"),
        Map.of(
            "family",
            chosen,
            "profile",
            "CODE_HIGH",
            "mode",
            chosen.equals("ORIGINAL_BASELINE") ? "BASELINE" : "SOURCE_AWARE",
            "strategy",
            strategy(chosen).name(),
            "model",
            models.containsKey(chosen) ? models.get(chosen).identity() : "NONE",
            "catalog",
            models.containsKey(chosen) ? catalog.identity() : "NONE",
            "rows",
            SourceStudyFiles.hash(stage.resolve("per-query.tsv")),
            "summary",
            SourceStudyFiles.hash(stage.resolve("summary.txt")),
            "boundary",
            SourceStudyFiles.hash(stage.resolve("PRE-VALIDATION.properties")),
            "code",
            code()));
  }

  static RankingStrategy strategy(String family) {
    return switch (family) {
      case "ORIGINAL_BASELINE" -> RankingStrategy.CLASSIC;
      case "R0" -> RankingStrategy.SYNTAX_GATED;
      case "R1" -> RankingStrategy.SOURCE_MEMBERS;
      case "R2" -> RankingStrategy.SOURCE_RELATIONS;
      case "R3" -> RankingStrategy.SOURCE_USAGE;
      default -> throw new IllegalArgumentException("unknown source family");
    };
  }

  private static PairwiseSelection.Metrics aggregate(
      List<Row> rows, String family, QueryPlanner.Mode mode, String split) {
    double ndcg = 0, mrr = 0, recall = 0;
    int count = 0, ids = 0;
    for (var row : rows)
      if (row.family().equals(family) && row.mode() == mode && row.header().split().equals(split)) {
        count++;
        ndcg += row.metric().ndcg10();
        recall += row.metric().recall20();
        if (row.header().type().equals("identifier")) {
          ids++;
          mrr += row.metric().mrr10();
        }
      }
    if (count == 0 || ids == 0)
      throw new IllegalArgumentException("source split lacks complete metric denominator");
    return new PairwiseSelection.Metrics(ndcg / count, mrr / ids, recall / count);
  }

  private static void verifyBaseline(List<Row> rows) throws IOException {
    Map<String, Row> original = new HashMap<>();
    for (var row : rows)
      if (row.family().equals("ORIGINAL_BASELINE") && row.mode() == QueryPlanner.Mode.BASELINE)
        original.put(row.header().id(), row);
    for (var row : rows)
      if (row.mode() == QueryPlanner.Mode.BASELINE) {
        var expected = original.get(row.header().id());
        if (expected == null
            || !expected.paths().equals(row.paths())
            || !expected.metric().equals(row.metric()))
          throw new IOException("source family altered exact original BASELINE");
      }
  }

  private static void replay(Path file, List<Row> rows, String family) throws IOException {
    Map<String, Row> actual = new HashMap<>();
    var legacy = Set.copyOf(QueryPlanner.Mode.legacyValues());
    for (var row : rows)
      if (row.family().equals(family) && legacy.contains(row.mode()))
        actual.put(row.mode() + "/" + row.header().id(), row);
    if (Files.size(file) > 2_097_152) throw new IOException("source comparator quota");
    var lines = Files.readAllLines(file);
    if (lines.size() != 541 || actual.size() != 540)
      throw new IOException("source comparator requires all540rows");
    for (String line : lines.subList(1, lines.size())) {
      var f = line.split("\t", -1);
      try {
        Row row = f.length == 8 ? actual.remove(f[0] + "/" + f[1]) : null;
        if (row == null
            || !row.header().split().equals(f[2])
            || !row.header().type().equals(f[3])
            || Double.compare(row.metric().ndcg10(), Double.parseDouble(f[4])) != 0
            || Double.compare(row.metric().mrr10(), Double.parseDouble(f[5])) != 0
            || Double.compare(row.metric().recall20(), Double.parseDouble(f[6])) != 0
            || !String.join("|", row.paths()).equals(f[7]))
          throw new IOException("source " + family + " frozen25 path/metric replay differs");
      } catch (NumberFormatException bad) {
        throw new IOException("source comparator numeric syntax", bad);
      }
    }
    if (!actual.isEmpty()) throw new IOException("source comparator omitted rows");
  }

  private static void writeRows(Path path, List<Row> rows) throws IOException {
    var lines = new ArrayList<String>();
    lines.add("family\tmode\tquery\tsplit\ttype\tndcg10\tmrr10\trecall20\treturnedPaths");
    for (var row : rows)
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

  private static Set<String> identifierIds(List<Wave2Inputs.Header> headers) {
    return headers.stream()
        .filter(h -> h.type().equals("identifier"))
        .map(Wave2Inputs.Header::id)
        .collect(Collectors.toSet());
  }

  private static SearchEngine engine(
      Path path,
      AuthorizationFilter auth,
      RankingStrategy strategy,
      LinearRankingModel model,
      SourceApiCatalog catalog)
      throws IOException {
    return new SearchEngine(
        path,
        auth,
        QueryPlanner.Mode.SOURCE_AWARE,
        RankingProfile.CODE_HIGH,
        new RankingConfiguration(strategy, model, catalog));
  }

  private static void ingest(SearchEngine engine, AuthorizationFilter.Access access, Path fixtures)
      throws IOException {
    Path file = fixtures.resolve("corpus.tsv");
    if (Files.size(file) > 8_388_608) throw new IOException("source corpus byte quota");
    var entries = new ArrayList<SnapshotManifest.Entry>();
    for (String line : Files.readAllLines(file)) {
      var fields = line.split("\t", -1);
      if (fields.length != 3 || entries.size() >= 1000)
        throw new IOException("source corpus record quota");
      try {
        entries.add(
            new SnapshotManifest.Entry(
                fields[0],
                engine.contentStore().put(Base64.getDecoder().decode(fields[2])),
                fields[0].endsWith(".java") ? "java" : "text",
                "source",
                Arrays.stream(fields[1].split(" ")).limit(256).toList(),
                "",
                ""));
      } catch (IllegalArgumentException bad) {
        throw new IOException("source corpus encoding", bad);
      }
    }
    engine.ingest(
        access,
        "wave3-" + Digests.sha256(file),
        new SnapshotManifest("evaluation", "corpus", "frozen-v3", true, entries, Set.of()),
        GenerationBuilder.Fault.none(),
        () -> false);
  }
}
