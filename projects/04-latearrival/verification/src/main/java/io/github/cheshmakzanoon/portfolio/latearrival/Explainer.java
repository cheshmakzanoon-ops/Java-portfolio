package io.github.cheshmakzanoon.portfolio.latearrival;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Replays recorded events through the real {@link Engine}, one record at a time, and says what
 * happened to each: accepted, a duplicate, rejected (with the engine's reason), late for a
 * finalized window, and what moved the watermark or published a result. No Kafka, PostgreSQL or
 * wall clock is involved; the engine sees the same inputs a production poll owner would build.
 *
 * <p>Every record is a separate engine transition, so the replay shows event-time behaviour, not
 * batching or durability: it cannot show recovery, fencing, source idleness (no observations are
 * supplied, so no partition ever goes idle) or output delivery.
 */
public final class Explainer {
  public static final long DEFAULT_SKEW_MILLIS = 300_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  public enum Verdict {
    ACCEPTED,
    DUPLICATE,
    REJECTED
  }

  /** One input record and its consequences. {@code line} is the 1-based line in the input. */
  public record Step(
      int line,
      int partition,
      long offset,
      String eventId,
      String sensor,
      Long eventTimeMillis,
      Verdict verdict,
      String detail,
      long watermarkBefore,
      long watermarkAfter,
      List<String> lateForFinalized,
      List<String> published) {
    public Step {
      lateForFinalized = List.copyOf(lateForFinalized);
      published = List.copyOf(published);
    }
  }

  public record Row(
      String tenant,
      String sensor,
      String type,
      long start,
      long end,
      long count,
      long sum,
      long minimum,
      long maximum,
      long revision,
      String status,
      Long publishesAtWatermark,
      Long finalizesAtWatermark) {}

  public record Report(
      String runId,
      int partitions,
      long nowMillis,
      List<Step> steps,
      List<Row> windows,
      long watermark,
      long duplicates,
      Map<Integer, Long> maxEventTimeByPartition) {
    public Report {
      steps = List.copyOf(steps);
      windows = List.copyOf(windows);
      maxEventTimeByPartition = Collections.unmodifiableMap(new TreeMap<>(maxEventTimeByPartition));
    }

    public long count(Verdict verdict) {
      return steps.stream().filter(s -> s.verdict() == verdict).count();
    }
  }

  private Explainer() {}

  /**
   * @param runId the run the engine belongs to; events of any other run are rejected. When null,
   *     the first parseable record's run is adopted.
   * @param nowMillis the processing time every record is processed at; when null, the largest event
   *     time in the input, so that no record is "from the future". The skew guard still applies
   *     when a smaller value is given.
   */
  public static Report replay(List<String> lines, String runId, int partitions, Long nowMillis) {
    if (partitions < 1 || partitions > 64) throw new IllegalArgumentException("partitions");
    List<Parsed> records = new ArrayList<>();
    long largest = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).isBlank()) continue;
      Parsed parsed = parse(lines.get(i), i + 1);
      records.add(parsed);
      if (parsed.eventTime != null) largest = Math.max(largest, parsed.eventTime);
    }
    String run = runId;
    for (Parsed p : records) {
      if (run == null && p.runId != null && Engine.identifier(p.runId)) run = p.runId;
    }
    if (run == null) run = "replay";
    long now = nowMillis == null ? largest : nowMillis;

    Engine.State state =
        new Engine.State(
            run, new Engine.Limits(partitions, 1_000, 100_000, 100_000, DEFAULT_SKEW_MILLIS), now);
    Map<Integer, Long> nextOffsets = new TreeMap<>();
    Map<String, Engine.Result> latest = new TreeMap<>();
    List<Step> steps = new ArrayList<>();
    for (Parsed record : records) {
      int partition = record.key == null ? 0 : EventCodec.partition(record.key, partitions);
      long offset = nextOffsets.getOrDefault(partition, 0L);
      nextOffsets.put(partition, offset + 1);
      Engine.Input input =
          EventCodec.decode(
              partition,
              offset,
              offset + 1,
              record.key == null ? "?" : record.key,
              record.text.getBytes(StandardCharsets.UTF_8),
              partitions);
      long before = state.watermark;
      long duplicatesBefore = state.duplicates;
      Engine.State next = Engine.transition(state, List.of(input), List.of(), now);
      Verdict verdict;
      String detail;
      if (!next.quarantine.isEmpty()) {
        verdict = Verdict.REJECTED;
        detail = next.quarantine.getLast().reason();
      } else if (next.duplicates > duplicatesBefore) {
        verdict = Verdict.DUPLICATE;
        detail = "identical to an earlier record with the same event id";
      } else {
        verdict = Verdict.ACCEPTED;
        detail =
            input.event() != null && input.event().progress()
                ? "progress record: advances the partition without a measurement"
                : "joined " + joined(input.event(), next);
      }
      List<String> late = new ArrayList<>();
      for (Engine.Rejection r : next.late) late.add(r.windowId());
      List<String> published = new ArrayList<>();
      for (Engine.Result r : next.outbox) {
        published.add(r.window().id() + " rev " + r.revision() + (r.finalized() ? " FINAL" : ""));
        latest.put(r.window().id(), r);
      }
      steps.add(
          new Step(
              record.line,
              partition,
              offset,
              input.event() == null ? null : input.event().eventId(),
              input.event() == null ? null : input.event().key(),
              record.eventTime,
              verdict,
              detail,
              before,
              next.watermark,
              late,
              published));
      state = next;
    }
    return new Report(
        run,
        partitions,
        now,
        steps,
        rows(state, latest),
        state.watermark,
        state.duplicates,
        maxima(state));
  }

  private static String joined(Engine.Event event, Engine.State state) {
    long start = Math.floorDiv(event.eventTimeEpochMillis(), Engine.MINUTE) * Engine.MINUTE;
    int windows = 0, finalized = 0;
    for (Engine.Window w :
        Engine.assigned(new Engine.Key(event.tenantId(), event.sensorId()), start)) {
      windows++;
      if (state.watermark >= w.end() + Engine.LATENESS) finalized++;
    }
    return (windows - finalized) + " of " + windows + " windows";
  }

  private static Map<Integer, Long> maxima(Engine.State state) {
    Map<Integer, Long> result = new TreeMap<>();
    state.partitions.forEach((p, v) -> result.put(p, v.maxTimestamp()));
    return result;
  }

  private static List<Row> rows(Engine.State state, Map<String, Engine.Result> latest) {
    Map<String, Row> rows = new TreeMap<>();
    for (Engine.Result r : latest.values()) {
      Engine.Window w = r.window();
      rows.put(
          w.id(),
          new Row(
              w.key().tenant(),
              w.key().sensor(),
              w.type(),
              w.start(),
              w.end(),
              r.aggregate().count(),
              r.aggregate().sum(),
              r.aggregate().minimum(),
              r.aggregate().maximum(),
              r.revision(),
              r.finalized() ? "FINAL" : "PROVISIONAL",
              null,
              r.finalized() ? null : Math.addExact(w.end(), Engine.LATENESS)));
    }
    // Windows that hold data but have not been published yet: the watermark has not reached them.
    for (Engine.Window w : state.pending) {
      if (rows.containsKey(w.id())) continue;
      Engine.Aggregate total = Engine.Aggregate.empty();
      for (long t = w.start(); t < w.end(); t += Engine.MINUTE) {
        Engine.Aggregate a = state.panes.get(new Engine.Pane(w.key(), t));
        if (a != null) total = total.merge(a);
      }
      rows.put(
          w.id(),
          new Row(
              w.key().tenant(),
              w.key().sensor(),
              w.type(),
              w.start(),
              w.end(),
              total.count(),
              total.sum(),
              total.minimum(),
              total.maximum(),
              0,
              "OPEN",
              w.end(),
              Math.addExact(w.end(), Engine.LATENESS)));
    }
    List<Row> ordered = new ArrayList<>(rows.values());
    ordered.sort(
        Comparator.comparing(Row::tenant)
            .thenComparing(Row::sensor)
            .thenComparing(Row::type)
            .thenComparingLong(Row::start));
    return ordered;
  }

  private record Parsed(int line, String text, String key, String runId, Long eventTime) {}

  /** Best-effort peek at routing fields only; the engine's own codec decides what is valid. */
  private static Parsed parse(String text, int line) {
    try {
      JsonNode n = JSON.readTree(text);
      String tenant = n.path("tenantId").isTextual() ? n.path("tenantId").textValue() : null;
      String sensor = n.path("sensorId").isTextual() ? n.path("sensorId").textValue() : null;
      String run = n.path("runId").isTextual() ? n.path("runId").textValue() : null;
      Long time =
          n.path("eventTimeEpochMillis").isIntegralNumber()
                  && n.path("eventTimeEpochMillis").canConvertToLong()
              ? n.path("eventTimeEpochMillis").longValue()
              : null;
      return new Parsed(
          line, text, tenant == null || sensor == null ? null : tenant + ":" + sensor, run, time);
    } catch (Exception malformed) {
      return new Parsed(line, text, null, null, null);
    }
  }

  public static String instant(long millis) {
    if (millis == Long.MIN_VALUE) return "none";
    try {
      return Instant.ofEpochMilli(millis).toString();
    } catch (RuntimeException outOfRange) {
      return Long.toString(millis);
    }
  }
}
