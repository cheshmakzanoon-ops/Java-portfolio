package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventCodecTest {
  @Test
  void explicitlySuppliedRecordTypeMustBeARecognizedString() {
    var event = new Engine.Event(1, "r", "e", "t", "a", 500, 42, false);
    var json = new String(EventCodec.encode(event), StandardCharsets.UTF_8);
    int partition = EventCodec.partition(event.key(), 1);
    for (String invalid : List.of("null", "{}", "[]", "true", "1", "\"UNKNOWN\"", "\"\"")) {
      byte[] payload =
          json.replace("\"recordType\":\"MEASUREMENT\"", "\"recordType\":" + invalid)
              .getBytes(StandardCharsets.UTF_8);
      var input = EventCodec.decode(partition, 0, 1, event.key(), payload, 1);
      assertEquals("MALFORMED_JSON", input.malformedReason(), invalid);
      assertNull(input.event(), invalid);
      var state =
          Engine.transition(
              new Engine.State("r", new Engine.Limits(1, 10, 100, 100, 0), 500),
              List.of(input),
              List.of(),
              500);
      assertEquals(1, state.offsets.get(0), invalid);
      assertEquals(1, state.quarantine.size(), invalid);
      assertTrue(state.panes.isEmpty(), invalid);
      assertTrue(state.seen.isEmpty(), invalid);
    }
  }

  @Test
  void omittedLegacyRecordTypeStillDefaultsToMeasurement() {
    var event = new Engine.Event(1, "r", "e", "t", "a", 500, 42, false);
    byte[] payload =
        new String(EventCodec.encode(event), StandardCharsets.UTF_8)
            .replace(",\"recordType\":\"MEASUREMENT\"", "")
            .getBytes(StandardCharsets.UTF_8);
    var input = EventCodec.decode(0, 0, 1, event.key(), payload, 1);
    assertNull(input.malformedReason());
    assertEquals(event, input.event());
  }

  @Test
  void validatesVersionedWireAndRouting() {
    var e = new Engine.Event(1, "r", "e", "t", "a", 500, 42, false);
    int p = EventCodec.partition(e.key(), 8);
    assertEquals(e, EventCodec.decode(p, 2, 7, e.key(), EventCodec.encode(e), 8).event());
    assertNull(EventCodec.decode(p, 2, 7, e.key(), EventCodec.encode(e), 8).malformedReason());
    assertEquals(
        "ROUTING_MISMATCH",
        EventCodec.decode((p + 1) % 8, 2, 7, e.key(), EventCodec.encode(e), 8).malformedReason());
    assertEquals(
        "MALFORMED_JSON",
        EventCodec.decode(p, 0, 1, e.key(), "{} {}".getBytes(), 8).malformedReason());
    assertEquals(
        "RECORD_SIZE", EventCodec.decode(p, 0, 1, e.key(), new byte[16385], 8).malformedReason());
    byte[] duplicate =
        new String(EventCodec.encode(e), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":2")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(
        "MALFORMED_JSON", EventCodec.decode(p, 0, 1, e.key(), duplicate, 8).malformedReason());
    var progress = new Engine.Event(1, "r", "p", "t", "a", 500, 1, true);
    assertEquals(
        "INVALID_PROGRESS",
        EventCodec.decode(p, 0, 1, e.key(), EventCodec.encode(progress), 8).malformedReason());
  }
}
