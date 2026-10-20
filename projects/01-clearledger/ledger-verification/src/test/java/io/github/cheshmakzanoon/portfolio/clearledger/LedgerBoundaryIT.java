package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** Database contract upgrades and forced projection/reconciliation interleavings. */
class LedgerBoundaryIT extends LedgerPostgresFixture {
  @Test
  void privilegedEntryPointRejectsNullDigestAndInvalidOperationBeforeReceiptLookup() {
    var intent = journal(10, "contract");
    var existing = store.post(tenant, "retained", intent);
    String lines =
        "[{\"account\":\""
            + a
            + "\",\"side\":\"DEBIT\",\"amount\":\"10\"},"
            + "{\"account\":\""
            + b
            + "\",\"side\":\"CREDIT\",\"amount\":\"10\"}]";
    Object[] valid = {
      tenant,
      "retained",
      intent.digest(),
      UUID.randomUUID(),
      period,
      intent.date(),
      "CAD",
      intent.reference(),
      lines,
      null,
      "POST"
    };
    var invalid = new ArrayList<Object[]>();
    for (int index : List.of(0, 1, 2, 3, 4, 5, 6, 7, 10)) {
      var arguments = valid.clone();
      arguments[index] = null;
      invalid.add(arguments);
    }
    for (Object[] change :
        List.of(
            new Object[] {1, "   "},
            new Object[] {2, "f".repeat(63)},
            new Object[] {10, "UPSERT"},
            new Object[] {10, "REVERSE"},
            new Object[] {9, existing})) {
      var arguments = valid.clone();
      arguments[(Integer) change[0]] = change[1];
      invalid.add(arguments);
    }
    for (var arguments : invalid) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  store.tx(
                      c ->
                          LedgerStore.query(
                              c,
                              "SELECT ledger.post_journal(?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                              arguments)));
      assertEquals("INPUT_BOUND", failure.code);
      assertFalse(failure.temporary);
    }
    store.close(tenant, period);
    assertEquals(existing, store.post(tenant, "retained", intent));
    assertEquals(1, count("journal"));
    assertEquals(2, count("posting"));
    assertEquals(1, count("idempotency_receipt"));
    assertEquals(1, count("outbox_event"));
    assertEquals(
        "10",
        store.balances(tenant).stream()
            .filter(r -> r.get("id").equals(a.toString()))
            .findFirst()
            .orElseThrow()
            .get("debits"));
  }

  @Test
  void migrationPreservesV2ReceiptsBalancesAndRuntimePrivileges() throws Exception {
    String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String url = pg.getJdbcUrl().replace("/" + pg.getDatabaseName(), "/" + database);
    try (var connection =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
    try {
      org.flywaydb.core.Flyway.configure()
          .dataSource(url, pg.getUsername(), pg.getPassword())
          .locations("classpath:db/migration")
          .target("2")
          .load()
          .migrate();
      try (var privileged = new LedgerStore(url, pg.getUsername(), pg.getPassword());
          var runtime = new LedgerStore(url, "clearledger_runtime", "local-only-clearledger")) {
        privileged.tx(
            c -> {
              LedgerStore.execute(c, "INSERT INTO ledger.tenant VALUES(?)", tenant);
              LedgerStore.execute(
                  c,
                  "INSERT INTO ledger.accounting_period VALUES(?,?,DATE '2026-01-01',DATE '2026-12-31',false)",
                  tenant,
                  period);
              LedgerStore.execute(
                  c,
                  "INSERT INTO ledger.account VALUES(?,?,'CAD','DEBIT','cash'),(?,?,'CAD','CREDIT','sales')",
                  tenant,
                  a,
                  tenant,
                  b);
              return null;
            });
        var intent = journal(75, "legacy");
        var committed = runtime.post(tenant, "legacy-key", intent);
        var before = runtime.balances(tenant);
        LedgerStore.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(committed, runtime.post(tenant, "legacy-key", intent));
        assertEquals(before, runtime.balances(tenant));
        assertTrue(SourceAudit.journals(privileged).isEmpty());
        assertThrows(
            LedgerStore.LedgerException.class,
            () ->
                runtime.tx(
                    c ->
                        LedgerStore.execute(
                            c, "DELETE FROM ledger.posting WHERE tenant_id=?", tenant)));
      }
    } finally {
      try (var connection =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP DATABASE " + database);
      }
    }
  }

  @Test
  void concurrentProjectorsApplyEachEventExactlyOnceAfterAForcedLockInterleaving()
      throws Exception {
    var projector = new Projector(store);
    var generation = UUID.randomUUID();
    projector.drain(generation);
    var original = store.post(tenant, "first", journal(100, "first"));
    store.post(tenant, "second", journal(250, "second"));
    store.reverse(tenant, original, "reverse", LocalDate.of(2026, 1, 2));
    try (var lock =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var threads = Executors.newFixedThreadPool(2)) {
      lock.setAutoCommit(false);
      LedgerStore.execute(lock, "LOCK TABLE ledger.reporting_balance IN ACCESS EXCLUSIVE MODE");
      var first = threads.submit(() -> projector.drain(generation));
      var second = threads.submit(() -> new Projector(store).drain(generation));
      try {
        awaitBlockedTransactions(2);
      } finally {
        lock.rollback();
      }
      assertEquals(3, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
    }
    var rows =
        admin.tx(
            c ->
                LedgerStore.query(
                    c,
                    "SELECT account_id,debits::text,credits::text FROM ledger.reporting_balance WHERE generation=? AND tenant_id=? ORDER BY account_id",
                    generation,
                    tenant));
    var cash =
        rows.stream()
            .filter(r -> r.get("account_id").equals(a.toString()))
            .findFirst()
            .orElseThrow();
    var revenue =
        rows.stream()
            .filter(r -> r.get("account_id").equals(b.toString()))
            .findFirst()
            .orElseThrow();
    assertEquals("350", cash.get("debits"));
    assertEquals("100", cash.get("credits"));
    assertEquals("100", revenue.get("debits"));
    assertEquals("350", revenue.get("credits"));
    assertEquals(0, projector.drain(generation));
    assertEquals(
        "3",
        admin
            .tx(
                c ->
                    LedgerStore.query(
                        c,
                        "SELECT count(*) n FROM ledger.projection_checkpoint p JOIN ledger.outbox_event e ON e.id=p.event_id WHERE p.generation=? AND e.tenant_id=?",
                        generation,
                        tenant))
            .getFirst()
            .get("n"));
  }

  @Test
  void projectionFailureRollsBackMembershipAndDeltasBeforeRetry() {
    var projector = new Projector(store);
    var generation = UUID.randomUUID();
    projector.drain(generation);
    store.post(tenant, "fault", journal(125, "fault"));
    admin.tx(
        c -> {
          LedgerStore.execute(
              c,
              "CREATE FUNCTION ledger.fail_projection_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'INJECTED_PROJECTION_FAILURE' USING ERRCODE='P0422'; END $$");
          LedgerStore.execute(
              c,
              "CREATE TRIGGER fail_projection_test BEFORE INSERT ON ledger.reporting_balance FOR EACH ROW EXECUTE FUNCTION ledger.fail_projection_test()");
          return null;
        });
    try {
      var failure =
          assertThrows(LedgerStore.LedgerException.class, () -> projector.drain(generation));
      assertEquals("INJECTED_PROJECTION_FAILURE", failure.code);
      assertEquals(
          "0",
          admin
              .tx(
                  c ->
                      LedgerStore.query(
                          c,
                          "SELECT count(*) n FROM ledger.projection_checkpoint p JOIN ledger.outbox_event e ON e.id=p.event_id WHERE p.generation=? AND e.tenant_id=?",
                          generation,
                          tenant))
              .getFirst()
              .get("n"));
      assertEquals(
          "0",
          admin
              .tx(
                  c ->
                      LedgerStore.query(
                          c,
                          "SELECT count(*) n FROM ledger.reporting_balance WHERE generation=? AND tenant_id=?",
                          generation,
                          tenant))
              .getFirst()
              .get("n"));
    } finally {
      admin.tx(
          c -> {
            LedgerStore.execute(c, "DROP TRIGGER fail_projection_test ON ledger.reporting_balance");
            LedgerStore.execute(c, "DROP FUNCTION ledger.fail_projection_test()");
            return null;
          });
    }
    assertEquals(1, projector.drain(generation));
    assertEquals(
        "125",
        admin
            .tx(
                c ->
                    LedgerStore.query(
                        c,
                        "SELECT debits::text FROM ledger.reporting_balance WHERE generation=? AND tenant_id=? AND account_id=?",
                        generation,
                        tenant,
                        a))
            .getFirst()
            .get("debits"));
  }

  @Test
  void competingManualDecisionsCannotConsumeOnePostingTwice() throws Exception {
    var reconciliation = new Reconciliation(store);
    var imported =
        reconciliation.ingest(
            tenant,
            "source_id,reference,currency,amount\nfirst,review,CAD,1.00\nsecond,review,CAD,1.00\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var candidate = store.post(tenant, "candidate", journal(100, "different-reference"));
    try (var lock =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var threads = Executors.newFixedThreadPool(2)) {
      lock.setAutoCommit(false);
      LedgerStore.execute(lock, "LOCK TABLE ledger.reconciliation_match IN ACCESS EXCLUSIVE MODE");
      var first = threads.submit(() -> resolveRace(reconciliation, imported, candidate, 1));
      var second = threads.submit(() -> resolveRace(reconciliation, imported, candidate, 2));
      try {
        awaitBlockedTransactions(2);
      } finally {
        lock.rollback();
      }
      assertEquals(1, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
    }
    assertEquals(1, count("reconciliation_match"));
    assertEquals(1, count("reconciliation_decision"));
    assertEquals(
        1,
        reconciliation.rows(tenant, imported).stream()
            .filter(r -> r.get("status").equals("MATCHED"))
            .count());
  }

  int resolveRace(Reconciliation reconciliation, UUID imported, UUID candidate, int row) {
    try {
      reconciliation.resolve(
          tenant, imported, row, candidate, 1, "reviewer", "verified", "synthetic evidence");
      return 1;
    } catch (IllegalArgumentException rejected) {
      assertEquals("INVALID_MANUAL_CANDIDATE", rejected.getMessage());
      return 0;
    }
  }

  void awaitBlockedTransactions(int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      long blocked =
          admin.tx(
              c ->
                  Long.parseLong(
                      LedgerStore.query(
                              c,
                              "SELECT count(*) n FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()")
                          .getFirst()
                          .get("n")));
      if (blocked >= expected) return;
      Thread.sleep(10);
    }
    fail("Expected two real database transactions blocked before releasing the test lock");
  }
}
