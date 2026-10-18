package io.github.cheshmakzanoon.portfolio.byteguard.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.cheshmakzanoon.portfolio.byteguard.bytecode.*;
import io.github.cheshmakzanoon.portfolio.byteguard.cfg.*;
import io.github.cheshmakzanoon.portfolio.byteguard.cli.*;
import io.github.cheshmakzanoon.portfolio.byteguard.dataflow.*;
import io.github.cheshmakzanoon.portfolio.byteguard.input.*;
import io.github.cheshmakzanoon.portfolio.byteguard.interprocedural.*;
import io.github.cheshmakzanoon.portfolio.byteguard.report.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;

class CorpusTest {
  static final Path PROJECT = Path.of("..").toAbsolutePath().normalize();
  static final Path CLASSES = Path.of("target/corpus-classes");
  static Analyzer.Result analysis;
  static List<Finding> findings;
  static JsonNode labels;

  @BeforeAll
  static void compileAndScan() throws Exception {
    Files.createDirectories(CLASSES);
    List<Path> debug = new ArrayList<>(), nodebug = new ArrayList<>();
    try (var paths = Files.walk(PROJECT.resolve("corpus/src"))) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .forEach(
              p -> {
                if (p.getFileName().toString().contains("19")) nodebug.add(p);
                else debug.add(p);
              });
    }
    compile(debug, true);
    compile(nodebug, false);
    Files.delete(CLASSES.resolve("fixtures/Missing.class"));
    labels =
        new ObjectMapper()
            .readTree(PROJECT.resolve("corpus/labels/frozen-v2.json").toFile())
            .path("labels");
    analysis =
        new Analyzer()
            .scan(
                List.of(CLASSES), List.of(), 25, InputLimits.defaults(), AnalysisBudget.defaults());
    findings = new FindingReporter().findings(analysis);
    assertFalse(analysis.analysis().partial(), analysis.analysis().diagnostics().toString());
  }

  static void compile(List<Path> sources, boolean debug) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "JDK compiler required");
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      boolean success =
          compiler
              .getTask(
                  null,
                  fm,
                  null,
                  List.of(
                      "-proc:none",
                      debug ? "-g" : "-g:none",
                      "--release",
                      "25",
                      "-classpath",
                      CLASSES.toString(),
                      "-d",
                      CLASSES.toString()),
                  null,
                  fm.getJavaFileObjectsFromPaths(sources))
              .call();
      assertTrue(success, "trusted corpus compilation");
    }
  }

  @Test
  void frozenLabelsMatchEveryResultAndThresholds() throws Exception {
    List<Map<String, Object>> results = new ArrayList<>();
    Map<String, int[]> confusion = new TreeMap<>();
    int tp = 0, tn = 0;
    for (JsonNode label : labels) {
      String id = label.path("id").asText();
      Path source = PROJECT.resolve("corpus").resolve(label.path("file").asText());
      assertEquals(
          label.path("sha256").asText(),
          BoundedJarReader.sha(Files.readAllBytes(source)),
          "frozen source changed: " + id);
      boolean positive = label.path("positive").asBoolean();
      boolean found =
          findings.stream()
              .anyMatch(
                  f ->
                      f.rule().equals(label.path("rule").asText())
                          && f.sink().method().startsWith("fixtures/" + id + "."));
      int[] counts = confusion.computeIfAbsent(label.path("rule").asText(), k -> new int[4]);
      counts[positive ? (found ? 0 : 3) : (found ? 2 : 1)]++;
      if (positive && found) tp++;
      if (!positive && !found) tn++;
      results.add(
          Map.of(
              "id",
              id,
              "expectedPositive",
              positive,
              "detected",
              found,
              "correct",
              positive == found));
    }
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", 1);
    evidence.put("workload", "frozen-corpus-v2");
    evidence.put("positiveDetected", tp);
    evidence.put("negativeCorrect", tn);
    evidence.put("rulesTpTnFpFn", confusion);
    evidence.put("cases", results);
    evidence.put("workUnits", analysis.analysis().work());
    evidence.put("unknownDiagnostics", analysis.analysis().diagnostics());
    new SarifExporter().write(Path.of("target/corpus-results.json"), evidence);
    assertEquals(
        60,
        tp,
        "missed positives: "
            + results.stream()
                .filter(x -> !(boolean) x.get("correct") && (boolean) x.get("expectedPositive"))
                .toList());
    assertTrue(
        tn >= 57,
        "false positives: "
            + results.stream()
                .filter(x -> !(boolean) x.get("correct") && !(boolean) x.get("expectedPositive"))
                .toList());
  }

  @Test
  void allReachableFramesAreTypedAndCfgEdgesResolve() {
    for (var c : analysis.classes().values())
      for (MethodIr m : c.methods().values()) {
        var g = new ControlFlowBuilder().build(m);
        var r = analysis.analysis().methods().get(m.id);
        assertNotNull(r);
        assertEquals(m.instructions.size(), r.frames().size());
        for (int i = 0; i < r.frames().size(); i++) {
          FrameState f = r.frames().get(i);
          if (f == null) continue;
          assertTrue(f.slots() <= m.node.maxStack);
          assertTrue(f.stack.stream().noneMatch(v -> v.kind() == AbstractValue.Kind.TOP));
          for (int target : g.normal().get(i))
            assertTrue(target >= 0 && target < m.instructions.size());
          for (int target : g.exceptional().get(i))
            assertEquals(AbstractValue.Kind.REF, r.frames().get(target).stack.getFirst().kind());
        }
      }
  }

  @Test
  void scheduleIndependentFixedPoint() {
    var reverse =
        new SummaryWorklist().analyze(analysis.classes(), AnalysisBudget.defaults(), true);
    assertFalse(reverse.partial());
    for (String id : analysis.analysis().summaries().keySet()) {
      var a = analysis.analysis().summaries().get(id);
      var b = reverse.summaries().get(id);
      assertEquals(a.returns(), b.returns(), id);
      assertEquals(a.flows().keySet(), b.flows().keySet(), id);
      for (String k : a.flows().keySet())
        assertEquals(a.flows().get(k).taint(), b.flows().get(k).taint(), id);
    }
  }

  @Test
  void pinnedSarifSchemaValidatesActualResults() throws Exception {
    var factory =
        com.networknt.schema.JsonSchemaFactory.getInstance(
            com.networknt.schema.SpecVersion.VersionFlag.V7);
    var schema = factory.getSchema(PROJECT.resolve("contracts/sarif-schema-2.1.0.json").toUri());
    var report = new SarifExporter().report(analysis, findings, Set.of());
    var errors = schema.validate(report);
    assertTrue(errors.isEmpty(), errors.toString());
    assertEquals(1, report.path("runs").size());
    assertEquals(findings.size(), report.path("runs").get(0).path("results").size());
  }

  @Test
  void baselineAndWitnessesStable() throws Exception {
    SarifExporter e = new SarifExporter();
    Path report = Path.of("target/corpus.sarif");
    e.write(report, e.report(analysis, findings, Set.of()));
    Set<String> baseline = e.baseline(report);
    assertEquals(findings.size(), baseline.size());
    for (Finding f : findings) {
      assertTrue(f.fingerprint().matches("[0-9a-f]{64}"));
      assertFalse(f.sinkLocation().uri().startsWith("/"));
      assertTrue(f.sink().ordinal() >= 0);
      if (f.sink().method().contains("19")) assertEquals(-1, f.sinkLocation().line());
      else assertTrue(f.sinkLocation().line() > 0);
      assertTrue(
          analysis.classes().values().stream()
              .flatMap(c -> c.methods().values().stream())
              .anyMatch(
                  m ->
                      m.id.equals(f.sink().method())
                          && m.instructions.size() > f.sink().ordinal()));
    }
    assertTrue(findings.stream().anyMatch(f -> !f.callPath().isEmpty()));
  }

  @Test
  void provenanceBudgetDoesNotEraseTaint() {
    var shortPath =
        new SummaryWorklist()
            .analyze(analysis.classes(), new AnalysisBudget(50_000_000, 120_000, 32, 0), false);
    var shorter = new Analyzer.Result(analysis.manifest(), analysis.classes(), shortPath);
    assertFalse(shortPath.partial());
    assertEquals(
        findings.stream().map(Finding::fingerprint).collect(java.util.stream.Collectors.toSet()),
        new FindingReporter()
            .findings(shorter).stream()
                .map(Finding::fingerprint)
                .collect(java.util.stream.Collectors.toSet()));
    assertTrue(
        new FindingReporter().findings(shorter).stream().anyMatch(Finding::provenanceTruncated));
  }

  @Test
  void cliFourExitsAndSentinel() throws Exception {
    Path dir = Path.of("target/cli-input");
    Files.createDirectories(dir);
    Path source = dir.resolve("Sentinel.java");
    Files.writeString(
        source,
        "public class Sentinel { static { try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty(\"byteguard.sentinel\")), \"EXECUTED\"); } catch (Exception e) { throw new RuntimeException(e); } } public static String run(){ return System.getenv(\"X\"); } }");
    Path marker = Path.of("target/SENTINEL-MUST-NOT-EXIST").toAbsolutePath();
    System.setProperty("byteguard.sentinel", marker.toString());
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertEquals(
        0, compiler.run(null, null, null, "-proc:none", "-d", dir.toString(), source.toString()));
    ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    PrintStream log = new PrintStream(terminal);
    assertEquals(
        0,
        ByteGuard.execute(
            new String[] {
              "scan",
              "--input",
              dir.resolve("Sentinel.class").toString(),
              "--out",
              "target/clean.sarif"
            },
            log,
            log));
    assertFalse(Files.exists(marker));
    assertEquals(
        1,
        ByteGuard.execute(
            new String[] {"scan", "--input", CLASSES.toString(), "--out", "target/positive.sarif"},
            log,
            log));
    assertEquals(
        0,
        ByteGuard.execute(
            new String[] {
              "scan",
              "--input",
              CLASSES.toString(),
              "--baseline",
              "target/positive.sarif",
              "--out",
              "target/baseline.sarif"
            },
            log,
            log));
    assertEquals(
        3,
        ByteGuard.execute(
            new String[] {
              "scan",
              "--input",
              CLASSES.toString(),
              "--work-budget",
              "1",
              "--out",
              "target/partial.sarif"
            },
            log,
            log));
    assertTrue(
        new ObjectMapper()
                .readTree(Path.of("target/partial.sarif").toFile())
                .path("runs")
                .get(0)
                .path("properties")
                .path("unfinishedFrontier")
                .size()
            > 0);
    assertEquals(
        2,
        ByteGuard.execute(
            new String[] {"scan", "--input", "missing.jar", "--out", "target/invalid.json"},
            log,
            log));
    assertTrue(terminal.toString().contains("PARTIAL"));
    assertFalse(Files.exists(marker));
  }

  @Test
  void explanationContainsActualFramesCfgAndContexts() throws Exception {
    Path directory = Path.of("target/explanation");
    new AnalysisExplainer()
        .write(
            analysis,
            directory,
            "fixtures/SQL01Positive.run(Ljava/sql/Statement;Ljava/sql/Connection;)V");
    JsonNode report = new ObjectMapper().readTree(directory.resolve("analysis.json").toFile());
    assertEquals(1, report.path("methods").size());
    assertFalse(report.path("callSiteContexts").isEmpty());
    JsonNode method = report.path("methods").get(0);
    assertTrue(method.path("instructions").get(0).has("locals"));
    assertTrue(
        Files.readString(directory.resolve(method.path("cfg").asText())).startsWith("digraph cfg"));
  }

  @Test
  void partialExplanationDoesNotPretendToHaveFrames() throws Exception {
    var partial =
        new SummaryWorklist()
            .analyze(analysis.classes(), new AnalysisBudget(1, 120_000, 32, 5), false);
    Path dir = Path.of("target/partial-explanation");
    new AnalysisExplainer()
        .write(
            new Analyzer.Result(analysis.manifest(), analysis.classes(), partial),
            dir,
            "fixtures/SQL01Positive.run(Ljava/sql/Statement;Ljava/sql/Connection;)V");
    JsonNode report = new ObjectMapper().readTree(dir.resolve("analysis.json").toFile());
    assertTrue(report.path("partial").asBoolean());
    assertEquals("unfinished", report.path("methods").get(0).path("coverage").asText());
  }
}
