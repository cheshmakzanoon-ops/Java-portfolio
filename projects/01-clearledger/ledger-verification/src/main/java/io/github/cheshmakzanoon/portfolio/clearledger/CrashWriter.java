package io.github.cheshmakzanoon.portfolio.clearledger;

import java.math.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * A real subprocess intentionally killed by the integration suite at named transaction boundaries.
 */
public final class CrashWriter {
  public static void main(String[] a) throws Exception {
    var store = new LedgerStore(a[0], "clearledger_runtime", "local-only-clearledger");
    var j =
        new Journal(
            UUID.fromString(a[2]),
            LocalDate.of(2026, 1, 2),
            "CAD",
            "crash",
            List.of(
                new Journal.Line(UUID.fromString(a[3]), Journal.Side.DEBIT, BigInteger.TEN),
                new Journal.Line(UUID.fromString(a[4]), Journal.Side.CREDIT, BigInteger.TEN)));
    try (var c =
        DriverManager.getConnection(a[0], "clearledger_runtime", "local-only-clearledger")) {
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      LedgerStore.postOn(c, UUID.fromString(a[1]), a[5], j, null, UUID.randomUUID());
      if (a[6].equals("after")) c.commit();
      Files.writeString(Path.of(a[7]), "READY");
      Thread.sleep(60000);
    }
  }
}
