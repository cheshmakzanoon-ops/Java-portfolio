package io.github.cheshmakzanoon.portfolio.daybook;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire shapes for the authoritative read endpoints. Amounts are exact strings, never numbers. */
final class ReadModels {
  private ReadModels() {}

  /** An account with cumulative debits and credits and its balance on the normal side. */
  static Map<String, Object> account(Map<String, String> row) {
    var currency = row.get("currency");
    var side = Journal.Side.valueOf(row.get("normal_side"));
    var debits = new BigInteger(row.get("debits"));
    var credits = new BigInteger(row.get("credits"));
    var net = debits.subtract(credits);
    var normal = side == Journal.Side.DEBIT ? net : net.negate();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", row.get("id"));
    out.put("name", row.get("name"));
    out.put("currency", currency);
    out.put("normalSide", side);
    out.put("debitsMinor", debits.toString());
    out.put("creditsMinor", credits.toString());
    out.put("normalBalanceMinor", normal.toString());
    out.put("normalBalance", TrialBalance.decimal(currency, normal));
    return out;
  }

  static Map<String, Object> period(Map<String, String> row) {
    var out = new LinkedHashMap<String, Object>();
    out.put("id", row.get("id"));
    out.put("start", row.get("start_date"));
    out.put("end", row.get("end_date"));
    out.put("closed", Boolean.parseBoolean(row.get("closed")));
    return out;
  }

  static Map<String, Object> journal(LedgerStore.JournalDetail detail) {
    var header = detail.header();
    var currency = header.get("currency");
    var lines = new ArrayList<Map<String, Object>>();
    for (var line : detail.lines()) {
      var minor = new BigInteger(line.get("amount"));
      var item = new LinkedHashMap<String, Object>();
      item.put("line", Integer.parseInt(line.get("line_no")));
      item.put("account", line.get("account_id"));
      item.put("side", line.get("side"));
      item.put("amountMinor", minor.toString());
      item.put("amount", TrialBalance.decimal(currency, minor));
      lines.add(item);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("id", detail.id());
    out.put("period", header.get("period_id"));
    out.put("date", header.get("effective_date"));
    out.put("recordedAt", header.get("recorded_at"));
    out.put("currency", currency);
    out.put("reference", header.get("reference"));
    out.put("reverses", header.get("reverses"));
    out.put("reversedBy", header.get("reversed_by"));
    out.put("lines", lines);
    return out;
  }

  static Map<String, Object> trialBalance(TrialBalance report) {
    var currencies = new ArrayList<Map<String, Object>>();
    for (var totals : report.currencies()) {
      var accounts = new ArrayList<Map<String, Object>>();
      for (var row : totals.accounts()) {
        var item = new LinkedHashMap<String, Object>();
        item.put("id", row.account());
        item.put("name", row.name());
        item.put("normalSide", row.normalSide());
        item.put("debitsMinor", row.debits().toString());
        item.put("creditsMinor", row.credits().toString());
        item.put("normalBalanceMinor", row.normalBalance().toString());
        item.put("normalBalance", TrialBalance.decimal(row.currency(), row.normalBalance()));
        accounts.add(item);
      }
      var currency = new LinkedHashMap<String, Object>();
      currency.put("currency", totals.currency());
      currency.put("debitsMinor", totals.debits().toString());
      currency.put("creditsMinor", totals.credits().toString());
      currency.put("debits", TrialBalance.decimal(totals.currency(), totals.debits()));
      currency.put("credits", TrialBalance.decimal(totals.currency(), totals.credits()));
      currency.put("balanced", totals.balanced());
      currency.put("accounts", accounts);
      currencies.add(currency);
    }
    var out = new LinkedHashMap<String, Object>();
    out.put("asOf", report.asOf());
    out.put("balanced", report.balanced());
    out.put("currencies", currencies);
    return out;
  }

  static List<Map<String, Object>> accounts(List<Map<String, String>> rows) {
    return rows.stream().map(ReadModels::account).toList();
  }
}
