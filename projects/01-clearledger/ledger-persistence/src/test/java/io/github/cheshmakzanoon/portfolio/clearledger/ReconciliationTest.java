package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ReconciliationTest {
  @Test
  void auditEvidenceRejectsUnrepresentableTextBeforeAcquiringADatabaseConnection() {
    var reconciliation = new Reconciliation(null);
    for (String invalid : List.of("audit\0text", "\uD800", "\uDC00")) {
      for (int field = 0; field < 3; field++) {
        String actor = field == 0 ? invalid : "reviewer";
        String reason = field == 1 ? invalid : "reviewed";
        String evidence = field == 2 ? invalid : "source row";
        var error =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    reconciliation.resolve(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        1,
                        actor,
                        reason,
                        evidence));
        assertEquals("DECISION_EVIDENCE", error.getMessage());
      }
    }
  }

  @Test
  void exactByteRowAndFieldLimitsRemainAccepted() {
    String header = "source_id,reference,currency,amount\n";
    for (String ending : List.of("", "\n", "\r\n")) {
      String csv = header + "source,ref,CAD,1.00\n".repeat(4999) + "last,ref,CAD,1.00" + ending;
      assertEquals(
          5000,
          StatementCsv.prepare(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .rows()
              .size());
    }
    var exact =
        header
            + "s,"
            + "r".repeat(1_048_576 - header.length() - "s,,CAD,1.00\n".length())
            + ",CAD,1.00\n";
    assertEquals(1_048_576, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    assertEquals(
        "FIELD_BOUND",
        StatementCsv.prepare(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .rows()
            .getFirst()
            .reason());
    var valid =
        StatementCsv.prepare(
            (header + "s".repeat(120) + "," + "r".repeat(120) + ",JPY,1\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(StatementCsv.Status.UNMATCHED, valid.rows().getFirst().status());
  }

  @Test
  void missingReferenceAndInvalidRowsKeepDistinctReasonsAndSourcePositions() {
    String csv =
        "source_id,reference,currency,amount\nblank,,CAD,1.00\nzero,,CAD,0\n,ref,CAD,1.00\n"
            + "long,"
            + "r".repeat(121)
            + ",CAD,1.00\n"
            + "s".repeat(121)
            + ",ref,CAD,1.00\n\n";
    var rows = StatementCsv.prepare(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)).rows();
    assertEquals(
        List.of(
            "MISSING_REFERENCE",
            "ZERO_AMOUNT",
            "FIELD_BOUND",
            "FIELD_BOUND",
            "FIELD_BOUND",
            "COLUMN_COUNT"),
        rows.stream().map(StatementCsv.Row::reason).toList());
    assertEquals(StatementCsv.Status.UNMATCHED, rows.getFirst().status());
    assertEquals(6, rows.getLast().number());
    for (String invalid :
        List.of("", "source_id,wrong,currency,amount\n", "\0source_id,reference,currency,amount")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> StatementCsv.prepare(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    assertEquals("'\tplain", StatementCsv.safeCell("\tplain"));
    assertEquals("'\rplain", StatementCsv.safeCell("\rplain"));
    assertNotEquals(
        StatementCsv.prepare(
                "source_id,reference,currency,amount\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .contentHash(),
        StatementCsv.prepare(
                "source_id,reference,currency,amount\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .contentHash());
  }

  @Test
  void preparationOwnsImmutableRowsAndSourceIdentityBeforeAnyTransaction() {
    var csv =
        "source_id,reference,currency,amount\nfirst,ref,CAD,1.00\nfirst,ref,CAD,1.00\nthird,ref,CAD,1.001\n";
    byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var parsed = StatementCsv.prepare(bytes);
    assertEquals(
        List.of(
            StatementCsv.Status.UNMATCHED,
            StatementCsv.Status.DUPLICATE,
            StatementCsv.Status.INVALID),
        parsed.rows().stream().map(StatementCsv.Row::status).toList());
    assertEquals(new java.math.BigInteger("100"), parsed.rows().getFirst().amount());
    assertEquals("SCALE", parsed.rows().getLast().reason());
    assertEquals(3, parsed.rows().getLast().number());
    assertThrows(UnsupportedOperationException.class, () -> parsed.rows().clear());
    var hash = parsed.contentHash();
    Arrays.fill(bytes, (byte) 0);
    assertEquals("first", parsed.rows().getFirst().source());
    assertEquals(hash, parsed.contentHash());
  }

  @Test
  void exportUsesContractColumnsRatherThanMapIterationOrder() {
    var row = new LinkedHashMap<String, String>();
    row.put("reason", "=formula");
    row.put("status", "INVALID");
    row.put("amount", null);
    row.put("currency", "CAD");
    row.put("reference", "quoted,\"reference\"");
    row.put("source_id", "source");
    row.put("row_no", "1");
    row.put("internal", "must not leak");
    assertEquals(
        "row_no,source_id,reference,currency,amount,status,reason\n"
            + "\"1\",\"source\",\"quoted,\"\"reference\"\"\",\"CAD\",\"\",\"INVALID\",\"'=formula\"\n",
        StatementCsv.write(List.of(row)));
  }

  @Test
  void invalidUtf8CannotSilentlyChangeFinancialReferences() {
    var header =
        "source_id,reference,currency,amount\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (byte[] invalid :
        List.of(
            new byte[] {(byte) 0xc3},
            new byte[] {(byte) 0xc3, 0x28},
            new byte[] {(byte) 0xc0, (byte) 0xaf},
            new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
      var csv = java.util.Arrays.copyOf(header, header.length + invalid.length);
      System.arraycopy(invalid, 0, csv, header.length, invalid.length);
      var failure =
          assertThrows(
              IllegalArgumentException.class,
              () -> new Reconciliation(null).ingest(UUID.randomUUID(), csv));
      assertEquals("IMPORT_ENCODING", failure.getMessage());
    }
  }

  @Test
  void nulIsRejectedBeforeDatabaseAccess() {
    var csv = "source_id,reference,currency,amount\nsource,ref\0,CAD,1.00\n";
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Reconciliation(null)
                    .ingest(
                        UUID.randomUUID(), csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals("IMPORT_NUL_BYTE", failure.getMessage());
  }

  @Test
  void csvIsBoundedAndQuoted() {
    assertEquals(List.of("a", "b,c", "JPY", "2"), StatementCsv.parseLine("a,\"b,c\",JPY,2"));
    assertEquals(List.of("a\"b"), StatementCsv.parseLine("\"a\"\"b\""));
    for (String s : List.of("\"open", "a\"bad", "\"closed\"bad"))
      assertThrows(IllegalArgumentException.class, () -> StatementCsv.parseLine(s));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Reconciliation(null).ingest(UUID.randomUUID(), new byte[1048577]));
  }

  @Test
  void rowBoundaryAndSpreadsheetEscaping() {
    var oversized =
        "source_id,reference,currency,amount\n" + "x,r,CAD,1.00\n".repeat(5000) + "last,r,CAD,1.00";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Reconciliation(null)
                .ingest(
                    UUID.randomUUID(),
                    oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    for (String value : List.of("=SUM(A1)", "+1", "-2", "@x", " \t=SUM(A1)", "\uFEFF=SUM(A1)"))
      assertEquals("'" + value, StatementCsv.safeCell(value));
    assertEquals("plain", StatementCsv.safeCell("plain"));
    assertEquals("", StatementCsv.safeCell(null));
  }
}
