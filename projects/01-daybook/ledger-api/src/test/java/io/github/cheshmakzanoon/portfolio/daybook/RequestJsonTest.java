package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestJsonTest {
  @Test
  void decimalStringsAndNamedSidesRejectScalarCoercionAndDuplicateKeys() {
    var builder = JsonMapper.builder();
    new DaybookApplication().strictRequestJson().customize(builder);
    var mapper = builder.build();
    String prefix =
        "{\"account\":\"00000000-0000-0000-0000-000000000001\",\"side\":\"DEBIT\",\"amount\":";
    assertEquals(
        "1.00", mapper.readValue(prefix + "\"1.00\"}", LedgerController.LineInput.class).amount());
    for (String invalid : java.util.List.of("1", "1.25", "true", "{}", "[]"))
      assertThrows(
          tools.jackson.core.JacksonException.class,
          () -> mapper.readValue(prefix + invalid + "}", LedgerController.LineInput.class),
          invalid);
    assertThrows(
        tools.jackson.core.JacksonException.class,
        () ->
            mapper.readValue(
                (prefix + "\"1.00\"}").replace("\"DEBIT\"", "0"),
                LedgerController.LineInput.class));
    assertThrows(
        tools.jackson.core.JacksonException.class,
        () ->
            mapper.readValue(
                prefix + "\"1.00\",\"amount\":\"2.00\"}", LedgerController.LineInput.class));
  }
}
