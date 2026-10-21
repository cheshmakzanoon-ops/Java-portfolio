package io.github.cheshmakzanoon.portfolio.handoff;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The pinned container is the default. Explicit native PostgreSQL uses a fresh, owned schema;
 * missing Docker or malformed local settings never skip or silently replace a test.
 */
final class PostgresTestDatabase {
  private PostgreSQLContainer container;
  private Local configuration;
  private String schema;
  private String jdbcUrl;
  private String username;
  private String password;

  record Local(String url, String username, String password) {
    @Override
    public String toString() {
      return "Local[disposable PostgreSQL configuration; credentials redacted]";
    }
  }

  static Local localConfiguration(Map<String, String> environment) {
    String url = environment.get("HANDOFF_TEST_JDBC_URL");
    String username = environment.get("HANDOFF_TEST_DB_USER");
    String password = environment.get("HANDOFF_TEST_DB_PASSWORD");
    if (url == null && username == null && password == null) return null;
    if (url == null
        || username == null
        || username.isBlank()
        || password == null
        || password.isEmpty())
      throw new IllegalArgumentException("All three native PostgreSQL test settings are required");
    // A native test adapter cannot point at a remote service, accept JDBC URL credentials,
    // or inherit a caller-selected schema that the integration fixture might truncate.
    if (!url.matches(
        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/[a-zA-Z][a-zA-Z0-9_-]{0,62}"))
      throw new IllegalArgumentException(
          "Native PostgreSQL tests require an explicit loopback host, port and disposable database");
    int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException("Native PostgreSQL test port is invalid");
    return new Local(url, username, password);
  }

  void start() {
    if (jdbcUrl != null) throw new IllegalStateException("Test database already started");
    configuration = localConfiguration(System.getenv());
    if (configuration == null) {
      container =
          new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
                  .asCompatibleSubstituteFor("postgres"));
      container.start();
      jdbcUrl = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
      return;
    }
    username = configuration.username();
    password = configuration.password();
    String allocated = "handoff_it_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(configuration.url(), username, password);
        var statement = connection.createStatement()) {
      if (connection.getMetaData().getDatabaseMajorVersion() != 18)
        throw new IllegalStateException("Native integration requires PostgreSQL 18");
      statement.setQueryTimeout(15);
      statement.execute("CREATE SCHEMA " + allocated);
      schema = allocated;
      jdbcUrl = configuration.url() + "?currentSchema=" + schema;
    } catch (SQLException | RuntimeException failure) {
      try {
        stop();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw new IllegalStateException("Native PostgreSQL test setup failed", failure);
    }
  }

  String getJdbcUrl() {
    if (jdbcUrl == null) throw new IllegalStateException("Test database is not running");
    return jdbcUrl;
  }

  String getUsername() {
    return username;
  }

  String getPassword() {
    return password;
  }

  void stop() {
    if (container != null) {
      container.stop();
      container = null;
    }
    if (schema != null) {
      try (Connection connection =
              DriverManager.getConnection(configuration.url(), username, password);
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(15);
        // Only the randomly named schema successfully created by this fixture is removed.
        statement.execute("DROP SCHEMA " + schema + " CASCADE");
        schema = null;
      } catch (SQLException failure) {
        throw new IllegalStateException("Native PostgreSQL test schema cleanup failed", failure);
      }
    }
    jdbcUrl = null;
  }
}
