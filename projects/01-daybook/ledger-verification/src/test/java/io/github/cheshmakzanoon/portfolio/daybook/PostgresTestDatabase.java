package io.github.cheshmakzanoon.portfolio.daybook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The pinned container is the default. Explicit native PostgreSQL gets a fresh, randomly named
 * database that this fixture creates and drops; missing Docker or malformed local settings never
 * skip a test or silently select another backend.
 */
final class PostgresTestDatabase {
  private static final String IMAGE =
      "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873";
  private static final int MINIMUM_MAJOR = 16;

  private PostgreSQLContainer container;
  private Local configuration;
  private String database;
  private String jdbcUrl;
  private String username;
  private String password;

  record Local(String url, String username, String password) {
    @Override
    public String toString() {
      return "Local[disposable PostgreSQL configuration; credentials redacted]";
    }

    String host() {
      return url.substring("jdbc:postgresql://".length(), url.lastIndexOf(':'));
    }

    String port() {
      return url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/'));
    }

    String maintenanceDatabase() {
      return url.substring(url.lastIndexOf('/') + 1);
    }
  }

  static Local localConfiguration(Map<String, String> environment) {
    String url = environment.get("DAYBOOK_TEST_JDBC_URL");
    String username = environment.get("DAYBOOK_TEST_DB_USER");
    String password = environment.get("DAYBOOK_TEST_DB_PASSWORD");
    if (url == null && username == null && password == null) return null;
    if (url == null
        || username == null
        || username.isBlank()
        || password == null
        || password.isEmpty())
      throw new IllegalArgumentException("All three native PostgreSQL test settings are required");
    // A native run may only target a loopback server through an explicit maintenance database,
    // without URL parameters or credentials. The fixture then creates its own database.
    if (!url.matches(
        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/[a-zA-Z][a-zA-Z0-9_-]{0,62}"))
      throw new IllegalArgumentException(
          "Native PostgreSQL tests require an explicit loopback host, port and database name");
    int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
    if (port < 1 || port > 65535)
      throw new IllegalArgumentException("Native PostgreSQL test port is invalid");
    return new Local(url, username, password);
  }

  boolean isNative() {
    return configuration != null;
  }

  void start() {
    if (jdbcUrl != null) throw new IllegalStateException("Test database already started");
    configuration = localConfiguration(System.getenv());
    if (configuration == null) {
      container =
          new PostgreSQLContainer(
                  DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
              .withDatabaseName("daybook")
              .withUsername("postgres")
              .withPassword("local-only-admin");
      container.start();
      jdbcUrl = container.getJdbcUrl();
      username = container.getUsername();
      password = container.getPassword();
      database = container.getDatabaseName();
      return;
    }
    username = configuration.username();
    password = configuration.password();
    String allocated = "daybook_it_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(configuration.url(), username, password);
        var statement = connection.createStatement()) {
      int major = connection.getMetaData().getDatabaseMajorVersion();
      if (major < MINIMUM_MAJOR)
        throw new IllegalStateException(
            "Native integration requires PostgreSQL " + MINIMUM_MAJOR + "+");
      System.out.println(
          "Daybook native PostgreSQL "
              + connection.getMetaData().getDatabaseProductVersion()
              + " (the pinned container is the reference environment)");
      statement.setQueryTimeout(30);
      statement.execute("CREATE DATABASE " + allocated);
      database = allocated;
      jdbcUrl = databaseUrl(allocated);
    } catch (SQLException | RuntimeException failure) {
      try {
        stop();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw new IllegalStateException("Native PostgreSQL test setup failed", failure);
    }
  }

  private String databaseUrl(String name) {
    return "jdbc:postgresql://" + configuration.host() + ":" + configuration.port() + "/" + name;
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

  String getDatabaseName() {
    return database;
  }

  /** An independent, empty database for restore checks. */
  Restored createRestoreTarget() throws Exception {
    if (container == null) {
      String name = "daybook_restore_" + UUID.randomUUID().toString().replace("-", "");
      try (Connection connection =
              DriverManager.getConnection(configuration.url(), username, password);
          var statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE " + name);
      }
      return new Restored(name, databaseUrl(name), username, password, null);
    }
    PostgreSQLContainer clean =
        new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("restored")
            .withUsername("postgres")
            .withPassword("restore-only");
    clean.start();
    return new Restored("restored", clean.getJdbcUrl(), "postgres", "restore-only", clean);
  }

  /** A logical dump of the test database, restored into a target by the matching tools. */
  void dumpInto(Restored target) throws Exception {
    if (container != null) {
      var dump =
          container.execInContainer("pg_dump", "-U", "postgres", "-d", "daybook", "--no-owner");
      if (dump.getExitCode() != 0) throw new IllegalStateException(dump.getStderr());
      var provisioned =
          target.container.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              "postgres",
              "-d",
              "restored",
              "-c",
              "CREATE ROLE daybook_runtime LOGIN PASSWORD 'local-only-daybook'");
      if (provisioned.getExitCode() != 0) throw new IllegalStateException(provisioned.getStderr());
      target.container.copyFileToContainer(
          org.testcontainers.images.builder.Transferable.of(dump.getStdout()), "/tmp/backup.sql");
      var restored =
          target.container.execInContainer(
              "psql",
              "-v",
              "ON_ERROR_STOP=1",
              "-U",
              "postgres",
              "-d",
              "restored",
              "-f",
              "/tmp/backup.sql");
      if (restored.getExitCode() != 0) throw new IllegalStateException(restored.getStderr());
      return;
    }
    // Native dump tools must match the server's major version, so they are named explicitly.
    String tools = System.getenv("DAYBOOK_TEST_PG_BIN");
    if (tools == null || tools.isBlank())
      throw new IllegalStateException(
          "Native backup/restore needs pg_dump and psql of the server's major version in "
              + "DAYBOOK_TEST_PG_BIN; the test is not skipped");
    Path directory = Path.of(tools);
    Path file = Files.createTempFile("daybook-backup", ".sql");
    try {
      run(
          List.of(
              directory.resolve("pg_dump").toString(),
              "-h",
              configuration.host(),
              "-p",
              configuration.port(),
              "-U",
              username,
              "-d",
              database,
              "--no-owner",
              "-f",
              file.toString()));
      run(
          List.of(
              directory.resolve("psql").toString(),
              "-v",
              "ON_ERROR_STOP=1",
              "-h",
              configuration.host(),
              "-p",
              configuration.port(),
              "-U",
              username,
              "-d",
              target.database(),
              "-f",
              file.toString()));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private void run(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("PGPASSWORD", password);
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0)
      throw new IllegalStateException(command.getFirst() + " failed: " + output);
  }

  /** A restore destination; closing it removes only what this fixture created. */
  record Restored(
      String database,
      String jdbcUrl,
      String username,
      String password,
      PostgreSQLContainer container)
      implements AutoCloseable {
    @Override
    public void close() {
      if (container != null) container.stop();
    }
  }

  void dropRestoreTarget(Restored target) throws SQLException {
    if (container != null) return;
    try (Connection connection =
            DriverManager.getConnection(configuration.url(), username, password);
        var statement = connection.createStatement()) {
      statement.execute("DROP DATABASE IF EXISTS " + target.database() + " WITH (FORCE)");
    }
  }

  void stop() {
    if (container != null) {
      container.stop();
      container = null;
    } else if (database != null && configuration != null) {
      try (Connection connection =
              DriverManager.getConnection(configuration.url(), username, password);
          var statement = connection.createStatement()) {
        statement.setQueryTimeout(30);
        // Only the randomly named database this fixture created is removed.
        statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
      } catch (SQLException failure) {
        throw new IllegalStateException("Native PostgreSQL test database cleanup failed", failure);
      }
    }
    database = null;
    jdbcUrl = null;
  }
}
