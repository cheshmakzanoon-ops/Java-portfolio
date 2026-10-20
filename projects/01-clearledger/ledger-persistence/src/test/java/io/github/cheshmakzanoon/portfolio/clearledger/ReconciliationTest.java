package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ReconciliationTest {
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
    assertEquals(List.of("a", "b,c", "JPY", "2"), Reconciliation.parseLine("a,\"b,c\",JPY,2"));
    assertEquals(List.of("a\"b"), Reconciliation.parseLine("\"a\"\"b\""));
    for (String s : List.of("\"open", "a\"bad", "\"closed\"bad"))
      assertThrows(IllegalArgumentException.class, () -> Reconciliation.parseLine(s));
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
      assertEquals("'" + value, Reconciliation.safeCell(value));
    assertEquals("plain", Reconciliation.safeCell("plain"));
    assertEquals("", Reconciliation.safeCell(null));
  }
}
