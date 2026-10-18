package io.github.cheshmakzanoon.portfolio.clearledger;

import java.sql.*;
import java.util.*;

/** Per-event membership, never sequence MAX: an earlier allocated ID can commit later. */
public final class Projector {
  private final LedgerStore store;

  public Projector(LedgerStore s) {
    store = s;
  }

  public UUID active() {
    return store.tx(
        c ->
            UUID.fromString(
                LedgerStore.query(c, "SELECT generation FROM ledger.projection_head")
                    .getFirst()
                    .get("generation")));
  }

  public int drain(UUID generation) {
    int n = 0;
    while (true) {
      int batch =
          store.tx(
              c -> {
                var events =
                    LedgerStore.query(
                        c,
                        "SELECT e.id,e.tenant_id,e.journal_id FROM ledger.outbox_event e WHERE NOT EXISTS(SELECT FROM ledger.projection_checkpoint p WHERE p.generation=? AND p.event_id=e.id) ORDER BY e.id LIMIT 100",
                        generation);
                int count = 0;
                for (var e : events) {
                  int inserted =
                      LedgerStore.execute(
                          c,
                          "INSERT INTO ledger.projection_checkpoint(generation,event_id) VALUES(?,?) ON CONFLICT DO NOTHING",
                          generation,
                          Long.valueOf(e.get("id")));
                  if (inserted == 0) continue;
                  LedgerStore.execute(
                      c,
                      "INSERT INTO ledger.reporting_balance SELECT ?,tenant_id,account_id,sum(CASE side WHEN 'DEBIT' THEN amount ELSE 0 END),sum(CASE side WHEN 'CREDIT' THEN amount ELSE 0 END) FROM ledger.posting WHERE tenant_id=? AND journal_id=? GROUP BY tenant_id,account_id ON CONFLICT(generation,tenant_id,account_id) DO UPDATE SET debits=ledger.reporting_balance.debits+excluded.debits,credits=ledger.reporting_balance.credits+excluded.credits",
                      generation,
                      UUID.fromString(e.get("tenant_id")),
                      UUID.fromString(e.get("journal_id")));
                  count++;
                }
                return count;
              });
      n += batch;
      if (batch == 0) return n;
    }
  }

  public long pending() {
    return store.tx(
        c ->
            Long.valueOf(
                LedgerStore.query(
                        c,
                        "SELECT count(*) AS n FROM ledger.outbox_event e WHERE NOT EXISTS(SELECT FROM ledger.projection_checkpoint p JOIN ledger.projection_head h ON h.generation=p.generation WHERE p.event_id=e.id)")
                    .getFirst()
                    .get("n")));
  }

  private static UUID generationOn(Connection c) throws SQLException {
    return UUID.fromString(
        LedgerStore.query(c, "SELECT generation FROM ledger.projection_head")
            .getFirst()
            .get("generation"));
  }

  private static long pendingOn(Connection c, UUID generation, UUID tenant) throws SQLException {
    return Long.parseLong(
        LedgerStore.query(
                c,
                "SELECT count(*) AS n FROM ledger.outbox_event e WHERE e.tenant_id=? AND NOT EXISTS(SELECT FROM ledger.projection_checkpoint p WHERE p.generation=? AND p.event_id=e.id)",
                tenant,
                generation)
            .getFirst()
            .get("n"));
  }

  public Map<String, Object> freshness(UUID tenant) {
    return store.tx(
        c -> {
          var generation = generationOn(c);
          return Map.of(
              "generation",
              generation,
              "pendingEvents",
              pendingOn(c, generation, tenant),
              "authoritative",
              false,
              "snapshot",
              LedgerStore.query(c, "SELECT pg_current_snapshot() AS snapshot")
                  .getFirst()
                  .get("snapshot"));
        });
  }

  public Map<String, Object> report(UUID tenant, UUID account) {
    return store.tx(
        c -> {
          var generation = generationOn(c);
          var rows =
              LedgerStore.query(
                  c,
                  "SELECT a.id,a.currency,a.normal_side,coalesce(r.debits,0)::text AS debits_minor,coalesce(r.credits,0)::text AS credits_minor FROM ledger.account a LEFT JOIN ledger.reporting_balance r ON r.tenant_id=a.tenant_id AND r.account_id=a.id AND r.generation=? WHERE a.tenant_id=? AND a.id=?",
                  generation,
                  tenant,
                  account);
          return Map.of(
              "generation",
              generation,
              "authoritative",
              false,
              "pendingEvents",
              pendingOn(c, generation, tenant),
              "balances",
              rows,
              "snapshot",
              LedgerStore.query(c, "SELECT pg_current_snapshot() AS snapshot")
                  .getFirst()
                  .get("snapshot"));
        });
  }

  public UUID replay() {
    var shadow = UUID.randomUUID();
    drain(shadow);
    store.tx(
        c -> {
          LedgerStore.query(c, "SELECT ledger.promote_shadow(?)", shadow);
          return null;
        });
    return shadow;
  }

  public static void main(String[] args) throws Exception {
    var s =
        new LedgerStore(
            System.getenv()
                .getOrDefault("LEDGER_URL", "jdbc:postgresql://localhost:18101/clearledger"),
            "clearledger_runtime",
            System.getenv().getOrDefault("LEDGER_PASSWORD", "local-only-clearledger"));
    var p = new Projector(s);
    if (args.length > 0 && args[0].equals("replay")) {
      System.out.println(p.replay());
      return;
    }
    do {
      p.drain(p.active());
      Thread.sleep(500);
    } while (true);
  }
}
