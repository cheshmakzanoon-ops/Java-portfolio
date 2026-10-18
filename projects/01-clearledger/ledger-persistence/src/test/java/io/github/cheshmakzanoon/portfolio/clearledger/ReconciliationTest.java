package io.github.cheshmakzanoon.portfolio.clearledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class ReconciliationTest {
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
