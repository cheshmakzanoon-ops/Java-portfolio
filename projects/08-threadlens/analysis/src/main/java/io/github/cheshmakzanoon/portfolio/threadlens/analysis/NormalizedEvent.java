package io.github.cheshmakzanoon.portfolio.threadlens.analysis;

import java.util.List;

/**
 * Stack order is root to leaf. Value is bytes for allocation, nanoseconds for durations, 1 for
 * samples.
 */
public record NormalizedEvent(
    Kind kind, long epochNanos, long value, String subject, List<String> stack) {
  public enum Kind {
    SAMPLE,
    ALLOCATION,
    GC,
    MONITOR,
    PARK,
    TIMING,
    LOSS
  }

  public NormalizedEvent {
    if (kind == null || epochNanos < 0 || value < 0 || subject == null || stack == null)
      throw new IllegalArgumentException("invalid normalized event");
    if (subject.length() > 256
        || stack.size() > 128
        || stack.stream().anyMatch(s -> s == null || s.length() > 256))
      throw new IllegalArgumentException("normalization bounds");
    stack = List.copyOf(stack);
  }
}
