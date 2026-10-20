package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RelevanceEvaluator {
  private RelevanceEvaluator() {}

  public record Judged(
      String id, String split, String type, String text, Map<String, Integer> grades) {}

  public static List<Judged> judgments(Path fixtures) throws IOException {
    return EvaluationJudgments.read(fixtures, Set.of("development", "validation", "held-out"));
  }

  public static void verifyFreeze(Path fixtures) throws IOException {
    Path manifest = fixtures.resolve("FREEZE.sha256");
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > 4096)
      throw new IOException("fixture manifest regular file/quota");
    Set<String> seen = new HashSet<>();
    for (String line : Files.readAllLines(manifest)) {
      String[] p = line.split("  ", -1);
      if (p.length != 2
          || !p[0].matches("[a-f0-9]{64}")
          || !Set.of("corpus.tsv", "judgments.tsv", "corpus.jsonl", "judgments.jsonl")
              .contains(p[1])
          || !seen.add(p[1])) throw new IOException("invalid fixture manifest");
      Path file = fixtures.resolve(p[1]);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > 33_554_432
          || !Digests.sha256(file).equals(p[0])) throw new IOException("frozen corpus changed");
    }
    if (!seen.containsAll(Set.of("corpus.tsv", "judgments.tsv")))
      throw new IOException("incomplete fixture manifest");
    if (!Files.isRegularFile(fixtures.resolve("REVIEW-APPROVED.md"), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("independent pre-measurement label review required");
  }

  public static String modelFingerprint() throws IOException {
    return modelFingerprint(RankingConfiguration.system().identity(RankingProfile.current()));
  }

  public static String modelFingerprint(String rankingIdentity) throws IOException {
    var domain = RelevanceEvaluator.class.getProtectionDomain();
    if (domain == null
        || domain.getCodeSource() == null
        || domain.getCodeSource().getLocation() == null)
      throw new IOException("missing evaluation artifact origin");
    Path origin = RankingCodeIdentity.artifactPath(domain.getCodeSource().getLocation());
    // Capture every inference and evaluation class, including extracted/future helpers. A phase
    // identity must not depend on whether its caller passed a configuration name or full identity.
    return Digests.sha256(
        "evaluation-code-v3\ncore="
            + RankingCodeIdentity.SHA256
            + "\nevaluation="
            + RankingCodeIdentity.fingerprint(origin)
            + "\nranking="
            + rankingIdentity);
  }

  public static void run(Path data, Path fixtures, Path output, String phase) throws IOException {
    verifyFreeze(fixtures);
    if (!Set.of("development", "final").contains(phase))
      throw new IllegalArgumentException("evaluation phase");
    AuthorizationFilter auth = new AuthorizationFilter();
    var a = auth.update("evaluator", "evaluation", Set.of("corpus"), true);
    List<Judged> queries = EvaluationJudgments.read(fixtures, Set.of("development", "validation"));
    try (SearchEngine engine = new SearchEngine(data, auth)) {
      String capturedModel = modelFingerprint(engine.rankingIdentity());
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (String line : Files.readAllLines(fixtures.resolve("corpus.tsv"))) {
        String[] p = line.split("\t", -1);
        byte[] content = Base64.getDecoder().decode(p[2]);
        List<String> symbols = Arrays.stream(p[1].split(" ")).limit(256).toList();
        entries.add(
            new SnapshotManifest.Entry(
                p[0],
                engine.contentStore().put(content),
                p[0].endsWith(".java") ? "java" : "text",
                "source",
                symbols,
                "",
                ""));
      }
      engine.ingest(
          a,
          "evaluation-"
              + Digests.sha256(
                  Digests.sha256(Files.readAllBytes(fixtures.resolve("corpus.tsv")))
                      + "\n"
                      + capturedModel),
          new SnapshotManifest("evaluation", "corpus", "frozen-v2", true, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      Files.createDirectories(output);
      Map<String, Double> validationScores = new HashMap<>();
      Map<String, Double> validationIdentifier = new HashMap<>();
      StringBuilder rows =
          new StringBuilder("phase,mode,query,split,type,ndcg10,mrr10,recall20,returnedPaths\n");
      List<String> summary = new ArrayList<>();
      for (var mode : QueryPlanner.Mode.legacyValues()) {
        for (String split : List.of("development", "validation")) {
          double ndcg = 0, mrr = 0, recall = 0;
          int n = 0, identifiers = 0;
          double identifierMrr = 0;
          for (Judged q : queries)
            if (q.split().equals(split)) {
              var response =
                  engine.search(
                      a.principal(), new SearchEngine.Request(q.text(), 20, null, Map.of()), mode);
              List<String> paths = response.results().stream().map(SearchEngine.Hit::path).toList();
              var m = RelevanceMetrics.evaluate(paths, q.grades());
              ndcg += m.ndcg10();
              mrr += m.mrr10();
              if (q.type().equals("identifier")) {
                identifierMrr += m.mrr10();
                identifiers++;
              }
              recall += m.recall20();
              n++;
              rows.append(phase)
                  .append(',')
                  .append(mode)
                  .append(',')
                  .append(q.id())
                  .append(',')
                  .append(split)
                  .append(',')
                  .append(q.type())
                  .append(',')
                  .append(m.ndcg10())
                  .append(',')
                  .append(m.mrr10())
                  .append(',')
                  .append(m.recall20())
                  .append(',')
                  .append(String.join("|", paths))
                  .append('\n');
            }
          summary.add(
              mode
                  + " "
                  + split
                  + " nDCG10="
                  + ndcg / n
                  + " MRR10="
                  + mrr / n
                  + " Recall20="
                  + recall / n);
          summary.add(mode + " " + split + " identifierMRR10=" + identifierMrr / identifiers);
          if (split.equals("validation")) {
            validationScores.put(mode.name(), ndcg / n);
            validationIdentifier.put(mode.name(), identifierMrr / identifiers);
          }
        }
      }
      String selected =
          QueryPlanner.Mode.legacyValues().stream()
              .filter(
                  mode ->
                      validationIdentifier.get(mode.name()) >= validationIdentifier.get("BASELINE")
                          && validationScores.get(mode.name()) >= validationScores.get("BASELINE"))
              .max(
                  Comparator.<QueryPlanner.Mode>comparingDouble(
                          mode ->
                              Math.min(
                                  validationScores.get(mode.name()) / .75,
                                  validationIdentifier.get(mode.name()) / .90))
                      .thenComparingInt(mode -> mode == QueryPlanner.Mode.BASELINE ? 1 : 0))
              .orElseThrow()
              .name();
      Map<QueryPlanner.Mode, RelevanceMetrics.Values> heldOut =
          new EnumMap<>(QueryPlanner.Mode.class);
      Path selection = output.resolve("selection.txt");
      String decision =
          "Pre-held-out selection from development/validation only: "
              + selected
              + "\nRanking profile: "
              + RankingProfile.current().name()
              + "\nObjective: maximize min(validation nDCG10/.75, identifierMRR10/.90), no baseline regression\nModel SHA256: "
              + capturedModel
              + "\nFrozen fixture SHA256: "
              + Digests.sha256(Files.readAllBytes(fixtures.resolve("FREEZE.sha256")))
              + "\n";
      if (phase.equals("development")) {
        Files.writeString(selection, decision);
        summary.add("Held-out remains unopened.");
      } else {
        if (!Files.exists(selection) || !Files.readString(selection).equals(decision))
          throw new IOException("run development phase and freeze selection before held-out");
        EvaluationSeal.claim(fixtures, output, decision);
        List<Judged> heldOutQueries = EvaluationJudgments.read(fixtures, Set.of("held-out"));
        for (var mode : QueryPlanner.Mode.legacyValues()) {
          double ndcg = 0, identifierMrr = 0, recall = 0;
          int n = 0, ids = 0;
          for (Judged q : heldOutQueries)
            if (q.split().equals("held-out")) {
              var response =
                  engine.search(
                      a.principal(), new SearchEngine.Request(q.text(), 20, null, Map.of()), mode);
              List<String> paths = response.results().stream().map(SearchEngine.Hit::path).toList();
              var m = RelevanceMetrics.evaluate(paths, q.grades());
              ndcg += m.ndcg10();
              recall += m.recall20();
              n++;
              if (q.type().equals("identifier")) {
                identifierMrr += m.mrr10();
                ids++;
              }
              rows.append(phase)
                  .append(',')
                  .append(mode)
                  .append(',')
                  .append(q.id())
                  .append(',')
                  .append(q.split())
                  .append(',')
                  .append(q.type())
                  .append(',')
                  .append(m.ndcg10())
                  .append(',')
                  .append(m.mrr10())
                  .append(',')
                  .append(m.recall20())
                  .append(',')
                  .append(String.join("|", paths))
                  .append('\n');
            }
          heldOut.put(mode, new RelevanceMetrics.Values(ndcg / n, identifierMrr / ids, recall / n));
          summary.add(
              mode
                  + " held-out nDCG10="
                  + ndcg / n
                  + " identifierMRR10="
                  + identifierMrr / ids
                  + " Recall20="
                  + recall / n
                  + " targetPass="
                  + (ndcg / n >= 0.75 && identifierMrr / ids >= 0.9));
        }
      }
      if (phase.equals("final")) {
        var baseline = heldOut.get(QueryPlanner.Mode.BASELINE);
        var candidate = heldOut.get(QueryPlanner.Mode.valueOf(selected));
        boolean improved =
            !selected.equals("BASELINE")
                && candidate.ndcg10() > baseline.ndcg10()
                && candidate.mrr10() >= baseline.mrr10();
        summary.add(
            "Retained deployment mode="
                + (improved ? selected : "BASELINE")
                + "; original baseline retained unless held-out nDCG improves without identifier-MRR regression.");
      }
      Files.writeString(output.resolve(phase + "-per-query.csv"), rows);
      Files.writeString(output.resolve(phase + "-summary.txt"), String.join("\n", summary) + "\n");
      System.out.println(String.join("\n", summary));
    }
  }
}
