package io.github.cheshmakzanoon.portfolio.backtrace.jfr;

import io.github.cheshmakzanoon.portfolio.backtrace.analysis.NormalizedEvent;
import io.github.cheshmakzanoon.portfolio.backtrace.analysis.NormalizedEvent.Kind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

/**
 * Explicit field whitelist: never copies messages, arguments, properties, environment, or thread
 * names.
 */
public final class EventNormalizer {
  private final LongAdder unsupportedFields = new LongAdder(),
      truncatedStacks = new LongAdder(),
      clippedStrings = new LongAdder();

  public Optional<NormalizedEvent> normalize(RecordedEvent event) {
    String name = event.getEventType().getName();
    Kind kind;
    long value;
    String subject = name;
    switch (name) {
      case "jdk.ExecutionSample" -> {
        kind = Kind.SAMPLE;
        value = 1;
      }
      case "jdk.ObjectAllocationSample" -> {
        kind = Kind.ALLOCATION;
        if (!event.hasField("weight") || !event.hasField("objectClass")) {
          unsupportedFields.increment();
          return Optional.empty();
        }
        value = event.getLong("weight");
        RecordedClass type = event.getClass("objectClass");
        subject = type == null ? "[unknown allocation class]" : type.getName();
      }
      case "jdk.GarbageCollection" -> {
        kind = Kind.GC;
        value = event.getDuration().toNanos();
      }
      case "jdk.JavaMonitorEnter" -> {
        kind = Kind.MONITOR;
        value = event.getDuration().toNanos();
        if (event.hasField("monitorClass")) {
          RecordedClass type = event.getClass("monitorClass");
          subject = type == null ? "[unknown monitor class]" : type.getName();
        } else unsupportedFields.increment();
      }
      case "jdk.ThreadPark" -> {
        kind = Kind.PARK;
        value = event.getDuration().toNanos();
        if (event.hasField("parkedClass")) {
          RecordedClass type = event.getClass("parkedClass");
          subject = type == null ? "[unknown park class]" : type.getName();
        } else unsupportedFields.increment();
      }
      case "jdk.DataLoss" -> {
        kind = Kind.LOSS;
        if (!event.hasField("amount")) {
          unsupportedFields.increment();
          return Optional.empty();
        }
        value = event.getLong("amount");
      }
      default -> {
        return Optional.empty();
      }
    }
    return Optional.of(
        new NormalizedEvent(
            kind,
            epochNanos(event.getStartTime()),
            Math.max(0, value),
            bounded(subject),
            stack(event.getStackTrace())));
  }

  public static long epochNanos(Instant time) {
    return Math.addExact(Math.multiplyExact(time.getEpochSecond(), 1_000_000_000L), time.getNano());
  }

  private String bounded(String value) {
    if (value.length() > 256) {
      clippedStrings.increment();
      return boundedIdentity(value);
    }
    return value;
  }

  private List<String> stack(RecordedStackTrace stack) {
    if (stack == null) return List.of();
    List<RecordedFrame> frames = stack.getFrames();
    if (stack.isTruncated() || frames.size() > 128) truncatedStacks.increment();
    List<String> result = new ArrayList<>(Math.min(128, frames.size()));
    // Keep the leaf-most 128 frames, reverse into root-to-leaf order.
    for (int i = Math.min(128, frames.size()) - 1; i >= 0; i--) {
      var method = frames.get(i).getMethod();
      result.add(
          method == null
              ? "[unknown frame]"
              : bounded(
                  method.getType().getName() + "." + method.getName() + method.getDescriptor()));
    }
    return result;
  }

  public long unsupportedFields() {
    return unsupportedFields.sum();
  }

  public long truncatedStacks() {
    return truncatedStacks.sum();
  }

  public long clippedStrings() {
    return clippedStrings.sum();
  }

  /**
   * Preserve stable distinctions beyond the display prefix without retaining oversized identifiers.
   */
  public static String boundedIdentity(String value) {
    return value.length() <= 256
        ? value
        : value.substring(0, 191)
            + "#"
            + io.github.cheshmakzanoon.portfolio.backtrace.probe.MethodRegistry.stableIdentity(
                value);
  }
}
