package io.github.cheshmakzanoon.portfolio.tenantshield;

import org.flywaydb.core.Flyway;

/** Separate migration entry point; the service runtime never receives these credentials. */
public final class StoreAdmin {
  private StoreAdmin() {}

  public static void main(String[] args) {
    if (args.length != 1 || !args[0].equals("migrate"))
      throw new IllegalArgumentException("usage: StoreAdmin migrate");
    Flyway.configure()
        .dataSource(
            required("TENANTSHIELD_MIGRATION_URL"),
            required("TENANTSHIELD_MIGRATION_USER"),
            required("TENANTSHIELD_MIGRATION_PASSWORD"))
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name);
    return value;
  }
}
