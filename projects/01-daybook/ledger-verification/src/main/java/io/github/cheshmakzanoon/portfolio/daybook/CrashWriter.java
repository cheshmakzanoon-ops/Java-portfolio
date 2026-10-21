package io.github.cheshmakzanoon.portfolio.daybook;

import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Verification-only loopback HTTP writer killed at a named transaction/response boundary. */
public final class CrashWriter {
  public static void main(String[] a) throws Exception {
    if (a.length != 10)
      throw new IllegalArgumentException(
          "URL tenant period debit credit key before|after barrier portFile nonce");
    var journal =
        new Journal(
            UUID.fromString(a[2]),
            LocalDate.of(2026, 1, 2),
            "CAD",
            "crash",
            List.of(
                new Journal.Line(UUID.fromString(a[3]), Journal.Side.DEBIT, BigInteger.TEN),
                new Journal.Line(UUID.fromString(a[4]), Journal.Side.CREDIT, BigInteger.TEN)));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
    server.setExecutor(Executors.newSingleThreadExecutor());
    server.createContext(
        "/post",
        exchange -> {
          if (!exchange.getRequestMethod().equals("POST")
              || !("Bearer " + a[9])
                  .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
          }
          try (var connection =
              DriverManager.getConnection(a[0], "daybook_runtime", "local-only-daybook")) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (var limits = connection.createStatement()) {
              limits.execute("SET LOCAL statement_timeout='15s'");
            }
            var id =
                LedgerStore.postOn(
                    connection, UUID.fromString(a[1]), a[5], journal, null, UUID.randomUUID());
            if (a[6].equals("after")) connection.commit();
            Files.writeString(Path.of(a[7]), "READY");
            // No HTTP response headers have been sent. The parent kills this actual process here.
            Thread.sleep(60000);
            if (a[6].equals("before")) connection.commit();
            byte[] body = ("{\"journalId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception failure) {
            try {
              exchange.sendResponseHeaders(500, -1);
            } catch (java.io.IOException ignored) {
              /* peer closed */
            }
          } finally {
            exchange.close();
          }
        });
    server.start();
    var portFile = Path.of(a[8]);
    var staged = portFile.resolveSibling(portFile.getFileName() + ".tmp");
    Files.writeString(staged, Integer.toString(server.getAddress().getPort()));
    Files.move(
        staged, portFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    new CountDownLatch(1).await();
  }
}
