package io.github.cheshmakzanoon.portfolio.daybook;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Reconciliation {
  private final LedgerStore store;

  public Reconciliation(LedgerStore s) {
    store = s;
  }

  public UUID ingest(UUID tenant, byte[] csv) {
    var prepared = StatementCsv.prepare(csv);
    return store.tx(
        c -> {
          var prior =
              LedgerStore.query(
                  c,
                  "SELECT id FROM ledger.statement_import WHERE tenant_id=? AND content_hash=?",
                  tenant,
                  prepared.contentHash());
          if (!prior.isEmpty()) return UUID.fromString(prior.getFirst().get("id"));
          var id = UUID.randomUUID();
          LedgerStore.execute(
              c,
              "INSERT INTO ledger.statement_import(tenant_id,id,content_hash) VALUES(?,?,?)",
              tenant,
              id,
              prepared.contentHash());
          for (var row : prepared.rows()) {
            LedgerStore.execute(
                c,
                "INSERT INTO ledger.statement_row(tenant_id,import_id,row_no,source_id,reference,currency,amount,status,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                tenant,
                id,
                row.number(),
                row.source(),
                row.reference(),
                row.currency(),
                row.amount(),
                row.status().name(),
                row.reason());
            if (row.status() != StatementCsv.Status.UNMATCHED || row.reference().isBlank())
              continue;
            // Only zero, one, or multiple matches matter; never materialize an unbounded set.
            var candidates =
                LedgerStore.query(
                    c,
                    "SELECT p.journal_id,p.line_no FROM ledger.posting p JOIN ledger.journal j ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE p.tenant_id=? AND j.reference=? AND j.currency=? AND CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END=? AND NOT EXISTS(SELECT FROM ledger.reconciliation_match m WHERE m.tenant_id=p.tenant_id AND m.journal_id=p.journal_id AND m.line_no=p.line_no) LIMIT 2",
                    tenant,
                    row.reference(),
                    row.currency(),
                    row.amount());
            if (candidates.size() == 1) {
              var candidate = candidates.getFirst();
              LedgerStore.execute(
                  c,
                  "INSERT INTO ledger.reconciliation_match(tenant_id,import_id,row_no,journal_id,line_no) VALUES(?,?,?,?,?)",
                  tenant,
                  id,
                  row.number(),
                  UUID.fromString(candidate.get("journal_id")),
                  Integer.valueOf(candidate.get("line_no")));
              LedgerStore.execute(
                  c,
                  "UPDATE ledger.statement_row SET status='MATCHED',reason='UNAMBIGUOUS_REFERENCE_CURRENCY_AMOUNT' WHERE tenant_id=? AND import_id=? AND row_no=?",
                  tenant,
                  id,
                  row.number());
            } else if (candidates.size() > 1) {
              LedgerStore.execute(
                  c,
                  "UPDATE ledger.statement_row SET status='AMBIGUOUS',reason='MULTIPLE_CANDIDATES' WHERE tenant_id=? AND import_id=? AND row_no=?",
                  tenant,
                  id,
                  row.number());
            }
          }
          return id;
        });
  }

  public List<Map<String, String>> rows(UUID tenant, UUID id) {
    return store.tx(
        c ->
            LedgerStore.query(
                c,
                "SELECT row_no,source_id,reference,currency,amount::text,status,reason FROM ledger.statement_row WHERE tenant_id=? AND import_id=? ORDER BY row_no",
                tenant,
                id));
  }

  public void resolve(
      UUID tenant,
      UUID id,
      int row,
      UUID journal,
      int line,
      String actor,
      String reason,
      String evidence) {
    if (!LedgerText.valid(actor)
        || actor.isBlank()
        || actor.length() > 255
        || !LedgerText.valid(reason)
        || reason.isBlank()
        || !LedgerText.valid(evidence)
        || evidence.isBlank()
        || reason.length() > 1000
        || evidence.length() > 4000) throw new IllegalArgumentException("DECISION_EVIDENCE");
    store.tx(
        c -> {
          var valid =
              LedgerStore.query(
                  c,
                  "SELECT s.status FROM ledger.statement_row s JOIN ledger.posting p ON p.tenant_id=s.tenant_id JOIN ledger.journal j ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE s.tenant_id=? AND s.import_id=? AND s.row_no=? AND p.journal_id=? AND p.line_no=? AND j.currency=s.currency AND CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END=s.amount AND s.status IN('UNMATCHED','AMBIGUOUS') AND NOT EXISTS(SELECT FROM ledger.reconciliation_match m WHERE m.tenant_id=p.tenant_id AND m.journal_id=p.journal_id AND m.line_no=p.line_no) FOR UPDATE OF s",
                  tenant,
                  id,
                  row,
                  journal,
                  line);
          if (valid.isEmpty()) throw new IllegalArgumentException("INVALID_MANUAL_CANDIDATE");
          LedgerStore.execute(
              c,
              "INSERT INTO ledger.reconciliation_match VALUES(?,?,?,?,?)",
              tenant,
              id,
              row,
              journal,
              line);
          LedgerStore.execute(
              c,
              "INSERT INTO ledger.reconciliation_decision(tenant_id,import_id,row_no,actor,reason,evidence) VALUES(?,?,?,?,?,?)",
              tenant,
              id,
              row,
              actor,
              reason,
              evidence);
          LedgerStore.execute(
              c,
              "UPDATE ledger.statement_row SET status='MATCHED',reason='MANUAL_REVIEW' WHERE tenant_id=? AND import_id=? AND row_no=?",
              tenant,
              id,
              row);
          return null;
        });
  }

  public String export(UUID tenant, UUID id) {
    return StatementCsv.write(rows(tenant, id));
  }
}
