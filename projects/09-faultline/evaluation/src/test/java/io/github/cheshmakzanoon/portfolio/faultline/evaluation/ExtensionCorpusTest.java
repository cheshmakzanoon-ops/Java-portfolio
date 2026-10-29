package io.github.cheshmakzanoon.portfolio.faultline.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.cheshmakzanoon.portfolio.faultline.cli.Faultline;
import io.github.cheshmakzanoon.portfolio.faultline.input.*;
import io.github.cheshmakzanoon.portfolio.faultline.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.faultline.report.*;
import io.github.cheshmakzanoon.portfolio.faultline.rules.RuleModels;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;
import org.junit.jupiter.api.*;

/**
 * The additive corpus for model 1.1.0. Labels and source hashes are fixed before evaluation, as for
 * the frozen v2 corpus, and are never adjusted to match an engine result.
 */
class ExtensionCorpusTest {
  static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();
  static final Path ROOT = PROJECT.resolve("corpus/extension-v1");
  static final Path CLASSES = Path.of("target/extension-corpus-classes");
  static final Set<String> NEW_RULES =
      Set.of("BG-CLASS-LOAD", "BG-URL-FETCH", "BG-XPATH-QUERY", "BG-LDAP-FILTER");
  static JsonNode document;
  static Analyzer.Result analysis;
  static List<Finding> findings;

  @BeforeAll
  static void compileAndScan() throws Exception {
    document = new ObjectMapper().readTree(ROOT.resolve("labels/extension-v1.json").toFile());
    deleteTree(CLASSES);
    Files.createDirectories(CLASSES);
    List<Path> sources;
    try (Stream<Path> walk = Files.walk(ROOT.resolve("src"))) {
      sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "JDK compiler required");
    try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
      StringWriter diagnostics = new StringWriter();
      boolean compiled =
          compiler
              .getTask(
                  diagnostics,
                  files,
                  null,
                  List.of("-proc:none", "--release", "25", "-d", CLASSES.toString()),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertTrue(compiled, "trusted extension corpus compilation: " + diagnostics);
    }
    analysis =
        new Analyzer()
            .scan(
                List.of(CLASSES), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
    findings = new FindingReporter().findings(analysis);
    assertFalse(analysis.analysis().partial(), analysis.analysis().diagnostics().toString());
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) return;
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  private static String sha(Path path) throws IOException {
    return BoundedJarReader.sha(Files.readAllBytes(path));
  }

  @Test
  void everyFrozenSourceAndSupportFileKeepsItsPinnedBytes() throws Exception {
    for (JsonNode label : document.path("labels"))
      assertEquals(
          label.path("sha256").asText(),
          sha(ROOT.resolve(label.path("file").asText())),
          "frozen source changed: " + label.path("id").asText());
    var support = document.path("supportFiles").fields();
    int checked = 0;
    while (support.hasNext()) {
      var entry = support.next();
      assertEquals(entry.getValue().asText(), sha(ROOT.resolve(entry.getKey())), entry.getKey());
      checked++;
    }
    assertEquals(
        5, checked, "SafeExtension plus javax/jakarta ServletRequest and HttpServletRequest");
  }

  @Test
  void labelsCoverEveryNewRuleWithMatchedPositivesAndNegatives() {
    Map<String, int[]> counts = new TreeMap<>();
    for (JsonNode label : document.path("labels")) {
      int[] c = counts.computeIfAbsent(label.path("rule").asText(), k -> new int[2]);
      c[label.path("positive").asBoolean() ? 0 : 1]++;
    }
    assertTrue(counts.keySet().containsAll(NEW_RULES), counts.toString());
    for (String rule : NEW_RULES) {
      assertTrue(counts.get(rule)[0] >= 5, rule + " positives " + counts.get(rule)[0]);
      assertTrue(counts.get(rule)[1] >= 4, rule + " negatives " + counts.get(rule)[1]);
    }
  }

  @Test
  void everyLabelIsDetectedExactlyWhenItIsPositive() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<String, int[]> confusion = new TreeMap<>();
    List<String> wrong = new ArrayList<>();
    for (JsonNode label : document.path("labels")) {
      String id = label.path("id").asText(), rule = label.path("rule").asText();
      boolean positive = label.path("positive").asBoolean();
      boolean found =
          findings.stream()
              .anyMatch(
                  f ->
                      f.rule().equals(rule)
                          && f.sink().method().startsWith("fixtures/" + id + "."));
      boolean otherRule =
          findings.stream()
              .anyMatch(
                  f ->
                      !f.rule().equals(rule)
                          && f.sink().method().startsWith("fixtures/" + id + "."));
      int[] c = confusion.computeIfAbsent(rule, k -> new int[4]); // TP, TN, FP, FN
      c[positive ? (found ? 0 : 3) : (found ? 2 : 1)]++;
      if (positive != found || otherRule) wrong.add(id + (otherRule ? " (other rule)" : ""));
      rows.add(
          Map.of(
              "id",
              id,
              "rule",
              rule,
              "expectedPositive",
              positive,
              "detected",
              found,
              "correct",
              positive == found && !otherRule));
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", 1);
    evidence.put("workload", "extension-corpus-v1");
    evidence.put("modelVersion", RuleModels.VERSION);
    evidence.put("rulesTpTnFpFn", confusion);
    evidence.put("cases", rows);
    evidence.put("workUnits", analysis.analysis().work());
    evidence.put("diagnostics", analysis.analysis().diagnostics());
    new SarifExporter().write(Path.of("target/extension-corpus-results.json"), evidence);
    assertEquals(List.of(), wrong, "misclassified fixtures; labels are never edited to fit");
  }

  @Test
  void aWebRequestSourceIsReportedAtItsOwnCallSite() {
    List<Finding> web =
        findings.stream().filter(f -> f.sink().method().startsWith("fixtures/WEB")).toList();
    assertEquals(4, web.size(), web.toString());
    assertTrue(
        web.stream().anyMatch(f -> f.sourceDescriptor().startsWith("jakarta/servlet/http/")));
    assertTrue(web.stream().anyMatch(f -> f.sourceDescriptor().startsWith("javax/servlet/")));
  }

  @Test
  void theTextFormatListsEveryFindingAndHonoursABaseline() throws Exception {
    Path text = Path.of("target/extension-corpus.txt"),
        sarif = Path.of("target/extension-corpus.sarif");
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    PrintStream stream = new PrintStream(log);
    assertEquals(
        1,
        Faultline.execute(
            new String[] {
              "scan", "--input", CLASSES.toString(), "--format", "text", "--out", text.toString()
            },
            stream,
            stream),
        log.toString());
    String report = Files.readString(text);
    int count = findings.size();
    assertTrue(
        report.startsWith(
            "Faultline 1.1.0 COMPLETE: " + count + " findings (" + count + " new, 0 unchanged)"),
        report.lines().findFirst().orElse(""));
    for (String rule : NEW_RULES) assertTrue(report.contains("[new] " + rule + "  "), rule);
    assertEquals(count, report.lines().filter(l -> l.startsWith("[new] ")).count());

    new SarifExporter().write(sarif, new SarifExporter().report(analysis, findings, Set.of()));
    assertEquals(
        0,
        Faultline.execute(
            new String[] {
              "scan",
              "--input",
              CLASSES.toString(),
              "--format",
              "text",
              "--out",
              text.toString(),
              "--baseline",
              sarif.toString()
            },
            stream,
            stream),
        log.toString());
    String unchanged = Files.readString(text);
    assertTrue(unchanged.contains("(0 new, " + count + " unchanged)"), unchanged);
    assertEquals(0, unchanged.lines().filter(l -> l.startsWith("[new] ")).count());
  }

  @Test
  void aTextFailureReportReplacesTheOutputAndKeepsTheFailureExitCode() throws Exception {
    Path text = Path.of("target/extension-corpus-failure.txt");
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    PrintStream stream = new PrintStream(log);
    int exit =
        Faultline.execute(
            new String[] {
              "scan",
              "--input",
              CLASSES.toString(),
              "--format",
              "text",
              "--out",
              text.toString(),
              "--rules",
              ROOT.resolve("labels/extension-v1.json").toString()
            },
            stream,
            stream);
    assertEquals(2, exit);
    assertTrue(Files.readString(text).startsWith("Faultline INVALID/FAILED: invalid rule model"));
  }

  @Test
  void theShippedModelDocumentFindsExactlyWhatTheBuiltInModelFinds() throws Exception {
    Path out = Path.of("target/extension-corpus-shipped.json");
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    int exit =
        Faultline.execute(
            new String[] {
              "scan",
              "--input",
              CLASSES.toString(),
              "--rules",
              PROJECT.resolve("rules/core.yaml").toString(),
              "--format",
              "json",
              "--out",
              out.toString()
            },
            new PrintStream(sink),
            new PrintStream(sink));
    assertEquals(1, exit, sink.toString());
    Set<String> viaDocument = new TreeSet<>();
    for (JsonNode f : new ObjectMapper().readTree(out.toFile()).path("findings"))
      viaDocument.add(f.path("fingerprint").asText());
    Set<String> builtIn =
        findings.stream().map(Finding::fingerprint).collect(Collectors.toCollection(TreeSet::new));
    assertEquals(builtIn, viaDocument);
    assertFalse(builtIn.isEmpty());
  }
}
