package io.github.cheshmakzanoon.portfolio.pulsestream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventCodecTest {
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
