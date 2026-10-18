package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class RelevanceEvaluator {
  private RelevanceEvaluator() {}

  public record Judged(
      String id, String split, String type, String text, Map<String, Integer> grades) {}

  public static List<Judged> judgments(Path fixtures) throws IOException {
    List<Judged> out = new ArrayList<>();
    for (String line : Files.readAllLines(fixtures.resolve("judgments.tsv"))) {
      String[] p = line.split("\t");
      Map<String, Integer> grades = new TreeMap<>();
      for (String item : p[4].split(",")) {
        int at = item.lastIndexOf(':');
        grades.put(item.substring(0, at), Integer.parseInt(item.substring(at + 1)));
      }
      out.add(new Judged(p[0], p[1], p[2], p[3], Map.copyOf(grades)));
    }
    if (out.size() != 80
        || out.stream().filter(q -> q.split().equals("development")).count() != 40
        || out.stream().filter(q -> q.split().equals("validation")).count() != 20
        || out.stream().filter(q -> q.split().equals("held-out")).count() != 20)
      throw new IOException("evaluation split mismatch");
    return out;
  }

  public static void verifyFreeze(Path fixtures) throws IOException {
    for (String line : Files.readAllLines(fixtures.resolve("FREEZE.sha256"))) {
      String[] p = line.split("  ");
      if (p.length != 2 || !Digests.sha256(Files.readAllBytes(fixtures.resolve(p[1]))).equals(p[0]))
        throw new IOException("frozen corpus changed");
    }
    if (!Files.exists(fixtures.resolve("REVIEW-APPROVED.md")))
      throw new IOException("independent pre-measurement label review required");
  }

  public static String modelFingerprint() throws IOException {
    StringBuilder model = new StringBuilder();
    for (String type :
        List.of(
            "RelevanceEvaluator",
            "RankingProfile",
            "GenerationLease",
            "GenerationBuilder",
            "SourceDocument",
            "CodeFeatures",
            "CodeFeatureQuery",
            "SourceProseAnalyzer",
            "SearchEngine",
            "AuthorizationFilter",
            "CodeIdentifierAnalyzer$Part",
            "CandidateRetriever$Candidate",
            "DeclarationScanner",
            "DocumentMapper",
            "SearchConcepts",
            "SearchConcepts$Group",
            "QueryPlanner$ClauseBudget",
            "QueryPlanner",
            "QueryPlanner$Mode",
            "QueryAst$Term",
            "QueryAst$BooleanNode",
            "QueryAst$Negation",
            "QueryAst$Parser$Token",
            "Ranker",
            "CandidateRetriever",
            "CodeIdentifierAnalyzer",
            "CodeIdentifierAnalyzer$1",
            "QueryAst",
            "QueryAst$Parser",
            "RelevanceMetrics")) {
      try (var resource = RelevanceEvaluator.class.getResourceAsStream(type + ".class")) {
        if (resource == null) throw new IOException("missing model bytecode");
        model.append(type).append(':').append(Digests.sha256(resource.readAllBytes())).append('\n');
      }
    }
    // The supported shaded CLI binds dependencies/indexing helpers as well as original class bytes.
    try {
      Path artifact =
          Path.of(
              RelevanceEvaluator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isRegularFile(artifact)) model.append("artifact:").append(Digests.sha256(artifact));
    } catch (java.net.URISyntaxException invalid) {
      throw new IOException("invalid model artifact location", invalid);
    }
    model.append("profile:").append(RankingProfile.current().name());
    return Digests.sha256(model.toString());
  }

  public static void run(Path data, Path fixtures, Path output, String phase) throws IOException {
    verifyFreeze(fixtures);
    if (!Set.of("development", "final").contains(phase))
      throw new IllegalArgumentException("evaluation phase");
    AuthorizationFilter auth = new AuthorizationFilter();
    var a = auth.update("evaluator", "evaluation", Set.of("corpus"), true);
    List<Judged> queries = judgments(fixtures);
    try (SearchEngine engine = new SearchEngine(data, auth)) {
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
                      + modelFingerprint()),
          new SnapshotManifest("evaluation", "corpus", "frozen-v2", true, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      Files.createDirectories(output);
      Map<String, Double> validationScores = new HashMap<>();
      Map<String, Double> validationIdentifier = new HashMap<>();
      StringBuilder rows =
          new StringBuilder("phase,mode,query,split,type,ndcg10,mrr10,recall20,returnedPaths\n");
      List<String> summary = new ArrayList<>();
      for (var mode : QueryPlanner.Mode.values()) {
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
          Arrays.stream(QueryPlanner.Mode.values())
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
              + modelFingerprint()
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
        for (var mode : QueryPlanner.Mode.values()) {
          double ndcg = 0, identifierMrr = 0, recall = 0;
          int n = 0, ids = 0;
          for (Judged q : queries)
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
