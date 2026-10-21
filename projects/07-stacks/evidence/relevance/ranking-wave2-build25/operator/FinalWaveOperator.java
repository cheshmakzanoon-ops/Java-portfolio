package io.github.cheshmakzanoon.portfolio.stacks.wave2operator;

import io.github.cheshmakzanoon.portfolio.stacks.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Separate local operator. All inference and metrics load from the unchanged copied build25 JAR. */
public final class FinalWaveOperator {
  private static final String WAVE = "ranking-wave2-build25-C1-final";
  private static final String SOURCE = "c0ab80c6a5e14d5836b9e69813f18d16acda382a97686df2d020c45622a2e8d2";
  private static final String JAR = "e2d947c97a6b5b5a5fc6d6696d1ca56241eed11f9cfbb772b0d1880a6217dbc7";
  private static final String CANDIDATE = "f938b3fea0324fdbeac8bb781b42bec7f38f99af4f86cda5db8a30495bc98399";
  private static final String BASELINE = "75a5f85ef95c35c0a1f77d1cc4a35b3cca8fe1a90eef9ad8664cb85656316c0f";
  private static final String PROJECT = "projects/07-stacks/";
  private static final String OUTPUT = PROJECT + "evidence/relevance/ranking-wave2-build25/final-attempt-1";
  private static final String GLOBAL = PROJECT + "fixtures/ranking-wave2/FINAL-ONCE-build25-C1.properties";
  private static final String DATA = PROJECT + ".runtime/ranking-wave2-build25-final-attempt1";
  private static final long MAX_METADATA = 67_108_864;
  private FinalWaveOperator() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 2 && args[0].equals("self-test")) { selfTest(Path.of(args[1])); return; }
    if (args.length < 2 || !(args[0].equals("check") || args[0].equals("execute")))
      throw new IllegalArgumentException("check CONTEXT | self-test FRESH_TEMP | execute CONTEXT APPROVAL");
    Path context = Path.of(args[1]);
    Properties properties = verifyContext(context);
    if (args[0].equals("check") && args.length == 2) {
      System.out.println("CHECK PASS: frozen C1 and BASELINE identities; no corpus, judgments, data or seals opened.");
      return;
    }
    if (!args[0].equals("execute") || args.length != 3) throw new IllegalArgumentException("explicit approval required");
    execute(context, properties, Path.of(args[2]));
  }

  private static Properties verifyContext(Path context) throws IOException {
    Properties p = readProperties(context, 65536);
    require(p, "wave", WAVE); require(p, "sourceFingerprint", SOURCE); require(p, "artifactSha256", JAR);
    require(p, "candidateFingerprint", CANDIDATE); require(p, "baselineFingerprint", BASELINE);
    require(p, "candidateFamily", "C1"); require(p, "strategy", "SYNTAX_GATED");
    require(p, "mode", "SOURCE_AWARE"); require(p, "profile", "CODE_HIGH"); require(p, "model", "NONE");
    require(p, "outputRelative", OUTPUT); require(p, "globalSealRelative", GLOBAL); require(p, "dataRelative", DATA);
    Path root = Path.of(required(p, "repository")).toAbsolutePath().normalize();
    int count;
    try { count = Integer.parseInt(required(p, "bindingCount")); }
    catch (NumberFormatException bad) { throw new IOException("invalid binding count", bad); }
    if (count < 15 || count > 32) throw new IOException("binding count quota");
    Set<String> bound = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String relative = required(p, "binding." + i + ".relative");
      if (!bound.add(relative)) throw new IOException("duplicate binding path");
      requireHash(required(p, "binding." + i + ".sha256"), hash(resolve(root, relative), MAX_METADATA));
    }
    Path artifact = resolve(root, required(p, "artifactRelative"));
    requireHash(JAR, hash(artifact, 536870912));
    Properties selected = readProperties(resolve(root, required(p, "selectionRelative")), 16384);
    require(selected, "family", "C1"); require(selected, "strategy", "SYNTAX_GATED");
    require(selected, "profile", "CODE_HIGH"); require(selected, "mode", "SOURCE_AWARE"); require(selected, "model", "NONE");
    // These are exact immutable JAR/class/config identities, without opening a search index or labels.
    requireHash(CANDIDATE, RelevanceEvaluator.modelFingerprint(
        new RankingConfiguration(RankingStrategy.SYNTAX_GATED, null).identity(RankingProfile.CODE_HIGH)));
    requireHash(BASELINE, RelevanceEvaluator.modelFingerprint(RankingConfiguration.classic().identity(RankingProfile.CODE_HIGH)));
    requireHash(required(p, "operatorBinarySha256"), operatorBinary());
    requireHash(required(p, "fixtureManifestSha256"), hash(root.resolve(PROJECT + "fixtures/FREEZE.sha256"), 65536));
    return p;
  }

  private static void execute(Path context, Properties p, Path approval) throws Exception {
    Properties authorized = readProperties(approval, 4096);
    require(authorized, "action", "execute-once"); require(authorized, "wave", WAVE);
    requireHash(required(authorized, "contextSha256"), hash(context, 65536));
    requireHash(required(authorized, "operatorSourceSha256"), required(p, "operatorSourceSha256"));
    requireHash(required(authorized, "operatorBinarySha256"), required(p, "operatorBinarySha256"));
    if (!required(authorized, "approvedBy").equals("root")) throw new IOException("coordinator approval required");
    Path root = Path.of(required(p, "repository")).toAbsolutePath().normalize();
    Path output = resolve(root, OUTPUT), global = resolve(root, GLOBAL), data = resolve(root, DATA);
    if (Files.exists(global, LinkOption.NOFOLLOW_LINKS) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(data, LinkOption.NOFOLLOW_LINKS)) throw new IOException("once-only final context already used or stale data present");
    TreeMap<String, String> decision = new TreeMap<>();
    decision.put("wave", WAVE); decision.put("contextSha256", hash(context, 65536));
    decision.put("candidateFingerprint", CANDIDATE); decision.put("baselineFingerprint", BASELINE);
    decision.put("artifactSha256", JAR); decision.put("approvalSha256", hash(approval, 4096));
    decision.put("selectionSha256", required(p, "selectionSha256"));
    decision.put("fixtureManifestSha256", required(p, "fixtureManifestSha256"));
    decision.put("protocolSha256", required(p, "protocolSha256"));
    // Both new paths are fixed to this versioned wave. Original first-final seals are never opened/written.
    Files.createDirectory(output);
    try (var parent = FileChannel.open(output.getParent(), StandardOpenOption.READ)) { parent.force(true); }
    createSeal(global, decision);
    createSeal(output.resolve("HELDOUT-OPENED.properties"), decision);
    Instant start = Instant.now();
    decision.put("startedUtc", start.toString());
    writeMap(output.resolve("EXECUTION-STARTED.properties"), decision);
    try {
      Path fixtures = root.resolve(PROJECT + "fixtures");
      // First access to actual corpus/judgment bytes is AFTER explicit approval and durable once-only seals.
      RelevanceEvaluator.verifyFreeze(fixtures);
      var baselineAuth = new AuthorizationFilter();
      var baseAccess = baselineAuth.update("wave2-final", "evaluation", Set.of("corpus"), true);
      var candidateAuth = new AuthorizationFilter();
      var candidateAccess = candidateAuth.update("wave2-final", "evaluation", Set.of("corpus"), true);
      try (SearchEngine base = new SearchEngine(data.resolve("baseline"), baselineAuth,
              QueryPlanner.Mode.BASELINE, RankingProfile.CODE_HIGH, RankingConfiguration.classic());
          SearchEngine candidate = new SearchEngine(data.resolve("C1"), candidateAuth,
              QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CODE_HIGH,
              new RankingConfiguration(RankingStrategy.SYNTAX_GATED, null))) {
        requireHash(BASELINE, RelevanceEvaluator.modelFingerprint(base.rankingIdentity()));
        requireHash(CANDIDATE, RelevanceEvaluator.modelFingerprint(candidate.rankingIdentity()));
        ingest(base, baseAccess, fixtures); ingest(candidate, candidateAccess, fixtures);
        List<Judged> queries = heldout(fixtures.resolve("judgments.tsv"));
        List<String> rows = new ArrayList<>(List.of("mode\tquery\tsplit\ttype\tndcg10\tmrr10\trecall20\treturnedPaths"));
        List<String> summary = new ArrayList<>(List.of("mode\tndcg10\tidentifierMrr10\trecall20\tqueries\tidentifierQueries\ttargetPass"));
        Map<QueryPlanner.Mode, Metrics> metrics = new EnumMap<>(QueryPlanner.Mode.class);
        for (var mode : QueryPlanner.Mode.values()) {
          SearchEngine engine = mode == QueryPlanner.Mode.BASELINE ? base : candidate;
          double ndcg = 0, idMrr = 0, recall = 0; int identifiers = 0;
          for (var query : queries) {
            var response = engine.search("wave2-final", new SearchEngine.Request(query.text(), 20, null, Map.of()), mode);
            var paths = response.results().stream().map(SearchEngine.Hit::path).toList();
            var value = RelevanceMetrics.evaluate(paths, query.grades());
            ndcg += value.ndcg10(); recall += value.recall20();
            if (query.type().equals("identifier")) { idMrr += value.mrr10(); identifiers++; }
            rows.add(mode + "\t" + query.id() + "\theld-out\t" + query.type() + "\t" + value.ndcg10()
                + "\t" + value.mrr10() + "\t" + value.recall20() + "\t" + String.join("|", paths));
          }
          Metrics value = new Metrics(ndcg / queries.size(), idMrr / identifiers, recall / queries.size());
          metrics.put(mode, value);
          summary.add(mode + "\t" + value.ndcg() + "\t" + value.identifierMrr() + "\t" + value.recall()
              + "\t" + queries.size() + "\t" + identifiers + "\t" + value.targetPass());
        }
        if (rows.size() != 181 || summary.size() != 10) throw new IOException("final result count mismatch");
        Files.write(output.resolve("heldout-per-query.tsv"), rows, StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("heldout-summary.tsv"), summary, StandardOpenOption.CREATE_NEW);
        Metrics baseline = metrics.get(QueryPlanner.Mode.BASELINE), fixedCandidate = metrics.get(QueryPlanner.Mode.SOURCE_AWARE);
        // Only the frozen SOURCE_AWARE candidate enters this guard. Diagnostic winners are never selected.
        boolean retained = adopt(baseline, fixedCandidate);
        TreeMap<String, String> deployment = new TreeMap<>();
        deployment.put("frozenFamily", "C1"); deployment.put("frozenMode", "SOURCE_AWARE");
        deployment.put("retainedFamily", retained ? "C1" : "ORIGINAL_BASELINE");
        deployment.put("retainedMode", retained ? "SOURCE_AWARE" : "BASELINE");
        deployment.put("retainedStrategy", retained ? "SYNTAX_GATED" : "CLASSIC");
        deployment.put("profile", "CODE_HIGH"); deployment.put("model", "NONE");
        deployment.put("candidateMeetsTargets", Boolean.toString(fixedCandidate.targetPass()));
        deployment.put("adoptionGuard", "strict nDCG improvement and no identifierMRR regression; diagnostics excluded");
        deployment.put("repeatedTestExposure", "first-final history exists; this is an explicitly versioned repeated test, not a fresh blind sample");
        deployment.put("compiledDefaultsChanged", "false");
        writeMap(output.resolve("deployment.properties"), deployment);
        decision.put("finishedUtc", Instant.now().toString());
        decision.put("elapsedMillis", Long.toString(Duration.between(start, Instant.now()).toMillis()));
        decision.put("resultRows", "180"); decision.put("heldoutQueries", "20"); decision.put("modes", "9");
        decision.put("perQuerySha256", hash(output.resolve("heldout-per-query.tsv"), MAX_METADATA));
        decision.put("summarySha256", hash(output.resolve("heldout-summary.tsv"), MAX_METADATA));
        decision.put("deploymentSha256", hash(output.resolve("deployment.properties"), 16384));
        decision.put("baselineRuntime", base.rankingIdentity()); decision.put("candidateRuntime", candidate.rankingIdentity());
        decision.put("retainedFamily", deployment.get("retainedFamily"));
      }
      decision.put("finishedUtc", Instant.now().toString());
      decision.put("elapsedMillis", Long.toString(Duration.between(start, Instant.now()).toMillis()));
      writeMap(output.resolve("COMPLETED.properties"), decision);
      System.out.println("COMPLETED: exactly180 fixed final rows; deployment guard=" + decision.get("retainedFamily"));
    } catch (Exception failed) {
      TreeMap<String, String> failure = new TreeMap<>(decision); failure.put("failedUtc", Instant.now().toString());
      failure.put("exception", failed.getClass().getName()); failure.put("onceOnlyAttemptConsumed", "true");
      try { writeMap(output.resolve("FAILED.properties"), failure); }
      catch (IOException markerFailure) { failed.addSuppressed(markerFailure); }
      throw failed;
    }
  }

  private record Metrics(double ndcg, double identifierMrr, double recall) {
    boolean targetPass() { return ndcg >= .75 && identifierMrr >= .90; }
  }
  private static boolean adopt(Metrics baseline, Metrics fixed) {
    return fixed.ndcg() > baseline.ndcg() && fixed.identifierMrr() >= baseline.identifierMrr();
  }
  private record Judged(String id, String type, String text, Map<String, Integer> grades) {}

  private static List<Judged> heldout(Path path) throws IOException {
    if (Files.size(path) > 2097152) throw new IOException("judgment byte quota");
    List<Judged> queries = new ArrayList<>(); Set<String> ids = new HashSet<>();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] prefix = line.split("\t", 3);
        if (prefix.length < 2 || !prefix[1].equals("held-out")) continue;
        String[] fields = line.split("\t", 5);
        if (fields.length != 5 || queries.size() >= 20 || !fields[0].matches("[A-Za-z0-9_-]{1,128}")
            || !ids.add(fields[0]) || !Set.of("identifier", "prose").contains(fields[2])) throw new IOException("heldout query identity/type/quota");
        try {
          QueryAst.parse(fields[3]); Map<String, Integer> grades = new TreeMap<>();
          for (String item : fields[4].split(",")) {
            int colon = item.lastIndexOf(':'); if (colon <= 0) throw new IllegalArgumentException("grade record");
            String document = SnapshotManifest.normalize(item.substring(0, colon));
            int grade = Integer.parseInt(item.substring(colon + 1));
            if (grade < 1 || grade > 3 || grades.put(document, grade) != null) throw new IllegalArgumentException("grade bound/duplicate");
          }
          if (grades.isEmpty()) throw new IllegalArgumentException("empty grades");
          queries.add(new Judged(fields[0], fields[2], fields[3], Map.copyOf(grades)));
        } catch (IllegalArgumentException invalid) { throw new IOException("invalid frozen heldout row", invalid); }
      }
    }
    if (queries.size() != 20 || queries.stream().filter(q -> q.type().equals("identifier")).count() != 10)
      throw new IOException("twenty heldout queries with ten identifiers required");
    return List.copyOf(queries);
  }

  private static void ingest(SearchEngine engine, AuthorizationFilter.Access access, Path fixtures) throws IOException {
    Path corpus = fixtures.resolve("corpus.tsv"); if (Files.size(corpus) > 8388608) throw new IOException("corpus quota");
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (String line : Files.readAllLines(corpus)) {
      String[] fields = line.split("\t", -1);
      if (fields.length != 3 || entries.size() >= 1000) throw new IOException("corpus format/quota");
      entries.add(new SnapshotManifest.Entry(fields[0], engine.contentStore().put(Base64.getDecoder().decode(fields[2])),
          fields[0].endsWith(".java") ? "java" : "text", "source", Arrays.stream(fields[1].split(" ")).limit(256).toList(), "", ""));
    }
    engine.ingest(access, "final25-" + Digests.sha256(corpus), new SnapshotManifest("evaluation", "corpus",
        "frozen-v3", true, entries, Set.of()), GenerationBuilder.Fault.none(), () -> false);
  }

  private static Path resolve(Path root, String relative) throws IOException {
    Path supplied = Path.of(relative), path = root.resolve(supplied).normalize();
    if (supplied.isAbsolute() || !path.startsWith(root) || relative.contains("\\") || relative.contains("\n"))
      throw new IOException("unsafe context path");
    return path;
  }
  private static String required(Properties p, String key) throws IOException {
    String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IOException("missing " + key); return value;
  }
  private static void require(Properties p, String key, String expected) throws IOException { requireHash(expected, required(p, key)); }
  private static void requireHash(String expected, String actual) throws IOException {
    if (!expected.equals(actual)) throw new IOException("frozen final identity mismatch");
  }
  private static String hash(Path path, long limit) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IOException("bounded regular identity file required");
    return Digests.sha256(path);
  }
  private static Properties readProperties(Path path, long limit) throws IOException {
    hash(path, limit); Properties p = new Properties();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      if (line.isEmpty()) continue;
      int equals = line.indexOf('=');
      if (equals <= 0 || p.putIfAbsent(line.substring(0, equals), line.substring(equals + 1)) != null)
        throw new IOException("invalid/duplicate context key");
    }
    return p;
  }
  private static void createSeal(Path path, Map<String, String> values) throws IOException {
    byte[] bytes = mapBytes(values); if (bytes.length > 4096) throw new IOException("seal quota");
    try (var out = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)) {
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
    }
    try (var parent = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { parent.force(true); }
  }

  private static String operatorBinary() throws IOException {
    try {
      Path origin = Path.of(FinalWaveOperator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      List<Path> files = new ArrayList<>();
      try (var walk = Files.walk(origin)) {
        var it = walk.iterator(); int seen = 0;
        while (it.hasNext()) {
          Path path = it.next(); if (++seen > 512 || Files.isSymbolicLink(path)) throw new IOException("operator origin bound");
          if (Files.isRegularFile(path) && path.toString().endsWith(".class")) {
            if (files.size() >= 128 || Files.size(path) > 1048576) throw new IOException("operator class quota"); files.add(path);
          }
        }
      }
      if (files.isEmpty()) throw new IOException("missing operator classes");
      files.sort(Comparator.comparing(path -> origin.relativize(path).toString().replace('\\', '/')));
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      for (Path file : files) {
        digest.update(origin.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        digest.update(Files.readAllBytes(file));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.net.URISyntaxException | java.security.NoSuchAlgorithmException invalid) { throw new IOException("operator binary identity", invalid); }
  }
  private static byte[] mapBytes(Map<String, String> values) throws IOException {
    StringBuilder out = new StringBuilder();
    for (var entry : new TreeMap<>(values).entrySet()) {
      if (entry.getKey().contains("=") || entry.getKey().contains("\n") || entry.getValue().contains("\n") || entry.getValue().contains("\r"))
        throw new IOException("metadata syntax");
      out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }
  private static void writeMap(Path path, Map<String, String> values) throws IOException {
    Files.write(path, mapBytes(values), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  private static void selfTest(Path temporary) throws Exception {
    Files.createDirectory(temporary); int checked = 0;
    Path labels = temporary.resolve("synthetic.tsv"); StringBuilder valid = new StringBuilder("ignored\tdevelopment\tbad\t(\tinvalid\n");
    for (int i = 0; i < 20; i++) valid.append("s").append(i).append("\theld-out\t")
        .append(i < 10 ? "identifier\tresolve" : "prose\treader lease").append("\tDoc").append(i).append(".java:3\n");
    Files.writeString(labels, valid); check(heldout(labels).size() == 20); checked++;
    for (String bad : List.of(valid.toString().replace("s1\theld-out", "s0\theld-out"),
        valid.toString().replace("Doc0.java:3", "Doc0.java:4"), valid.toString().replace("Doc0.java:3", "../escape:3"),
        valid.toString().replace("Doc0.java:3", "Doc0.java:3,Doc0.java:1"), valid.toString().replace("identifier\tresolve", "other\tresolve"),
        valid.toString().replace("s19\theld-out\tprose\treader lease\tDoc19.java:3\n", ""))) {
      Files.writeString(labels, bad); expectIOException(() -> heldout(labels)); checked++;
    }
    Path seal = temporary.resolve("once.properties"); createSeal(seal, Map.of("context", "fixed"));
    expectIOException(() -> createSeal(seal, Map.of("context", "fixed"))); checked++;
    check(Files.readString(seal).equals("context=fixed\n")); checked++;
    expectIOException(() -> resolve(temporary, "../escape")); checked++;
    expectIOException(() -> resolve(temporary, temporary.toString())); checked++;
    Path duplicate = temporary.resolve("duplicate.properties"); Files.writeString(duplicate, "a=1\na=2\n");
    expectIOException(() -> readProperties(duplicate, 100)); checked++;
    check(!adopt(new Metrics(.6, .9, 1), new Metrics(.7, .8, 1))); checked++;
    check(!adopt(new Metrics(.6, .9, 1), new Metrics(.6, .9, 1))); checked++;
    check(adopt(new Metrics(.6, .9, 1), new Metrics(.7, .9, 1))); checked++;
    check(!new Metrics(.7499, .9, 1).targetPass()); checked++;
    check(!new Metrics(.75, .8999, 1).targetPass()); checked++;
    check(new Metrics(.75, .9, 1).targetPass()); checked++;
    var metric = RelevanceMetrics.evaluate(List.of("unjudged", "actual"), Map.of("actual", 3, "missing", 1));
    check(Math.abs(metric.mrr10() - .5) < 1e-12 && Math.abs(metric.recall20() - .5) < 1e-12); checked++;
    writeMap(temporary.resolve("SELF-TEST-PASSED.properties"), Map.of("assertions", Integer.toString(checked), "realFixturesOpened", "false"));
    System.out.println("SELF-TEST PASS assertions=" + checked + "; synthetic files only");
  }
  @FunctionalInterface private interface IoAction { void run() throws Exception; }
  private static void expectIOException(IoAction action) throws Exception {
    try { action.run(); } catch (IOException expected) { return; } throw new AssertionError("expected refusal");
  }
  private static void check(boolean value) { if (!value) throw new AssertionError("operator self-test failed"); }
}
