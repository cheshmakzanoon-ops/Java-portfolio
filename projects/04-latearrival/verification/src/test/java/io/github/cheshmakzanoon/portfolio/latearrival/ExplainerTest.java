package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Report;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Row;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Step;
import io.github.cheshmakzanoon.portfolio.latearrival.Explainer.Verdict;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplainerTest {
  private static final long T0 = 1_767_225_600_000L; // 2026-01-01T00:00:00Z
  private static final Path EXAMPLE = Path.of("..", "examples", "late-data.jsonl");
  @TempDir Path temp;

  private static String event(String id, String sensor, long seconds, long milli) {
    return "{\"schemaVersion\":1,\"runId\":\"demo\",\"eventId\":\""
        + id
        + "\",\"tenantId\":\"acme\",\"sensorId\":\""
        + sensor
        + "\",\"eventTimeEpochMillis\":"
        + (T0 + seconds * 1000)
        + ",\"valueMilliUnits\":"
        + milli
        + "}";
  }

  private static String progress(String id, String sensor, long seconds) {
    return event(id, sensor, seconds, 0).replace("}", ",\"recordType\":\"PROGRESS\"}");
  }

  private static List<Verdict> verdicts(Report report) {
    return report.steps().stream().map(Step::verdict).toList();
  }

  private static Row row(Report report, String type, long startSeconds) {
    return report.windows().stream()
        .filter(w -> w.type().equals(type) && w.start() == T0 + startSeconds * 1000)
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(type + " " + startSeconds + " in " + report.windows()));
  }

  @Test
  void theExampleFileShowsEveryKindOfOutcome() throws Exception {
    Report report = Explainer.replay(Files.readAllLines(EXAMPLE), null, 1, null);
    assertEquals("demo", report.runId(), "the first parseable run is adopted");
    assertEquals(
        List.of(
            Verdict.ACCEPTED,
            Verdict.ACCEPTED,
            Verdict.DUPLICATE,
            Verdict.ACCEPTED,
            Verdict.ACCEPTED,
            Verdict.ACCEPTED,
            Verdict.ACCEPTED,
            Verdict.REJECTED,
            Verdict.REJECTED,
            Verdict.REJECTED,
            Verdict.ACCEPTED),
        verdicts(report));
    List<Step> steps = report.steps();
    assertEquals(Long.MIN_VALUE, steps.get(0).watermarkBefore());
    assertEquals(
        T0 + 5_000,
        steps.get(0).watermarkAfter(),
        "maximum event time minus the 5 s disorder bound");
    assertEquals(
        T0 + 5_000,
        steps.get(1).watermarkAfter(),
        "an out-of-order event inside the bound moves nothing");
    assertEquals("CONFLICTING_EVENT_ID", steps.get(7).detail());
    assertEquals("MALFORMED_JSON", steps.get(8).detail());
    assertEquals("INVALID_RECORD", steps.get(9).detail());
    assertEquals(9, steps.get(8).line());
    assertEquals(1, report.duplicates());
    // e3 (t=70 s) lifts the watermark past the first window, which is published but still
    // revisable.
    assertEquals(
        List.of(
                "acme:boiler:SLIDING:" + (T0 - 240_000) + " rev 1",
                "acme:boiler:TUMBLING:" + T0 + " rev 1")
            .stream()
            .sorted()
            .toList(),
        steps.get(3).published().stream().sorted().toList());
    // e4 (t=95 s) makes the watermark 90 s = window end + 30 s lateness: the window is final.
    assertTrue(steps.get(4).published().contains("acme:boiler:TUMBLING:" + T0 + " rev 2 FINAL"));
    // e5 arrives for 20 s after that: late for the finalized windows, still counted in the others.
    assertEquals(
        List.of("acme:boiler:TUMBLING:" + T0, "acme:boiler:SLIDING:" + (T0 - 240_000)).stream()
            .sorted()
            .toList(),
        steps.get(5).lateForFinalized().stream().sorted().toList());
    assertEquals("joined 4 of 6 windows", steps.get(5).detail());
    Row tumbling = row(report, "TUMBLING", 0);
    assertEquals("FINAL", tumbling.status());
    assertEquals(2, tumbling.count(), "late e5/e6 are not in the finalized window");
    assertEquals(7_000, tumbling.sum());
    assertEquals(2_000, tumbling.minimum());
    assertEquals(5_000, tumbling.maximum());
    assertNull(tumbling.finalizesAtWatermark());
    assertEquals(T0 + 295_000, report.watermark(), "the progress record at 300 s minus 5 s");
    assertEquals(
        "OPEN", row(report, "SLIDING", 0).status(), "[0 s, 300 s) ends after the 295 s watermark");
    assertEquals(T0 + 300_000, row(report, "SLIDING", 0).publishesAtWatermark());
    assertEquals("FINAL", row(report, "SLIDING", -60).status());
  }

  @Test
  void aWindowIsProvisionalUntilTheWatermarkPassesItsEndPlusLateness() {
    Report report =
        Explainer.replay(List.of(event("a", "s", 10, 1), event("b", "s", 70, 2)), null, 1, null);
    Row first = row(report, "TUMBLING", 0);
    assertEquals("PROVISIONAL", first.status());
    assertEquals(1, first.revision());
    assertEquals(T0 + 90_000, first.finalizesAtWatermark(), "end 60 s + 30 s");
    assertNull(first.publishesAtWatermark(), "already published");
    Row open = row(report, "TUMBLING", 60);
    assertEquals("OPEN", open.status());
    assertEquals(0, open.revision());
    assertEquals(1, open.count());
    assertEquals(T0 + 120_000, open.publishesAtWatermark());
    assertEquals(T0 + 150_000, open.finalizesAtWatermark());
  }

  @Test
  void aLateEventRevisesAnUnfinalizedWindowAndIsListedAsPublished() {
    Report report =
        Explainer.replay(
            List.of(event("a", "s", 10, 1), event("b", "s", 70, 2), event("c", "s", 30, 100)),
            null,
            1,
            null);
    Step late = report.steps().get(2);
    assertEquals(Verdict.ACCEPTED, late.verdict());
    assertTrue(late.lateForFinalized().isEmpty());
    assertTrue(
        late.published().contains("acme:s:TUMBLING:" + T0 + " rev 2"), late.published().toString());
    Row row = row(report, "TUMBLING", 0);
    assertEquals(2, row.count());
    assertEquals(101, row.sum());
    assertEquals(2, row.revision());
  }

  @Test
  void aProgressRecordAdvancesTheWatermarkWithoutAddingAValue() {
    Report report =
        Explainer.replay(List.of(event("a", "s", 10, 5), progress("p", "s", 400)), null, 1, null);
    Step step = report.steps().get(1);
    assertEquals(Verdict.ACCEPTED, step.verdict());
    assertTrue(step.detail().startsWith("progress record"));
    assertEquals(T0 + 395_000, report.watermark());
    assertEquals("FINAL", row(report, "TUMBLING", 0).status());
    assertEquals(1, row(report, "TUMBLING", 0).count());
  }

  @Test
  void blankLinesAreSkippedAndLineNumbersStayTrue() {
    Report report =
        Explainer.replay(List.of("", event("a", "s", 1, 1), "   ", "oops", ""), null, 1, null);
    assertEquals(List.of(2, 4), report.steps().stream().map(Step::line).toList());
    assertEquals(List.of(Verdict.ACCEPTED, Verdict.REJECTED), verdicts(report));
    assertNull(report.steps().get(1).eventId());
    assertNull(report.steps().get(1).sensor());
    assertNull(report.steps().get(1).eventTimeMillis());
  }

  @Test
  void aSuppliedRunAndAPastClockAreEnforcedByTheEngine() {
    List<String> lines = List.of(event("a", "s", 10, 1), event("b", "s", 10 + 3600, 1));
    Report other = Explainer.replay(lines, "elsewhere", 1, null);
    assertEquals(List.of(Verdict.REJECTED, Verdict.REJECTED), verdicts(other));
    assertEquals("INVALID_RECORD", other.steps().get(0).detail());
    // With the clock at the first event, the second is an hour in the future: beyond the 5 min
    // skew.
    Report skewed = Explainer.replay(lines, null, 1, T0 + 10_000);
    assertEquals(List.of(Verdict.ACCEPTED, Verdict.REJECTED), verdicts(skewed));
    assertEquals("INVALID_RECORD", skewed.steps().get(1).detail());
    assertEquals(T0 + 10_000, skewed.nowMillis());
  }

  @Test
  void anEmptyInputIsAValidReplay() {
    Report report = Explainer.replay(List.of(), null, 1, null);
    assertEquals("replay", report.runId());
    assertTrue(report.steps().isEmpty());
    assertTrue(report.windows().isEmpty());
    assertEquals(Long.MIN_VALUE, report.watermark());
  }

  @Test
  void recordsAreRoutedByTheCodecsMurmur2PartitionAndAnUnseenPartitionHoldsTheWatermark() {
    // Find two sensors that route to different partitions of 2.
    String a = null, b = null;
    for (int i = 0; i < 50 && b == null; i++) {
      String candidate = "sensor-" + i;
      int partition = EventCodec.partition("acme:" + candidate, 2);
      if (a == null) a = candidate;
      else if (partition != EventCodec.partition("acme:" + a, 2)) b = candidate;
    }
    assertNotNull(b);
    Report one =
        Explainer.replay(List.of(event("a", a, 10, 1), event("a2", a, 400, 1)), null, 2, null);
    assertEquals(EventCodec.partition("acme:" + a, 2), one.steps().get(0).partition());
    assertEquals(0, one.steps().get(0).offset());
    assertEquals(1, one.steps().get(1).offset());
    assertEquals(Long.MIN_VALUE, one.watermark(), "the other partition has seen nothing");
    assertTrue(one.steps().stream().allMatch(s -> s.published().isEmpty()));
    assertEquals(
        Long.MIN_VALUE, one.maxEventTimeByPartition().get(1 - one.steps().get(0).partition()));

    Report both =
        Explainer.replay(List.of(event("a", a, 10, 1), event("b", b, 400, 1)), null, 2, null);
    assertEquals(T0 + 5_000, both.watermark(), "the slower partition decides: 10 s - 5 s");
    assertNotEquals(both.steps().get(0).partition(), both.steps().get(1).partition());
  }

  @Test
  void theReplayAgreesWithTheIndependentBatchOracleOnRandomStreams() {
    for (long seed = 1; seed <= 25; seed++) {
      Random random = new Random(seed);
      List<String> lines = new ArrayList<>();
      List<Engine.Event> events = new ArrayList<>();
      for (int i = 0; i < 250; i++) {
        String sensor = "s" + random.nextInt(3);
        long seconds = random.nextInt(900) - (random.nextInt(10) == 0 ? 120 : 0);
        seconds = Math.max(0, seconds + i / 3);
        long value = random.nextInt(2001) - 1000;
        String id =
            random.nextInt(12) == 0 && !events.isEmpty()
                ? events.get(random.nextInt(events.size())).eventId()
                : "e" + i;
        String line =
            random.nextInt(15) == 0
                ? progress(id, sensor, seconds)
                : event(id, sensor, seconds, value);
        Engine.Event parsed =
            new Engine.Event(
                1,
                "demo",
                id,
                "acme",
                sensor,
                T0 + seconds * 1000,
                line.contains("PROGRESS") ? 0 : value,
                line.contains("PROGRESS"));
        lines.add(line);
        events.add(parsed);
      }
      Report report = Explainer.replay(lines, null, 1, null);
      BatchOracle oracle = new BatchOracle(1, report.nowMillis());
      Map<String, Engine.Event> firstById = new HashMap<>();
      for (Engine.Event e : events) {
        // An event id reused with a different payload is a conflict the engine rejects; the oracle
        // only sees records the engine would admit.
        Engine.Event previous = firstById.putIfAbsent("acme:" + e.eventId(), e);
        if (previous != null && !previous.canonical().equals(e.canonical())) continue;
        oracle.record(e, 0, report.nowMillis());
      }
      Map<String, BatchOracle.Total> expected = new TreeMap<>();
      oracle
          .totals()
          .forEach(
              (k, v) ->
                  expected.put(
                      k.tenant() + ":" + k.sensor() + ":" + k.type() + ":" + k.start(), v));
      Map<String, BatchOracle.Total> actual = new TreeMap<>();
      for (Row row : report.windows())
        actual.put(
            row.tenant() + ":" + row.sensor() + ":" + row.type() + ":" + row.start(),
            new BatchOracle.Total(row.count(), row.sum(), row.minimum(), row.maximum()));
      assertEquals(expected, actual, "seed " + seed);
      assertEquals(oracle.watermark(), report.watermark(), "seed " + seed);
    }
  }

  @Test
  void aTextReportNamesEachVerdictLateWindowAndFinalizationTime() throws Exception {
    Report report = Explainer.replay(Files.readAllLines(EXAMPLE), null, 1, null);
    String text = ExplainMain.text(report);
    assertTrue(
        text.startsWith(
            "Replay of run demo: 11 record(s) on 1 partition(s), processed at 2026-01-01T00:05:00Z\n"),
        text);
    assertTrue(text.contains("DUPLICATE"));
    assertTrue(
        text.contains("too late for finalized window acme:boiler:TUMBLING:2026-01-01T00:00:00Z\n"),
        text);
    assertTrue(
        text.contains("published acme:boiler:TUMBLING:2026-01-01T00:00:00Z rev 2 FINAL\n"), text);
    assertTrue(text.contains("published acme:boiler:SLIDING:2025-12-31T23:56:00Z rev 1\n"), text);
    assertTrue(text.contains("watermark none -> 2026-01-01T00:00:05Z"));
    assertTrue(text.contains("Summary: 7 accepted, 1 duplicate, 3 rejected"), text);
    assertTrue(text.contains("count=2 sum=7000 min=2000 max=5000  rev 2 FINAL"), text);
    assertEquals(
        text, ExplainMain.text(Explainer.replay(Files.readAllLines(EXAMPLE), null, 1, null)));
  }

  @Test
  void windowIdentifiersReadAsInstantsInTextOnly() {
    assertEquals(
        "a:b:TUMBLING:2026-01-01T00:00:00Z rev 3 FINAL",
        ExplainMain.readable("a:b:TUMBLING:" + T0 + " rev 3 FINAL"));
    assertEquals("a:b:SLIDING:2026-01-01T00:00:00Z", ExplainMain.readable("a:b:SLIDING:" + T0));
    assertEquals("not a window", ExplainMain.readable("not a window"));
    assertEquals("a:b:TYPE:x rev 1", ExplainMain.readable("a:b:TYPE:x rev 1"));
  }

  private record Run(int exit, String out, String err) {}

  private static Run main(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int exit =
        ExplainMain.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(
        exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void theCommandPrintsTextOrJsonAndRefusesBadInput() throws Exception {
    Run text = main(EXAMPLE.toString());
    assertEquals(0, text.exit(), text.err());
    assertTrue(text.out().contains("Windows\n"));

    Run json = main(EXAMPLE.toString(), "--format", "json");
    assertEquals(0, json.exit());
    JsonNode parsed = new ObjectMapper().readTree(json.out());
    assertEquals("demo", parsed.get("runId").asText());
    assertEquals(11, parsed.get("steps").size());
    assertEquals("DUPLICATE", parsed.at("/steps/2/verdict").asText());
    assertEquals(T0 + 295_000, parsed.get("watermarkMillis").asLong());
    assertTrue(parsed.at("/steps/0/watermarkBeforeMillis").isNull());
    assertEquals(1, parsed.get("duplicates").asInt());
    assertEquals(parsed.at("/windows").size() > 0, true);

    Run partitioned =
        main(
            EXAMPLE.toString(),
            "--partitions",
            "2",
            "--run",
            "demo",
            "--now",
            Long.toString(T0 + 900_000));
    assertEquals(0, partitioned.exit(), partitioned.err());
    assertTrue(partitioned.out().contains("on 2 partition(s)"));

    Path empty = temp.resolve("empty.jsonl");
    Files.writeString(empty, "");
    assertEquals(0, main(empty.toString()).exit());

    for (String[] args :
        new String[][] {
          {},
          {"--format", "xml", EXAMPLE.toString()},
          {EXAMPLE.toString(), "extra.jsonl"},
          {EXAMPLE.toString(), "--partitions"},
          {EXAMPLE.toString(), "--partitions", "0"},
          {EXAMPLE.toString(), "--partitions", "many"},
          {EXAMPLE.toString(), "--bogus"},
          {temp.resolve("missing.jsonl").toString()},
          {temp.toString()}
        }) {
      Run refused = main(args);
      assertEquals(2, refused.exit(), String.join(" ", args));
      assertTrue(refused.err().startsWith("explain: "), refused.err());
    }
    Path huge = temp.resolve("huge.jsonl");
    Files.write(huge, new byte[(int) ExplainMain.MAXIMUM_BYTES + 1]);
    assertTrue(main(huge.toString()).err().contains("exceeds 16 MiB"));
    Path link = temp.resolve("link.jsonl");
    Files.createSymbolicLink(link, EXAMPLE.toAbsolutePath());
    assertEquals(2, main(link.toString()).exit(), "links are refused");
  }
}
