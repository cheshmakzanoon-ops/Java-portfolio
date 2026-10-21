package io.github.cheshmakzanoon.portfolio.latearrival;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, thread-confined event-time machine. A transition never mutates its input. */
public final class Engine {
  public static final long MINUTE = 60_000, DISORDER = 5_000, LATENESS = 30_000, IDLE = 30_000;

  public record Limits(int partitions, int keys, int panes, int events, long futureSkewMillis)
      implements Serializable {
    public Limits {
      if (partitions < 1
          || keys < 1
          || keys > 50_000
          || panes < 1
          || events < 1
          || futureSkewMillis < 0) throw new IllegalArgumentException("invalid limits");
    }
  }

  public record Event(
      int schemaVersion,
      String runId,
      String eventId,
      String tenantId,
      String sensorId,
      long eventTimeEpochMillis,
      long valueMilliUnits,
      boolean progress)
      implements Serializable {
    public String key() {
      return tenantId + ":" + sensorId;
    }

    public String canonical() {
      return schemaVersion
          + "|"
          + runId
          + "|"
          + eventId
          + "|"
          + tenantId
          + "|"
          + sensorId
          + "|"
          + eventTimeEpochMillis
          + "|"
          + valueMilliUnits
          + "|"
          + progress;
    }
  }

  public record Input(
      int partition,
      long offset,
      long nextOffset,
      String kafkaKey,
      Event event,
      String malformedReason)
      implements Serializable {
    public Input {
      if (partition < 0 || offset < 0 || nextOffset <= offset)
        throw new IllegalArgumentException("offset");
    }

    public static Input of(int p, long offset, Event e) {
      return new Input(p, offset, Math.addExact(offset, 1), e.key(), e, null);
    }
  }

  public record Key(String tenant, String sensor) implements Serializable {}

  public record Pane(Key key, long start) implements Serializable {}

  public record Window(Key key, String type, long start) implements Serializable {
    public long end() {
      return Math.addExact(start, type.equals("TUMBLING") ? MINUTE : 5 * MINUTE);
    }

    public String id() {
      return key.tenant + ":" + key.sensor + ":" + type + ":" + start;
    }
  }

  public record Aggregate(long count, long sum, long minimum, long maximum)
      implements Serializable {
    public Aggregate add(long value) {
      return new Aggregate(
          Math.addExact(count, 1),
          Math.addExact(sum, value),
          Math.min(minimum, value),
          Math.max(maximum, value));
    }

    public Aggregate merge(Aggregate b) {
      return new Aggregate(
          Math.addExact(count, b.count),
          Math.addExact(sum, b.sum),
          Math.min(minimum, b.minimum),
          Math.max(maximum, b.maximum));
    }

    public static Aggregate empty() {
      return new Aggregate(0, 0, Long.MAX_VALUE, Long.MIN_VALUE);
    }
  }

  public record Result(Window window, Aggregate aggregate, long revision, boolean finalized)
      implements Serializable {}

  public record Rejection(String eventId, String tenant, String reason, String windowId)
      implements Serializable {}

  public record Partition(long maxTimestamp, long lastActivity, boolean idle)
      implements Serializable {}

  /**
   * Broker observations are facts from the poll owner, never guesses from engine queue emptiness.
   */
  public record Observation(int partition, boolean paused, long brokerLag, int queued) {
    public Observation {
      if (partition < 0 || brokerLag < 0 || queued < 0)
        throw new IllegalArgumentException("observation");
    }
  }

  public static final class State implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String runId;
    public final Limits limits;
    public long watermark = Long.MIN_VALUE;
    public long duplicates;
    public final Map<Pane, Aggregate> panes = new HashMap<>();
    public final Map<Window, Result> results = new HashMap<>();
    public final Set<Window> pending = new HashSet<>();
    public final Set<Key> admitted = new HashSet<>();
    public final Map<String, String> seen = new HashMap<>();
    public final Map<Integer, Long> offsets = new TreeMap<>();
    public final Map<Integer, Partition> partitions = new TreeMap<>();
    public final List<Rejection> quarantine = new ArrayList<>();
    public final List<Rejection> late = new ArrayList<>();
    public final List<Result> outbox = new ArrayList<>();

    public State(String runId, Limits limits, long now) {
      if (!identifier(runId)) throw new IllegalArgumentException("run");
      this.runId = runId;
      this.limits = limits;
      for (int p = 0; p < limits.partitions; p++)
        partitions.put(p, new Partition(Long.MIN_VALUE, now, false));
    }

    private State(State s) {
      runId = s.runId;
      limits = s.limits;
      watermark = s.watermark;
      duplicates = s.duplicates;
      panes.putAll(s.panes);
      s.results.forEach(
          (w, r) -> {
            if (!r.finalized) results.put(w, r);
          });
      pending.addAll(s.pending);
      admitted.addAll(s.admitted);
      seen.putAll(s.seen);
      offsets.putAll(s.offsets);
      partitions.putAll(s.partitions);
      // Outbox contains only this transaction's new publications; durable store owns delivery.
    }

    public State afterRestart(long now) {
      State s = new State(this);
      partitions.forEach((p, v) -> s.partitions.put(p, new Partition(v.maxTimestamp, now, false)));
      return s;
    }
  }

  private Engine() {}

  public static State transition(
      State committed, List<Input> inputs, List<Observation> observations, long now) {
    if (now < 0) throw new IllegalArgumentException("clock");
    State next = new State(committed);
    for (Input input : inputs) accept(next, input, now);
    for (Observation o : observations) {
      Partition p = next.partitions.get(o.partition);
      if (p == null) throw new IllegalArgumentException("partition");
      boolean eligible = !o.paused && o.brokerLag == 0 && o.queued == 0;
      // An ineligible interval resets the silence proof, including known broker backlog.
      if (!eligible) next.partitions.put(o.partition, new Partition(p.maxTimestamp, now, false));
      else if (now - p.lastActivity >= IDLE)
        next.partitions.put(o.partition, new Partition(p.maxTimestamp, p.lastActivity, true));
    }
    advance(next);
    return next;
  }

  private static void accept(State s, Input in, long now) {
    if (!s.partitions.containsKey(in.partition)) throw new IllegalArgumentException("partition");
    long previous = s.offsets.getOrDefault(in.partition, 0L);
    if (in.offset < previous) throw new IllegalArgumentException("offset moved backwards");
    s.offsets.put(in.partition, in.nextOffset);
    Event e = in.event;
    if (in.malformedReason != null || e == null) {
      reject(s, e, in.malformedReason == null ? "MALFORMED" : in.malformedReason, null);
      return;
    }
    if (e.progress && e.valueMilliUnits != 0
        || e.schemaVersion != 1
        || !identifier(e.runId)
        || !s.runId.equals(e.runId)
        || !identifier(e.eventId)
        || !identifier(e.tenantId)
        || !identifier(e.sensorId)
        || e.eventTimeEpochMillis > Long.MAX_VALUE - 6 * MINUTE
        || e.eventTimeEpochMillis < 0
        || e.eventTimeEpochMillis > now && e.eventTimeEpochMillis - now > s.limits.futureSkewMillis
        || !e.key().equals(in.kafkaKey)) {
      reject(s, e, "INVALID_RECORD", null);
      return;
    }
    String identity = e.tenantId + ":" + e.eventId;
    String hash = hash(e.canonical());
    String prior = s.seen.get(identity);
    if (prior != null) {
      if (prior.equals(hash)) s.duplicates++;
      else reject(s, e, "CONFLICTING_EVENT_ID", null);
      return;
    }
    if (s.seen.size() >= s.limits.events) {
      reject(s, e, "RUN_EVENT_QUOTA", null);
      return;
    }
    s.seen.put(identity, hash);
    if (!e.progress) {
      Key key = new Key(e.tenantId, e.sensorId);
      if (!s.admitted.contains(key) && s.admitted.size() >= s.limits.keys) {
        reject(s, e, "KEY_QUOTA", null);
        return;
      }
      long start = Math.floorDiv(e.eventTimeEpochMillis, MINUTE) * MINUTE;
      Pane pane = new Pane(key, start);
      List<Window> windows = assigned(key, start);
      List<Window> open = windows.stream().filter(w -> !closed(s, w)).toList();
      if (!open.isEmpty() && !s.panes.containsKey(pane) && s.panes.size() >= s.limits.panes) {
        reject(s, e, "PANE_QUOTA", null);
        return;
      }
      Aggregate updated;
      try {
        updated = s.panes.getOrDefault(pane, Aggregate.empty()).add(e.valueMilliUnits);
        // Merging also checks sliding-window sums before modifying ANY aggregate.
        for (Window w : open) aggregate(s, w, pane, updated);
      } catch (ArithmeticException ex) {
        reject(s, e, "ARITHMETIC_OVERFLOW", null);
        return;
      }
      s.admitted.add(key);
      if (!open.isEmpty()) s.panes.put(pane, updated);
      for (Window w : windows) {
        if (closed(s, w))
          s.late.add(new Rejection(e.eventId, e.tenantId, "FINALIZED_WINDOW", w.id()));
        else {
          s.pending.add(w);
          Result previousResult = s.results.get(w);
          if (previousResult != null || s.watermark >= w.end())
            publish(
                s,
                new Result(
                    w,
                    aggregate(s, w, null, null),
                    previousResult == null ? 1 : previousResult.revision + 1,
                    false));
        }
      }
    }
    Partition p = s.partitions.get(in.partition);
    s.partitions.put(
        in.partition, new Partition(Math.max(p.maxTimestamp, e.eventTimeEpochMillis), now, false));
    advance(s);
  }

  public static List<Window> assigned(Key key, long paneStart) {
    // Membership is exactly one tumbling window and five overlapping sliding windows.
    // Allocate that fixed bound; an invalid cursor cannot grow retained memory.
    Window[] result = new Window[6];
    result[0] = new Window(key, "TUMBLING", paneStart);
    for (int i = 0; i < 5; i++) result[i + 1] = new Window(key, "SLIDING", paneStart - i * MINUTE);
    return List.of(result);
  }

  private static boolean closed(State s, Window w) {
    return s.watermark >= w.end() + LATENESS;
  }

  private static Aggregate aggregate(State s, Window w, Pane replace, Aggregate replacement) {
    Aggregate result = Aggregate.empty();
    for (long t = w.start; t < w.end(); t += MINUTE) {
      Pane pane = new Pane(w.key, t);
      Aggregate a = pane.equals(replace) ? replacement : s.panes.get(pane);
      if (a != null) result = result.merge(a);
    }
    return result;
  }

  private static void advance(State s) {
    long candidate = Long.MAX_VALUE;
    boolean active = false;
    for (Partition p : s.partitions.values())
      if (!p.idle) {
        active = true;
        candidate =
            Math.min(
                candidate,
                p.maxTimestamp == Long.MIN_VALUE ? Long.MIN_VALUE : p.maxTimestamp - DISORDER);
      }
    long previousWatermark = s.watermark;
    if (active) s.watermark = Math.max(s.watermark, candidate);
    // Only newly due windows or panes can change on a watermark step. A new late
    // window under an unchanged watermark is published directly by accept().
    if (s.watermark == previousWatermark) return;
    for (Window w : List.copyOf(s.pending)) {
      if (s.watermark < w.end()) continue;
      boolean done = closed(s, w);
      Result previous = s.results.get(w);
      if (previous == null || done && !previous.finalized) {
        publish(
            s,
            new Result(
                w,
                aggregate(s, w, null, null),
                previous == null ? 1 : previous.revision + 1,
                done));
      }
      if (done) s.pending.remove(w);
    }
    s.panes.keySet().removeIf(p -> s.watermark >= p.start + 5 * MINUTE + LATENESS);
  }

  private static void publish(State s, Result r) {
    s.results.put(r.window, r);
    s.outbox.add(r);
  }

  private static void reject(State s, Event e, String why, String window) {
    s.quarantine.add(
        new Rejection(
            e == null || !identifier(e.eventId) ? "unknown" : e.eventId,
            e == null || !identifier(e.tenantId) ? "!unattributed" : e.tenantId,
            why,
            window));
  }

  public static boolean identifier(String s) {
    return s != null && s.matches("[A-Za-z0-9_-]{1,64}");
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
