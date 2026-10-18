package io.github.cheshmakzanoon.portfolio.routeweaver;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
  private Json() {}

  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .withCoercionConfig(
              LogicalType.Textual,
              c ->
                  c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("cannot serialize bounded object", e);
    }
  }

  public static <T> java.util.List<T> readList(String value, Class<T> type) {
    try {
      return MAPPER.readValue(
          value, MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, type));
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid JSON list", e);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid JSON input", e);
    }
  }
}
