package io.github.cheshmakzanoon.portfolio.latearrival;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class QueryReadinessTest {
  @Test
  void queryOnlyProcessCannotReportReadyWhenItsStoreIsUnavailable() {
    var store = new PostgresStore("jdbc:no-such-pulse-driver:readiness", "unused", "unused");
    var processor = new PulseApplication.Processor(store);
    assertEquals(true, processor.metrics().get("healthy"));
    var response = new QueryApi(store, processor, new MockEnvironment()).health();
    assertEquals(503, response.getStatusCode().value());
    assertNotNull(response.getBody());
    assertEquals(false, response.getBody().get("storeAvailable"));
    assertEquals(false, response.getBody().get("healthy"));
    assertFalse(response.getBody().toString().contains("jdbc:"));
  }
}
