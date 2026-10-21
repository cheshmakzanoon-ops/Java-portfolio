package io.github.cheshmakzanoon.portfolio.durableflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgresTestDatabaseTest {
  @Test
  void noSettingsMeansThePinnedContainerAndPartialSettingsAreErrors() {
    assertNull(PostgresTestDatabase.localConfiguration(Map.of()));
    var complete = local("jdbc:postgresql://127.0.0.1:25432/durableflow_test");
    for (String key : complete.keySet()) {
      var partial = new HashMap<>(complete);
      partial.remove(key);
      assertThrows(
          IllegalArgumentException.class, () -> PostgresTestDatabase.localConfiguration(partial));
      partial.put(key, "");
      assertThrows(
          IllegalArgumentException.class, () -> PostgresTestDatabase.localConfiguration(partial));
    }
  }

  @Test
  void onlyExplicitLoopbackPostgresEndpointsWithoutCallerSelectedSchemasAreAllowed() {
    for (String host : List.of("127.0.0.1", "localhost", "[::1]")) {
      String url = "jdbc:postgresql://" + host + ":25432/disposable_test";
      assertEquals(url, PostgresTestDatabase.localConfiguration(local(url)).url());
    }
    for (String invalid :
        List.of(
            "jdbc:postgresql://db.example:25432/test",
            "jdbc:postgresql://127.0.0.1/test",
            "jdbc:postgresql://127.0.0.1:0/test",
            "jdbc:postgresql://127.0.0.1:65536/test",
            "jdbc:postgresql://127.0.0.1:25432/test?currentSchema=public",
            "jdbc:postgresql://127.0.0.1:25432/test?user=admin",
            "jdbc:postgresql://user@127.0.0.1:25432/test",
            "jdbc:postgresql://127.0.0.1:25432/",
            "jdbc:postgresql://127.0.0.1:25432/test/other",
            "jdbc:postgresql://127.0.0.1:25432/test#fragment",
            "jdbc:postgresql://127.0.0.1:25432/" + "x".repeat(64),
            "jdbc:h2:mem:test"))
      assertThrows(
          IllegalArgumentException.class,
          () -> PostgresTestDatabase.localConfiguration(local(invalid)),
          invalid);
  }

  @Test
  void configurationDiagnosticsNeverExposeCredentials() {
    var configuration =
        PostgresTestDatabase.localConfiguration(local("jdbc:postgresql://localhost:25432/test"));
    assertFalse(configuration.toString().contains("synthetic-user"));
    assertFalse(configuration.toString().contains("synthetic-password"));
    assertFalse(configuration.toString().contains("jdbc:"));
    var bad = local("jdbc:postgresql://remote.example:25432/test?password=synthetic-password");
    var failure =
        assertThrows(
            IllegalArgumentException.class, () -> PostgresTestDatabase.localConfiguration(bad));
    assertFalse(failure.getMessage().contains("synthetic-password"));
  }

  private Map<String, String> local(String url) {
    return Map.of(
        "DURABLEFLOW_TEST_JDBC_URL",
        url,
        "DURABLEFLOW_TEST_DB_USER",
        "synthetic-user",
        "DURABLEFLOW_TEST_DB_PASSWORD",
        "synthetic-password");
  }
}
