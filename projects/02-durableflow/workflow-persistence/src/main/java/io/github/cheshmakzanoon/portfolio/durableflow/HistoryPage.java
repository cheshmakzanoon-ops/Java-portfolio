package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.Event;
import java.util.List;
import java.util.UUID;

/** An immutable prefix of one tenant-authorized execution's append-only history. */
public record HistoryPage(
    UUID instance,
    long snapshotVersion,
    long after,
    long nextAfter,
    boolean hasMore,
    List<Event> events) {
  public HistoryPage {
    if (instance == null
        || snapshotVersion < 1
        || snapshotVersion > WorkflowLimits.HISTORY_EVENTS
        || after < 0
        || after > snapshotVersion
        || events == null
        || events.size() > WorkflowLimits.HISTORY_PAGE_EVENTS)
      throw new IllegalArgumentException("history page bounds");
    events = List.copyOf(events);
    long expected = after;
    for (Event event : events) {
      if (event.sequence() != ++expected || expected > snapshotVersion)
        throw new IllegalArgumentException("history page ordering");
    }
    if (nextAfter != expected || hasMore != (nextAfter < snapshotVersion))
      throw new IllegalArgumentException("history page cursor");
    if (hasMore && events.isEmpty())
      throw new IllegalArgumentException("history page must make progress");
  }

  static void validateRequest(long after, Long snapshotVersion, int limit) {
    if (after < 0
        || after > WorkflowLimits.HISTORY_EVENTS
        || limit < 1
        || limit > WorkflowLimits.HISTORY_PAGE_EVENTS
        || (snapshotVersion != null
            && (snapshotVersion < 1
                || snapshotVersion > WorkflowLimits.HISTORY_EVENTS
                || after > snapshotVersion)))
      throw new IllegalArgumentException("history page bounds");
  }

  static HistoryPage from(
      UUID instance, long snapshotVersion, long after, int limit, List<Event> events) {
    validateRequest(after, snapshotVersion, limit);
    long expectedCount = Math.min(limit, snapshotVersion - after);
    if (events.size() != expectedCount)
      throw new IllegalStateException("persisted history is incomplete");
    long next = after + events.size();
    try {
      return new HistoryPage(
          instance, snapshotVersion, after, next, next < snapshotVersion, events);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("persisted history is not contiguous", invalid);
    }
  }
}
