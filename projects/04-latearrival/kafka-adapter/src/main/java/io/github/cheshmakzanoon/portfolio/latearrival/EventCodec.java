package io.github.cheshmakzanoon.portfolio.latearrival;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.common.utils.Utils;

/** A versioned bounded JSON envelope, deliberately separate from the engine. */
public final class EventCodec {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> FIELDS =
      Set.of(
          "schemaVersion",
          "runId",
          "eventId",
          "tenantId",
          "sensorId",
          "eventTimeEpochMillis",
          "valueMilliUnits",
          "recordType");

  private EventCodec() {}

  public static int partition(String key, int count) {
    return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % count;
  }

  public static Engine.Input decode(
      int partition, long offset, long nextOffset, String key, byte[] payload, int count) {
    if (payload == null || payload.length > 16_384)
      return new Engine.Input(partition, offset, nextOffset, key, null, "RECORD_SIZE");
    try {
      JsonNode n = JSON.readTree(payload);
      if (!n.isObject()) throw new IllegalArgumentException();
      n.fieldNames()
          .forEachRemaining(
              f -> {
                if (!FIELDS.contains(f)) throw new IllegalArgumentException();
              });
      for (String field : ListFields.TEXT)
        if (!n.path(field).isTextual()) throw new IllegalArgumentException();
      for (String field : ListFields.NUMBER)
        if (!n.path(field).isIntegralNumber() || !n.path(field).canConvertToLong())
          throw new IllegalArgumentException();
      if (n.has("recordType") && !n.path("recordType").isTextual())
        throw new IllegalArgumentException();
      String kind = n.path("recordType").asText("MEASUREMENT");
      if (!Set.of("MEASUREMENT", "PROGRESS").contains(kind)
          || !n.path("schemaVersion").canConvertToInt()) throw new IllegalArgumentException();
      var e =
          new Engine.Event(
              n.path("schemaVersion").intValue(),
              n.path("runId").textValue(),
              n.path("eventId").textValue(),
              n.path("tenantId").textValue(),
              n.path("sensorId").textValue(),
              n.path("eventTimeEpochMillis").longValue(),
              n.path("valueMilliUnits").longValue(),
              kind.equals("PROGRESS"));
      String error =
          key == null || !key.equals(e.key()) || partition(key, count) != partition
              ? "ROUTING_MISMATCH"
              : null;
      if (e.progress() && e.valueMilliUnits() != 0) error = "INVALID_PROGRESS";
      return new Engine.Input(partition, offset, nextOffset, key, e, error);
    } catch (Exception e) {
      return new Engine.Input(partition, offset, nextOffset, key, null, "MALFORMED_JSON");
    }
  }

  public static byte[] encode(Engine.Event e) {
    ObjectNode n = JSON.createObjectNode();
    n.put("schemaVersion", e.schemaVersion());
    n.put("runId", e.runId());
    n.put("eventId", e.eventId());
    n.put("tenantId", e.tenantId());
    n.put("sensorId", e.sensorId());
    n.put("eventTimeEpochMillis", e.eventTimeEpochMillis());
    n.put("valueMilliUnits", e.valueMilliUnits());
    n.put("recordType", e.progress() ? "PROGRESS" : "MEASUREMENT");
    return n.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static class ListFields {
    static final String[] TEXT = {"runId", "eventId", "tenantId", "sensorId"};
    static final String[] NUMBER = {"schemaVersion", "eventTimeEpochMillis", "valueMilliUnits"};
  }
}
