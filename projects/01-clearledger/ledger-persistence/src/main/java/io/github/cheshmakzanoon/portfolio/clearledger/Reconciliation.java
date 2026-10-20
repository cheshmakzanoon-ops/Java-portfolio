package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

public final class Reconciliation {
  private final LedgerStore store;

  public Reconciliation(LedgerStore s) {
    store = s;
  }

  public UUID ingest(UUID tenant, byte[] csv) {
    if (csv.length > 1048576) throw new IllegalArgumentException("IMPORT_BYTE_BOUND");
    final String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(csv))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("IMPORT_ENCODING", invalid);
    }
    // PostgreSQL text cannot represent NUL. Reject the file before starting a transaction.
    if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("IMPORT_NUL_BYTE");
    var lines = text.split("\\r?\\n", -1);
    if (lines.length - 1 - (lines[lines.length - 1].isEmpty() ? 1 : 0) > 5000)
      throw new IllegalArgumentException("IMPORT_ROW_BOUND");
    if (!lines[0].equals("source_id,reference,currency,amount"))
      throw new IllegalArgumentException("CSV_HEADER");
    String hash;
    try {
      hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return store.tx(
        c -> {
          var prior =
              LedgerStore.query(
                  c,
                  "SELECT id FROM ledger.statement_import WHERE tenant_id=? AND content_hash=?",
                  tenant,
                  hash);
          if (!prior.isEmpty()) return UUID.fromString(prior.getFirst().get("id"));
          var id = UUID.randomUUID();
          LedgerStore.execute(
              c,
              "INSERT INTO ledger.statement_import(tenant_id,id,content_hash) VALUES(?,?,?)",
              tenant,
              id,
              hash);
          var sources = new HashSet<String>();
          for (int i = 1; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            String source = "", ref = "", currency = "";
            BigInteger amount = null;
            String status = "UNMATCHED", reason = "";
            try {
              var cols = parseLine(lines[i]);
              if (cols.size() != 4) throw new IllegalArgumentException("COLUMN_COUNT");
              source = cols.get(0);
              ref = cols.get(1);
              currency = cols.get(2);
              if (source.isBlank() || source.length() > 120 || ref.length() > 120)
                throw new IllegalArgumentException("FIELD_BOUND");
              amount = Money.parse(currency, cols.get(3)).minor();
              if (amount.signum() == 0) throw new IllegalArgumentException("ZERO_AMOUNT");
              if (!sources.add(source)) {
                status = "DUPLICATE";
                reason = "DUPLICATE_SOURCE_ID";
              }
            } catch (IllegalArgumentException e) {
              status = "INVALID";
              reason = e.getMessage();
            }
            if (status.equals("UNMATCHED") && ref.isBlank()) reason = "MISSING_REFERENCE";
            LedgerStore.execute(
                c,
                "INSERT INTO ledger.statement_row VALUES(?,?,?,?,?,?,?,?,?)",
                tenant,
                id,
                i,
                source,
                ref,
                currency,
                amount,
                status,
                reason);
            if (status.equals("UNMATCHED") && !ref.isBlank()) {
              var candidates =
                  LedgerStore.query(
                      c,
                      "SELECT p.journal_id,p.line_no FROM ledger.posting p JOIN ledger.journal j ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE p.tenant_id=? AND j.reference=? AND j.currency=? AND CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END=? AND NOT EXISTS(SELECT FROM ledger.reconciliation_match m WHERE m.tenant_id=p.tenant_id AND m.journal_id=p.journal_id AND m.line_no=p.line_no)",
                      tenant,
                      ref,
                      currency,
                      amount);
              if (candidates.size() == 1) {
                var v = candidates.getFirst();
                LedgerStore.execute(
                    c,
                    "INSERT INTO ledger.reconciliation_match VALUES(?,?,?,?,?)",
                    tenant,
                    id,
                    i,
                    UUID.fromString(v.get("journal_id")),
                    Integer.valueOf(v.get("line_no")));
                LedgerStore.execute(
                    c,
                    "UPDATE ledger.statement_row SET status='MATCHED',reason='UNAMBIGUOUS_REFERENCE_CURRENCY_AMOUNT' WHERE tenant_id=? AND import_id=? AND row_no=?",
                    tenant,
                    id,
                    i);
              } else if (candidates.size() > 1)
                LedgerStore.execute(
                    c,
                    "UPDATE ledger.statement_row SET status='AMBIGUOUS',reason='MULTIPLE_CANDIDATES' WHERE tenant_id=? AND import_id=? AND row_no=?",
                    tenant,
                    id,
                    i);
            }
          }
          return id;
        });
  }

  static List<String> parseLine(String line) {
    var out = new ArrayList<String>();
    var b = new StringBuilder();
    boolean quoted = false, closed = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          b.append('"');
          i++;
        } else if (quoted) {
          quoted = false;
          closed = true;
        } else if (b.isEmpty() && !closed) quoted = true;
        else throw new IllegalArgumentException("MALFORMED_QUOTE");
      } else if (ch == ',' && !quoted) {
        out.add(b.toString());
        b.setLength(0);
        closed = false;
      } else {
        if (closed) throw new IllegalArgumentException("TRAILING_QUOTE_TEXT");
        b.append(ch);
      }
    }
    if (quoted) throw new IllegalArgumentException("UNCLOSED_QUOTE");
    out.add(b.toString());
    return out;
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
    if (actor == null
        || actor.isBlank()
        || reason == null
        || reason.isBlank()
        || evidence == null
        || evidence.isBlank()
        || reason.length() > 1000
        || evidence.length() > 4000) throw new IllegalArgumentException("DECISION_EVIDENCE");
    store.tx(
        c -> {
          var valid =
              LedgerStore.query(
                  c,
                  "SELECT s.status FROM ledger.statement_row s JOIN ledger.posting p ON p.tenant_id=s.tenant_id JOIN ledger.journal j ON j.tenant_id=p.tenant_id AND j.id=p.journal_id WHERE s.tenant_id=? AND s.import_id=? AND s.row_no=? AND p.journal_id=? AND p.line_no=? AND j.currency=s.currency AND CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END=s.amount AND s.status IN('UNMATCHED','AMBIGUOUS') FOR UPDATE OF s",
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

  static String safeCell(String raw) {
    String value = raw == null ? "" : raw;
    String probe = value.stripLeading();
    while (probe.startsWith("\uFEFF")) probe = probe.substring(1).stripLeading();
    if (!probe.isEmpty() && "=+-@".indexOf(probe.charAt(0)) >= 0
        || !value.isEmpty() && "\t\r".indexOf(value.charAt(0)) >= 0) return "'" + value;
    return value;
  }

  public String export(UUID tenant, UUID id) {
    var out = new StringBuilder("row_no,source_id,reference,currency,amount,status,reason\n");
    for (var r : rows(tenant, id)) {
      boolean first = true;
      for (var v : r.values()) {
        if (!first) out.append(',');
        first = false;
        String value = safeCell(v);
        out.append('"').append(value.replace("\"", "\"\"")).append('"');
      }
      out.append('\n');
    }
    return out.toString();
  }
}
