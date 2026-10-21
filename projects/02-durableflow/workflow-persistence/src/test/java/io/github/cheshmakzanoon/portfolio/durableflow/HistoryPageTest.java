package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.Value;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.Event;
import io.github.cheshmakzanoon.portfolio.durableflow.Interpreter.EventType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryPageTest {
  private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000017");

  private Event event(long sequence) {
    return new Event(
        sequence, EventType.LATE_EVENT, "", "late signal:approval", Instant.EPOCH, List.of());
  }

  @Test
  void cursorNamesTheLastReturnedEventAndTheOriginalSnapshot() {
    var page = HistoryPage.from(INSTANCE, 7, 2, 3, List.of(event(3), event(4), event(5)));
    assertEquals(INSTANCE, page.instance());
    assertEquals(7, page.snapshotVersion());
    assertEquals(2, page.after());
    assertEquals(5, page.nextAfter());
    assertTrue(page.hasMore());
    assertEquals(List.of(3L, 4L, 5L), page.events().stream().map(Event::sequence).toList());
  }

  @Test
  void finalPartialPageAndExhaustedPageDoNotInventAnotherCursor() {
    var tail = HistoryPage.from(INSTANCE, 7, 5, 100, List.of(event(6), event(7)));
    assertFalse(tail.hasMore());
    assertEquals(7, tail.nextAfter());
    var exhausted = HistoryPage.from(INSTANCE, 7, 7, 100, List.of());
    assertFalse(exhausted.hasMore());
    assertEquals(7, exhausted.nextAfter());
    assertTrue(exhausted.events().isEmpty());
  }

  @Test
  void typedValuesAndHistoryOwnershipAreImmutable() {
    var values = new ArrayList<>(List.of(Value.integer(12)));
    var completed = new Event(1, EventType.COMPLETED, "sum", "completed", Instant.EPOCH, values);
    var source = new ArrayList<>(List.of(completed));
    var page = HistoryPage.from(INSTANCE, 1, 0, 10, source);
    values.clear();
    source.clear();
    assertEquals(List.of(Value.integer(12)), page.events().getFirst().result());
    assertThrows(UnsupportedOperationException.class, () -> page.events().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> page.events().getFirst().result().clear());
  }

  @Test
  void refusesInvalidRequestBoundsBeforeAConnectionIsNeeded() {
    for (long after : new long[] {-1, 10001, Long.MIN_VALUE, Long.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> HistoryPage.validateRequest(after, null, 1));
    for (long snapshot : new long[] {-1, 0, 10001, Long.MIN_VALUE, Long.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> HistoryPage.validateRequest(0, snapshot, 1));
    for (int limit : new int[] {-1, 0, 201, Integer.MIN_VALUE, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> HistoryPage.validateRequest(0, null, limit));
    assertThrows(IllegalArgumentException.class, () -> HistoryPage.validateRequest(2, 1L, 1));
    assertDoesNotThrow(() -> HistoryPage.validateRequest(0, null, 1));
    assertDoesNotThrow(() -> HistoryPage.validateRequest(10000, 10000L, 200));
  }

  @Test
  void gapsDuplicatesReorderingAndShortReadsCannotCertifyAnExhaustedSnapshot() {
    for (List<Event> invalid :
        List.of(
            List.of(event(1), event(3)),
            List.of(event(1), event(1)),
            List.of(event(2), event(1)),
            List.of(event(2), event(3))))
      assertThrows(IllegalStateException.class, () -> HistoryPage.from(INSTANCE, 3, 0, 2, invalid));
    assertThrows(
        IllegalStateException.class, () -> HistoryPage.from(INSTANCE, 3, 0, 2, List.of(event(1))));
    assertThrows(IllegalStateException.class, () -> HistoryPage.from(INSTANCE, 3, 0, 2, List.of()));
    assertThrows(
        IllegalStateException.class,
        () -> HistoryPage.from(INSTANCE, 3, 0, 2, List.of(event(1), event(2), event(3))));
  }

  @Test
  void serializedPageMetadataCannotContradictItsContents() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoryPage(INSTANCE, 3, 0, 1, false, List.of(event(1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoryPage(INSTANCE, 3, 0, 2, true, List.of(event(1))));
    assertThrows(
        IllegalArgumentException.class, () -> new HistoryPage(INSTANCE, 3, 0, 0, true, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new HistoryPage(INSTANCE, 3, 3, 3, true, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoryPage(null, 1, 0, 1, false, List.of(event(1))));
    assertThrows(
        IllegalArgumentException.class, () -> new HistoryPage(INSTANCE, 1, 0, 1, false, null));
    assertThrows(
        IllegalArgumentException.class, () -> new HistoryPage(INSTANCE, 1, 2, 2, false, List.of()));
  }

  @Test
  void everyAllowedPageSizeTraversesACompleteSnapshotExactlyOnce() {
    List<Event> expected = new ArrayList<>();
    for (int sequence = 1; sequence <= 421; sequence++) expected.add(event(sequence));
    for (int limit = 1; limit <= 200; limit++) {
      List<Event> observed = new ArrayList<>();
      long after = 0;
      boolean more;
      do {
        int end = Math.min(expected.size(), Math.toIntExact(after) + limit);
        var page =
            HistoryPage.from(
                INSTANCE,
                expected.size(),
                after,
                limit,
                expected.subList(Math.toIntExact(after), end));
        observed.addAll(page.events());
        assertTrue(page.nextAfter() > after);
        after = page.nextAfter();
        more = page.hasMore();
      } while (more);
      assertEquals(expected, observed, "page size " + limit);
      assertEquals(421, after);
    }
  }
}
