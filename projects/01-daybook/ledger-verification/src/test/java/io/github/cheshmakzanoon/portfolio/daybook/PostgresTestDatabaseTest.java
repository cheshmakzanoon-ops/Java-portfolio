package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgresTestDatabaseTest {
  private static Map<String, String> settings(String url, String user, String password) {
    Map<String, String> map = new HashMap<>();
    if (url != null) map.put("DAYBOOK_TEST_JDBC_URL", url);
    if (user != null) map.put("DAYBOOK_TEST_DB_USER", user);
    if (password != null) map.put("DAYBOOK_TEST_DB_PASSWORD", password);
    return map;
  }

  @Test
  void absentSettingsSelectThePinnedContainer() {
    assertNull(PostgresTestDatabase.localConfiguration(Map.of()));
  }

  @Test
  void partialSettingsFailInsteadOfFallingBackOrSkipping() {
    String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
    for (Map<String, String> partial :
        java.util.List.of(
            settings(url, null, null),
            settings(null, "postgres", "secret"),
            settings(url, "postgres", null),
            settings(url, "  ", "secret"),
            settings(url, "postgres", "")))
      assertThrows(
          IllegalArgumentException.class, () -> PostgresTestDatabase.localConfiguration(partial));
  }

  @Test
  void onlyExplicitLoopbackEndpointsWithoutParametersAreAccepted() {
    for (String valid :
        java.util.List.of(
            "jdbc:postgresql://127.0.0.1:5432/postgres",
            "jdbc:postgresql://localhost:55418/maintenance_db",
            "jdbc:postgresql://[::1]:5432/postgres")) {
      var local = PostgresTestDatabase.localConfiguration(settings(valid, "postgres", "secret"));
      assertEquals(valid, local.url());
      assertFalse(local.toString().contains("secret"), "credentials must be redacted");
    }
    for (String invalid :
        java.util.List.of(
            "jdbc:postgresql://db.example.com:5432/postgres",
            "jdbc:postgresql://10.0.0.5:5432/postgres",
            "jdbc:postgresql://127.0.0.1/postgres",
            "jdbc:postgresql://127.0.0.1:5432/postgres?user=other",
            "jdbc:postgresql://user:pw@127.0.0.1:5432/postgres",
            "jdbc:postgresql://127.0.0.1:5432/",
            "jdbc:postgresql://127.0.0.1:5432/1bad",
            "jdbc:postgresql://127.0.0.1:99999/postgres",
            "jdbc:postgresql://127.0.0.1:0/postgres",
            "jdbc:mysql://127.0.0.1:5432/postgres"))
      assertThrows(
          IllegalArgumentException.class,
          () -> PostgresTestDatabase.localConfiguration(settings(invalid, "postgres", "secret")),
          invalid);
  }

  @Test
  void derivedEndpointPartsMatchTheUrl() {
    var local =
        PostgresTestDatabase.localConfiguration(
            settings("jdbc:postgresql://[::1]:55418/maintenance", "u", "p"));
    assertEquals("[::1]", local.host());
    assertEquals("55418", local.port());
    assertEquals("maintenance", local.maintenanceDatabase());
  }
}
