package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestJsonTest {
  @Test
  void publicJsonRejectsAmbiguousFieldsTruncationAndScalarCoercion() {
    var builder = JsonMapper.builder();
    new Application(null).strictRequestJson().customize(builder);
    var mapper = builder.build();
    String valid = "{\"worker\":\"worker-a\",\"batch\":4,\"leaseSeconds\":10}";
    assertEquals(
        new Application.ClaimRequest("worker-a", 4, 10),
        mapper.readValue(valid, Application.ClaimRequest.class));
    for (String invalid :
        List.of(
            valid.replace("\"worker-a\"", "123"),
            valid.replace("\"worker-a\"", "true"),
            valid.replace("\"batch\":4", "\"batch\":4.9"),
            valid.replace("\"batch\":4", "\"batch\":null"),
            valid.replace("\"batch\":4", "\"batch\":4,\"batch\":1"),
            valid + " {}"))
      assertThrows(
          tools.jackson.core.JacksonException.class,
          () -> mapper.readValue(invalid, Application.ClaimRequest.class),
          invalid);
    assertThrows(
        tools.jackson.core.JacksonException.class,
        () -> mapper.readValue("0", Definition.Safety.class));
  }
}
