package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class LedgerPostgresFixture {
  PostgreSQLContainer pg;
  LedgerStore store, admin;
  UUID tenant, other, period, a, b;

  @BeforeAll
  void boot() {
    pg =
        new PostgreSQLContainer(
                org.testcontainers.utility.DockerImageName.parse(
                        "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("clearledger")
            .withUsername("postgres")
            .withPassword("local-only-admin");
    pg.start();
    LedgerStore.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    store = new LedgerStore(pg.getJdbcUrl(), "clearledger_runtime", "local-only-clearledger");
    admin = new LedgerStore(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  @AfterAll
  void stop() {
    try (var runtime = store;
        var privileged = admin) {
      // Close all pooled database connections before removing their database container.
    } finally {
      if (pg != null) pg.stop();
    }
  }

  @BeforeEach
  void seed() {
    tenant = UUID.randomUUID();
    other = UUID.randomUUID();
    period = UUID.randomUUID();
    admin.tx(
        c -> {
          LedgerStore.execute(c, "INSERT INTO ledger.tenant VALUES(?),(?)", tenant, other);
          LedgerStore.execute(
              c,
              "INSERT INTO ledger.accounting_period VALUES(?,?,DATE '2026-01-01',DATE '2026-12-31',false)",
              tenant,
              period);
          return null;
        });
    a = store.account(tenant, "CAD", Journal.Side.DEBIT, "cash");
    b = store.account(tenant, "CAD", Journal.Side.CREDIT, "sales");
  }

  Journal journal(int n, String ref) {
    return new Journal(
        period,
        LocalDate.of(2026, 1, 2),
        "CAD",
        ref,
        List.of(
            new Journal.Line(a, Journal.Side.DEBIT, BigInteger.valueOf(n)),
            new Journal.Line(b, Journal.Side.CREDIT, BigInteger.valueOf(n))));
  }

  long count(String table) {
    return admin.tx(
        c ->
            Long.valueOf(
                LedgerStore.query(
                        c, "SELECT count(*) n FROM ledger." + table + " WHERE tenant_id=?", tenant)
                    .getFirst()
                    .get("n")));
  }
}
