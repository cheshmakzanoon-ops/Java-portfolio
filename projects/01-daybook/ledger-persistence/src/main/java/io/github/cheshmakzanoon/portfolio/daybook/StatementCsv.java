package io.github.cheshmakzanoon.portfolio.daybook;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable file preparation runs once, before acquiring a serializable database connection. */
final class StatementCsv {
  enum Status {
    UNMATCHED,
    DUPLICATE,
    INVALID
  }

  record Row(
      int number,
      String source,
      String reference,
      String currency,
      BigInteger amount,
      Status status,
      String reason) {}

  record Prepared(String contentHash, List<Row> rows) {
    Prepared {
      rows = List.copyOf(rows);
    }
  }

  private static final List<String> COLUMNS =
      List.of("row_no", "source_id", "reference", "currency", "amount", "status", "reason");

  private StatementCsv() {}

  static Prepared prepare(byte[] csv) {
    Objects.requireNonNull(csv);
    if (csv.length > 1_048_576) throw new IllegalArgumentException("IMPORT_BYTE_BOUND");
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
    if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("IMPORT_NUL_BYTE");
    var lines = text.split("\\r?\\n", -1);
    if (lines.length - 1 - (lines[lines.length - 1].isEmpty() ? 1 : 0) > 5000)
      throw new IllegalArgumentException("IMPORT_ROW_BOUND");
    if (!lines[0].equals("source_id,reference,currency,amount"))
      throw new IllegalArgumentException("CSV_HEADER");
    final String hash;
    try {
      hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    var rows = new ArrayList<Row>();
    var sources = new HashSet<String>();
    for (int i = 1; i < lines.length; i++) {
      if (i == lines.length - 1 && lines[i].isEmpty()) continue;
      String source = "", reference = "", currency = "", reason = "";
      BigInteger amount = null;
      Status status = Status.UNMATCHED;
      try {
        var columns = parseLine(lines[i]);
        if (columns.size() != 4) throw new IllegalArgumentException("COLUMN_COUNT");
        source = columns.get(0);
        reference = columns.get(1);
        currency = columns.get(2);
        if (source.isBlank() || source.length() > 120 || reference.length() > 120)
          throw new IllegalArgumentException("FIELD_BOUND");
        amount = Money.parse(currency, columns.get(3)).minor();
        if (amount.signum() == 0) throw new IllegalArgumentException("ZERO_AMOUNT");
        if (!sources.add(source)) {
          status = Status.DUPLICATE;
          reason = "DUPLICATE_SOURCE_ID";
        }
      } catch (IllegalArgumentException invalid) {
        status = Status.INVALID;
        reason = invalid.getMessage();
      }
      if (status == Status.UNMATCHED && reference.isBlank()) reason = "MISSING_REFERENCE";
      rows.add(new Row(i, source, reference, currency, amount, status, reason));
    }
    return new Prepared(hash, rows);
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

  static String safeCell(String raw) {
    String value = raw == null ? "" : raw;
    String probe = value.stripLeading();
    while (probe.startsWith("\uFEFF")) probe = probe.substring(1).stripLeading();
    if (!probe.isEmpty() && "=+-@".indexOf(probe.charAt(0)) >= 0
        || !value.isEmpty() && "\t\r".indexOf(value.charAt(0)) >= 0) return "'" + value;
    return value;
  }

  static String write(List<Map<String, String>> rows) {
    var output = new StringBuilder(String.join(",", COLUMNS)).append('\n');
    for (var row : rows) {
      var cells = new ArrayList<String>();
      for (var column : COLUMNS) {
        String value = safeCell(row.get(column));
        cells.add("\"" + value.replace("\"", "\"\"") + "\"");
      }
      output.append(String.join(",", cells)).append('\n');
    }
    return output.toString();
  }
}
