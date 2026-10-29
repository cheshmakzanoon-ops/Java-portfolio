package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class LedgerPostgresIT extends LedgerPostgresFixture {
  @Test
  void databaseMatchesIndependentGeneratedModelAndPrivileges() {
    var random = new Random(20261017);
    var ref = new ReferenceLedger();
    for (int i = 0; i < 80; i++) {
      var j = journal(random.nextInt(100000) + 1, "generated-" + i);
      store.post(tenant, "generated-" + i, j);
      ref.apply(j);
    }
    for (var row : store.balances(tenant)) {
      var signed = new BigInteger(row.get("debits")).subtract(new BigInteger(row.get("credits")));
      assertEquals(ref.balances().get(UUID.fromString(row.get("id"))), signed);
    }
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            store.tx(
                c ->
                    LedgerStore.execute(
                        c, "DELETE FROM ledger.posting WHERE tenant_id=?", tenant)));
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            store.tx(
                c ->
                    LedgerStore.execute(
                        c,
                        "UPDATE ledger.journal SET reference='tamper' WHERE tenant_id=?",
                        tenant)));
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            store.tx(
                c ->
                    LedgerStore.execute(
                        c, "UPDATE ledger.account SET currency='USD' WHERE tenant_id=?", tenant)));
    assertThrows(
        LedgerStore.LedgerException.class, () -> store.post(other, "cross", journal(1, "cross")));
    var usd = store.account(tenant, "USD", Journal.Side.DEBIT, "usd");
    var invalid =
        new Journal(
            period,
            LocalDate.of(2026, 1, 2),
            "CAD",
            "wrong",
            List.of(
                new Journal.Line(usd, Journal.Side.DEBIT, BigInteger.ONE),
                new Journal.Line(b, Journal.Side.CREDIT, BigInteger.ONE)));
    assertThrows(LedgerStore.LedgerException.class, () -> store.post(tenant, "currency", invalid));
    assertEquals(80, count("journal"));
  }

  @Test
  void sixtyFourDuplicateRequestsAndRacingReversals() throws Exception {
    var pool = Executors.newFixedThreadPool(16);
    try {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<UUID>>();
      for (int i = 0; i < 64; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return store.post(tenant, "same", journal(100, "same"));
                }));
      start.countDown();
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(60, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
      assertEquals(1, count("journal"));
      assertThrows(
          LedgerStore.LedgerException.class,
          () -> store.post(tenant, "same", journal(101, "same")));
      var original = ids.iterator().next();
      var reversed = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 8; i++) {
        String key = "rev-" + i;
        reversed.add(
            pool.submit(
                () -> {
                  try {
                    store.reverse(tenant, original, key, LocalDate.of(2026, 2, 1));
                    return true;
                  } catch (LedgerStore.LedgerException e) {
                    return false;
                  }
                }));
      }
      int ok = 0;
      for (var f : reversed) if (f.get(60, TimeUnit.SECONDS)) ok++;
      assertEquals(1, ok);
      assertEquals(2, count("journal"));
      for (var row : store.balances(tenant)) assertEquals(row.get("debits"), row.get("credits"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void thirtyTwoWritersRetainAllUniqueAndDuplicateReceiptsUnderBoundedRetries() throws Exception {
    // Functional same-period contention, not a substitute for the nine full reference runs.
    int writers = 32, perWriter = 24;
    var outcomes = new Workload.ClientOutcomes();
    var executor = Executors.newFixedThreadPool(writers);
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(writers);
    var futures = new ArrayList<Future<?>>();
    try {
      for (int writer = 0; writer < writers; writer++) {
        int owner = writer;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  for (int n = 1; n <= perWriter; n++) {
                    String key = "contention-" + owner + "-" + n;
                    var intent = journal(n, key);
                    var receipt =
                        Workload.retryClient(() -> store.post(tenant, key, intent), outcomes);
                    if (n % 8 == 0)
                      assertEquals(
                          receipt,
                          Workload.retryClient(() -> store.post(tenant, key, intent), outcomes));
                  }
                  return null;
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      for (var future : futures) future.get(120, TimeUnit.SECONDS);
      assertEquals(0, outcomes.exhaustions.get());
      assertEquals(writers * perWriter, count("journal"));
      assertEquals(writers * perWriter, count("idempotency_receipt"));
      assertEquals(writers * perWriter, count("outbox_event"));
      var balances = store.balances(tenant);
      assertEquals(2, balances.size());
      var total = BigInteger.valueOf((long) writers * perWriter * (perWriter + 1) / 2);
      assertEquals(
          total,
          balances.stream()
              .map(row -> new BigInteger(row.get("debits")))
              .reduce(BigInteger.ZERO, BigInteger::add));
      assertEquals(
          total,
          balances.stream()
              .map(row -> new BigInteger(row.get("credits")))
              .reduce(BigInteger.ZERO, BigInteger::add));
      assertTrue(SourceAudit.journals(admin).isEmpty());
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
    }
  }

  @Test
  void closeRacesWritersWithoutPostingIntoClosedPeriod() throws Exception {
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++) {
        String key = "close-" + i;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    store.post(tenant, key, journal(1, key));
                  } catch (LedgerStore.LedgerException expected) {
                    assertTrue(
                        expected.code.contains("PERIOD_CLOSED_OR_DATE") || expected.temporary,
                        expected.code);
                  }
                }));
      }
      store.close(tenant, period);
      for (var f : futures) f.get(60, TimeUnit.SECONDS);
      assertThrows(
          LedgerStore.LedgerException.class, () -> store.post(tenant, "late", journal(1, "late")));
      assertEquals(
          "true",
          admin.tx(
              c ->
                  LedgerStore.query(
                          c,
                          "SELECT closed::text AS closed FROM ledger.accounting_period WHERE tenant_id=?",
                          tenant)
                      .getFirst()
                      .get("closed")));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void actualProcessTerminationBeforeAndAfterCommit() throws Exception {
    try (var client =
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build()) {
      for (String point : List.of("before", "after")) {
        var signal = Files.createTempFile("daybook-http-crash-", ".ready");
        Files.delete(signal);
        var portFile = signal.resolveSibling(signal.getFileName() + ".port");
        var nonce = UUID.randomUUID().toString();
        var command =
            List.of(
                System.getProperty("java.home") + "/bin/java",
                "-Xmx96m",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWriter.class.getName(),
                pg.getJdbcUrl(),
                tenant.toString(),
                period.toString(),
                a.toString(),
                b.toString(),
                "crash-" + point,
                point,
                signal.toString(),
                portFile.toString(),
                nonce);
        var process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> response = null;
        try {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
          while (!Files.exists(portFile) && process.isAlive() && System.nanoTime() < deadline)
            Thread.sleep(20);
          assertTrue(
              Files.exists(portFile),
              "HTTP writer must bind loopback and publish its ephemeral port");
          int port = Integer.parseInt(Files.readString(portFile));
          response =
              client.sendAsync(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://127.0.0.1:" + port + "/post"))
                      .timeout(java.time.Duration.ofSeconds(40))
                      .header("Authorization", "Bearer " + nonce)
                      .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString());
          deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
          while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline)
            Thread.sleep(20);
          assertTrue(
              Files.exists(signal),
              "HTTP writer must reach named commit barrier before sending response headers");
          process.destroyForcibly();
          assertTrue(process.waitFor(10, TimeUnit.SECONDS));
          var disconnected = response;
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> disconnected.get(10, TimeUnit.SECONDS),
              "client must observe an interrupted real HTTP exchange");
          var receipts = store.receipt(tenant, "crash-" + point);
          assertEquals(point.equals("after") ? 1 : 0, receipts.size());
          var replay = store.post(tenant, "crash-" + point, journal(10, "crash"));
          assertNotNull(replay);
          if (point.equals("after"))
            assertTrue(receipts.getFirst().get("response").contains(replay.toString()));
          assertEquals(1, store.receipt(tenant, "crash-" + point).size());
        } finally {
          process.destroyForcibly();
          if (response != null) response.cancel(true);
          Files.deleteIfExists(signal);
          Files.deleteIfExists(portFile);
          Files.deleteIfExists(portFile.resolveSibling(portFile.getFileName() + ".tmp"));
        }
      }
    }
    assertEquals(2, count("journal"));
  }

  @Test
  void projectionHandlesEarlierAllocatedEventCommittedLater() throws Exception {
    var projector = new Projector(store);
    try (var delayed =
        DriverManager.getConnection(pg.getJdbcUrl(), "daybook_runtime", "local-only-daybook")) {
      delayed.setAutoCommit(false);
      LedgerStore.postOn(
          delayed,
          tenant,
          "delayed",
          journal(10, "delayed"),
          null,
          UUID.randomUUID()); // later event uses independent tenant and period, avoiding deliberate
      // period lock.
      var t2 = UUID.randomUUID();
      var p2 = UUID.randomUUID();
      admin.tx(
          c -> {
            LedgerStore.execute(c, "INSERT INTO ledger.tenant VALUES(?)", t2);
            LedgerStore.execute(
                c,
                "INSERT INTO ledger.accounting_period VALUES(?,?,DATE '2026-01-01',DATE '2026-12-31',false)",
                t2,
                p2);
            return null;
          });
      var a2 = store.account(t2, "CAD", Journal.Side.DEBIT, "a");
      var b2 = store.account(t2, "CAD", Journal.Side.CREDIT, "b");
      store.post(
          t2,
          "later-id",
          new Journal(
              p2,
              LocalDate.of(2026, 1, 2),
              "CAD",
              "later",
              List.of(
                  new Journal.Line(a2, Journal.Side.DEBIT, BigInteger.ONE),
                  new Journal.Line(b2, Journal.Side.CREDIT, BigInteger.ONE))));
      projector.drain(projector.active());
      delayed.commit();
    }
    assertTrue(projector.pending() > 0);
    projector.drain(projector.active());
    assertEquals(0, projector.pending());
    assertEquals(0, projector.drain(projector.active()));
    var replay = projector.replay();
    assertEquals(replay, projector.active());
    var report = projector.report(tenant, a);
    assertEquals(false, report.get("authoritative"));
    assertNotNull(report.get("snapshot"));
    assertEquals(0L, report.get("pendingEvents"));
    assertEquals(replay, projector.freshness(tenant).get("generation"));
    assertEquals(0, projector.pending());
  }

  @Test
  void reconciliationPreservesUnicodeAndRejectsCorruptFilesWithoutPersistingImports() {
    var reconciliation = new Reconciliation(store);
    var header = "source_id,reference,currency,amount\n";
    var withNul =
        (header + "source,ref\0,CAD,1.00\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class, () -> reconciliation.ingest(tenant, withNul));
    var headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var corrupt = Arrays.copyOf(headerBytes, headerBytes.length + 1);
    corrupt[corrupt.length - 1] = (byte) 0xc3;
    assertThrows(IllegalArgumentException.class, () -> reconciliation.ingest(tenant, corrupt));
    assertEquals(0, count("statement_import"));
    assertEquals(0, count("statement_row"));

    store.post(tenant, "unicode", journal(100, "café-支払-\uD83D\uDE00"));
    var csv =
        (header + "énoncé-\uD83D\uDE00,café-支払-\uD83D\uDE00,CAD,1.00\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var id = reconciliation.ingest(tenant, csv);
    assertEquals(id, reconciliation.ingest(tenant, csv));
    var row = reconciliation.rows(tenant, id).getFirst();
    assertEquals("énoncé-\uD83D\uDE00", row.get("source_id"));
    assertEquals("café-支払-\uD83D\uDE00", row.get("reference"));
    assertEquals("MATCHED", row.get("status"));
    assertEquals(1, count("statement_import"));
  }

  @Test
  void reconciliationDistinguishesRowsAndPreservesDuplicateImport() {
    store.post(tenant, "match", journal(100, "unique"));
    store.post(tenant, "amb1", journal(200, "ambiguous"));
    store.post(tenant, "amb2", journal(200, "ambiguous"));
    store.post(tenant, "amb3", journal(200, "ambiguous"));
    var csv =
        ("source_id,reference,currency,amount\n1,unique,CAD,1.00\n2,ambiguous,CAD,2.00\n3,missing,CAD,9.00\n1,unique,CAD,1.00\n4,bad,CAD,1.001\n5,=SUM(A1),CAD,4.00\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var r = new Reconciliation(store);
    var id = r.ingest(tenant, csv);
    assertEquals(id, r.ingest(tenant, csv));
    assertEquals(
        List.of("MATCHED", "AMBIGUOUS", "UNMATCHED", "DUPLICATE", "INVALID", "UNMATCHED"),
        r.rows(tenant, id).stream().map(x -> x.get("status")).toList());
    assertTrue(r.export(tenant, id).contains("'=SUM"));
    assertTrue(r.rows(other, id).isEmpty());
    var candidate = store.receipt(tenant, "amb1").getFirst().get("response");
    var jid =
        admin.tx(
            c ->
                UUID.fromString(
                    LedgerStore.query(
                            c,
                            "SELECT journal_id FROM ledger.idempotency_receipt WHERE tenant_id=? AND key='amb1'",
                            tenant)
                        .getFirst()
                        .get("journal_id")));
    assertThrows(
        IllegalArgumentException.class,
        () -> r.resolve(tenant, id, 2, jid, 1, "actor", "", "proof"));
    r.resolve(tenant, id, 2, jid, 1, "reviewer", "bank evidence", "statement row 2");
    assertEquals("MATCHED", r.rows(tenant, id).get(1).get("status"));
    assertEquals(1, count("reconciliation_decision"));
  }

  @Test
  void sourceAuditFindsAdministratorCorruptionOutsideRuntimePermissions() {
    var original = store.post(tenant, "audit-original", journal(10, "audit-original"));
    assertTrue(SourceAudit.journals(admin).isEmpty());
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            store.tx(
                c ->
                    LedgerStore.execute(
                        c,
                        "INSERT INTO ledger.journal(tenant_id,id,period_id,currency,effective_date,reference) VALUES(?,?,?,'CAD',DATE '2026-01-02','unauthorized')",
                        tenant,
                        UUID.randomUUID(),
                        period)));
    var corrupt = new LinkedHashMap<String, UUID>();
    for (String kind :
        List.of(
            "EMPTY",
            "SINGLE",
            "OVERCOUNT",
            "UNBALANCED",
            "CURRENCY",
            "MAXSUM",
            "DATE",
            "WRONG_REVERSAL")) corrupt.put(kind, UUID.randomUUID());
    try {
      admin.tx(
          c -> {
            // A DBA deliberately bypasses the deferred backstop for this diagnostic fixture only.
            LedgerStore.execute(c, "ALTER TABLE ledger.journal DISABLE TRIGGER journal_invariants");
            for (var sample : corrupt.entrySet()) {
              String kind = sample.getKey();
              UUID id = sample.getValue();
              LedgerStore.execute(
                  c,
                  "INSERT INTO ledger.journal(tenant_id,id,period_id,currency,effective_date,reference,reverses) VALUES(?,?,?,?,?,?,?)",
                  tenant,
                  id,
                  period,
                  kind.equals("CURRENCY") ? "USD" : "CAD",
                  kind.equals("DATE") ? LocalDate.of(2027, 1, 1) : LocalDate.of(2026, 1, 2),
                  kind,
                  kind.equals("WRONG_REVERSAL") ? original : null);
              int count =
                  kind.equals("EMPTY")
                      ? 0
                      : kind.equals("SINGLE")
                          ? 1
                          : kind.equals("OVERCOUNT") ? 102 : kind.equals("MAXSUM") ? 4 : 2;
              for (int line = 0; line < count; line++) {
                boolean debit = line % 2 == 0;
                var amount =
                    kind.equals("MAXSUM")
                        ? Money.MAX
                        : BigInteger.valueOf(
                            kind.equals("WRONG_REVERSAL")
                                ? 11
                                : kind.equals("UNBALANCED") && !debit ? 9 : 10);
                LedgerStore.execute(
                    c,
                    "INSERT INTO ledger.posting(tenant_id,journal_id,line_no,account_id,side,amount) VALUES(?,?,?,?,?,?)",
                    tenant,
                    id,
                    line + 1,
                    debit ? a : b,
                    debit ? "DEBIT" : "CREDIT",
                    new BigDecimal(amount));
              }
            }
            LedgerStore.execute(c, "ALTER TABLE ledger.journal ENABLE TRIGGER journal_invariants");
            return null;
          });
      var detected =
          SourceAudit.journals(admin).stream()
              .filter(r -> r.get("tenant_id").equals(tenant.toString()))
              .map(r -> UUID.fromString(r.get("journal_id")))
              .collect(java.util.stream.Collectors.toSet());
      assertEquals(
          new HashSet<>(corrupt.values()),
          detected,
          "all eight independently corrupted source cases must be visible");
    } finally {
      admin.tx(
          c -> {
            LedgerStore.execute(c, "ALTER TABLE ledger.journal ENABLE TRIGGER journal_invariants");
            LedgerStore.execute(
                c,
                "DELETE FROM ledger.posting WHERE tenant_id=? AND journal_id<>?",
                tenant,
                original);
            LedgerStore.execute(
                c, "DELETE FROM ledger.journal WHERE tenant_id=? AND id<>?", tenant, original);
            return null;
          });
    }
    assertTrue(SourceAudit.journals(admin).isEmpty());
    assertEquals(1, count("journal"));
  }

  @Test
  void backupRestoreReauditsImmutableSource() throws Exception {
    store.post(tenant, "backup", journal(321, "backup"));
    try (var clean = pg.createRestoreTarget()) {
      try {
        pg.dumpInto(clean);
        var restoredStore = new LedgerStore(clean.jdbcUrl(), clean.username(), clean.password());
        assertEquals(store.balances(tenant), restoredStore.balances(tenant));
        var violations = SourceAudit.journals(restoredStore);
        assertTrue(violations.isEmpty());
        try (var runtimeRestored =
            new LedgerStore(clean.jdbcUrl(), "daybook_runtime", "local-only-daybook")) {
          new Projector(runtimeRestored).replay();
          assertThrows(
              LedgerStore.LedgerException.class,
              () ->
                  runtimeRestored.tx(
                      c ->
                          LedgerStore.execute(
                              c, "DELETE FROM ledger.posting WHERE tenant_id=?", tenant)));
        }
        restoredStore.close();
      } finally {
        pg.dropRestoreTarget(clean);
      }
    }
  }

  @Test
  void protectedOidcAndTwoApiInstances() throws Exception {
    try (var issuer = new LocalIssuer()) {
      var decoder = DaybookApplication.protectedDecoder(issuer.issuer);
      assertEquals(
          tenant.toString(),
          decoder.decode(issuer.token(tenant, "ledger.read")).getClaimAsString("tenant"));
      for (String invalid :
          List.of(
              issuer.token(
                  tenant,
                  "ledger.read",
                  issuer.issuer,
                  "wrong",
                  java.time.Instant.now().plusSeconds(300),
                  false),
              issuer.token(
                  tenant,
                  "ledger.read",
                  "http://wrong-issuer",
                  "daybook",
                  java.time.Instant.now().plusSeconds(300),
                  false),
              issuer.token(
                  tenant,
                  "ledger.read",
                  issuer.issuer,
                  "daybook",
                  java.time.Instant.now().minusSeconds(300),
                  false),
              issuer.token(
                  tenant,
                  "ledger.read",
                  issuer.issuer,
                  "daybook",
                  java.time.Instant.now().plusSeconds(300),
                  true)))
        assertThrows(
            org.springframework.security.oauth2.jwt.JwtException.class,
            () -> decoder.decode(invalid));
      for (Object bad : List.of("", "   ", 42, List.of("actor"))) {
        assertThrows(
            org.springframework.security.oauth2.jwt.JwtException.class,
            () -> decoder.decode(issuer.identityToken(bad, tenant.toString())));
        assertThrows(
            org.springframework.security.oauth2.jwt.JwtException.class,
            () -> decoder.decode(issuer.identityToken("actor", bad)));
      }
      for (String invalidSubject : List.of("\"\\u0000\"", "\"\\uD800\"", "\"\\uDC00\"")) {
        assertThrows(
            org.springframework.security.oauth2.jwt.JwtException.class,
            () -> decoder.decode(issuer.rawIdentityToken(tenant, invalidSubject)));
      }
      assertThrows(
          org.springframework.security.oauth2.jwt.JwtException.class,
          () -> decoder.decode(issuer.identityToken(null, tenant.toString())));
      assertThrows(
          org.springframework.security.oauth2.jwt.JwtException.class,
          () -> decoder.decode(issuer.identityToken("actor", null)));
      var app1 = new org.springframework.boot.SpringApplication(DaybookApplication.class);
      var app2 = new org.springframework.boot.SpringApplication(DaybookApplication.class);
      String[] args = {
        "--server.port=0",
        "--ledger.issuer=" + issuer.issuer,
        "--ledger.url=" + pg.getJdbcUrl(),
        "--spring.main.banner-mode=off"
      };
      try (var context1 = app1.run(args);
          var context2 = app2.run(args)) {
        int port1 =
            ((org.springframework.boot.web.server.context.WebServerApplicationContext) context1)
                .getWebServer()
                .getPort();
        int port2 =
            ((org.springframework.boot.web.server.context.WebServerApplicationContext) context2)
                .getWebServer()
                .getPort();
        var client = java.net.http.HttpClient.newHttpClient();
        String token =
            issuer.token(tenant, "ledger.write ledger.read ledger.reconcile ledger.admin");
        String json =
            "{\"period\":\""
                + period
                + "\",\"date\":\"2026-01-02\",\"currency\":\"CAD\",\"reference\":\"api\",\"lines\":[{\"account\":\""
                + a
                + "\",\"side\":\"DEBIT\",\"amount\":\"1.00\"},{\"account\":\""
                + b
                + "\",\"side\":\"CREDIT\",\"amount\":\"1.00\"}]}";
        for (String malformed :
            List.of(
                json.replace("\"amount\":\"1.00\"", "\"amount\":1.00"),
                json.replace("\"side\":\"DEBIT\"", "\"side\":0"))) {
          var invalid =
              client.send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/journals"))
                      .header("Authorization", "Bearer " + token)
                      .header("Content-Type", "application/json")
                      .header("Idempotency-Key", "coercion-rejected")
                      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(malformed))
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString());
          assertEquals(400, invalid.statusCode(), invalid.body());
          assertEquals(0, count("journal"));
        }
        var response1 =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/journals"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "api-shared")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        var response2 =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port2 + "/v1/journals"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "api-shared")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response1.statusCode(), response1.body());
        assertEquals(response1.body(), response2.body());
        assertEquals(1, count("journal"));
        var cross =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://127.0.0.1:" + port1 + "/v1/accounts/" + a + "/statement"))
                    .header("Authorization", "Bearer " + issuer.token(other, "ledger.read"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cross.statusCode());
        assertTrue(cross.body().contains("\"rows\":[]"));
        var denied =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            "http://127.0.0.1:" + port1 + "/v1/requests/api-shared"))
                    .header("Authorization", "Bearer " + issuer.token(tenant, "unrelated.scope"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());

        var freshness =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/reports/freshness"))
                    .header("Authorization", "Bearer " + issuer.token(other, "ledger.admin"))
                    .GET()
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, freshness.statusCode());
        assertTrue(freshness.body().contains("\"pendingEvents\":0"), freshness.body());
        var replayUri = java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/reports/replay");
        var tenantReplay =
            client.send(
                java.net.http.HttpRequest.newBuilder(replayUri)
                    .header("Authorization", "Bearer " + token)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(403, tenantReplay.statusCode());
        var systemReplay =
            client.send(
                java.net.http.HttpRequest.newBuilder(replayUri)
                    .header("Authorization", "Bearer " + issuer.token(tenant, "ledger.system"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, systemReplay.statusCode(), systemReplay.body());

        // No Content-Length: BodyPublishers.ofInputStream emits a real chunked body.
        for (String contentType : List.of("text/csv", "application/json")) {
          byte[] oversized = new byte[1048577];
          Arrays.fill(oversized, (byte) 'a');
          var huge =
              client.send(
                  java.net.http.HttpRequest.newBuilder(
                          java.net.URI.create(
                              "http://127.0.0.1:"
                                  + port1
                                  + (contentType.equals("text/csv")
                                      ? "/v1/statements"
                                      : "/v1/journals")))
                      .header("Authorization", "Bearer " + token)
                      .header("Content-Type", contentType)
                      .POST(
                          java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                              () -> new java.io.ByteArrayInputStream(oversized)))
                      .build(),
                  java.net.http.HttpResponse.BodyHandlers.ofString());
          assertEquals(413, huge.statusCode(), huge.body());
          assertTrue(huge.body().contains("REQUEST_BYTE_BOUND"));
        }
        String nested = "[".repeat(65) + "0" + "]".repeat(65);
        var deep =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/journals"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "nested")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(nested))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(422, deep.statusCode());
        assertTrue(deep.body().contains("NESTING_BOUND"));
        String hugeAmount = json.replace("1.00", "1".repeat(100000));
        var amountRejected =
            client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + port1 + "/v1/journals"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "huge-amount")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(hugeAmount))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(422, amountRejected.statusCode());
        var injection =
            store.account(tenant, "CAD", Journal.Side.DEBIT, "'; DROP TABLE ledger.journal;--");
        assertNotNull(injection);
        assertEquals(1, count("journal"));
      }
    }
  }

  @Test
  void devProfileRefusesNonLoopbackBinding() {
    var app = new org.springframework.boot.SpringApplication(DaybookApplication.class);
    assertThrows(
        org.springframework.beans.factory.BeanCreationException.class,
        () ->
            app.run(
                "--server.port=0",
                "--server.address=0.0.0.0",
                "--spring.profiles.active=dev",
                "--ledger.url=" + pg.getJdbcUrl(),
                "--spring.main.banner-mode=off"));
  }

  @Test
  void statementSnapshotExcludesLaterCommittedJournal() throws Exception {
    String snapshot;
    try (var delayed =
        DriverManager.getConnection(pg.getJdbcUrl(), "daybook_runtime", "local-only-daybook")) {
      delayed.setAutoCommit(false);
      LedgerStore.postOn(
          delayed, tenant, "late-statement", journal(5, "late"), null, UUID.randomUUID());
      snapshot = store.visibilitySnapshot();
      delayed.commit();
    }
    var boundary = Instant.now().plusSeconds(1);
    assertTrue(
        store
            .statement(
                tenant, a, LocalDate.of(1900, 1, 1), new UUID(0, 0), 0, boundary, snapshot, 100)
            .isEmpty());
    assertEquals(
        1,
        store
            .statement(
                tenant,
                a,
                LocalDate.of(1900, 1, 1),
                new UUID(0, 0),
                0,
                boundary,
                store.visibilitySnapshot(),
                100)
            .size());
  }

  @Test
  void rawRuntimeSqlRejectsGeneratedInvalidJournalsAtomically() {
    var random = new Random(991);
    for (int i = 0; i < 20; i++) {
      int n = random.nextInt(1000) + 1;
      String payload =
          "[{\"account\":\""
              + a
              + "\",\"side\":\"DEBIT\",\"amount\":\""
              + n
              + "\"},{\"account\":\""
              + b
              + "\",\"side\":\"CREDIT\",\"amount\":\""
              + (n + 1)
              + "\"}]";
      int sample = i;
      assertThrows(
          LedgerStore.LedgerException.class,
          () ->
              store.tx(
                  c ->
                      LedgerStore.query(
                          c,
                          "SELECT ledger.post_journal(?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                          tenant,
                          "invalid-" + sample,
                          "0".repeat(64),
                          UUID.randomUUID(),
                          period,
                          LocalDate.of(2026, 1, 2),
                          "CAD",
                          "invalid",
                          payload,
                          null,
                          "POST")));
    }
    assertEquals(0, count("journal"));
    assertEquals(0, count("posting"));
    assertEquals(0, count("idempotency_receipt"));
    assertEquals(0, count("outbox_event"));
  }

  @Test
  void sqlNullMalformedShapesAndDeferredBackstopRejectAtomically() {
    String valid =
        "[{\"account\":\""
            + a
            + "\",\"side\":\"DEBIT\",\"amount\":\"10\"},{\"account\":\""
            + b
            + "\",\"side\":\"CREDIT\",\"amount\":\"10\"}]";
    var invalid =
        new ArrayList<String>(
            Arrays.asList(
                null,
                "null",
                "{}",
                "[]",
                "[{}]",
                "[" + String.join(",", Collections.nCopies(101, "{}")) + "]",
                valid.replace("10", "1.5"),
                valid.replace("10", "-1"),
                valid.replace("DEBIT", "INVALID"),
                valid.replace("\"10\"", "null")));
    for (int index = 0; index < invalid.size(); index++) {
      String payload = invalid.get(index), key = "invalid-shape-" + index;
      assertThrows(
          LedgerStore.LedgerException.class,
          () ->
              store.tx(
                  c ->
                      LedgerStore.query(
                          c,
                          "SELECT ledger.post_journal(?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                          tenant,
                          key,
                          "0".repeat(64),
                          UUID.randomUUID(),
                          period,
                          LocalDate.of(2026, 1, 2),
                          "CAD",
                          "invalid",
                          payload,
                          null,
                          "POST")),
          key);
    }
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            admin.tx(
                c ->
                    LedgerStore.execute(
                        c,
                        "INSERT INTO ledger.journal(tenant_id,id,period_id,currency,effective_date,reference) VALUES(?,?,?,'CAD',DATE '2026-01-02','backstop')",
                        tenant,
                        UUID.randomUUID(),
                        period)));
    assertEquals(0, count("journal"));
    assertEquals(0, count("posting"));
    assertEquals(0, count("idempotency_receipt"));
    assertEquals(0, count("outbox_event"));
    assertTrue(
        store.balances(tenant).stream()
            .allMatch(
                r ->
                    new BigInteger(r.get("debits")).signum() == 0
                        && new BigInteger(r.get("credits")).signum() == 0));
  }

  @Test
  void balanceOverflowRejectsWholeJournalAndReceipt() {
    var max =
        new Journal(
            period,
            LocalDate.of(2026, 1, 2),
            "CAD",
            "max",
            List.of(
                new Journal.Line(a, Journal.Side.DEBIT, Money.MAX),
                new Journal.Line(b, Journal.Side.CREDIT, Money.MAX)));
    store.post(tenant, "max", max);
    assertThrows(
        LedgerStore.LedgerException.class,
        () -> store.post(tenant, "overflow", journal(1, "overflow")));
    assertEquals(1, count("journal"));
    assertEquals(1, count("idempotency_receipt"));
    assertTrue(store.receipt(tenant, "overflow").isEmpty());
  }

  @Test
  void blankReferencesNeverAutomatchOnAmountAlone() {
    store.post(tenant, "blank-ref", journal(100, ""));
    var r = new Reconciliation(store);
    var id =
        r.ingest(
            tenant,
            "source_id,reference,currency,amount\nblank,,CAD,1.00\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals("UNMATCHED", r.rows(tenant, id).getFirst().get("status"));
    assertEquals("MISSING_REFERENCE", r.rows(tenant, id).getFirst().get("reason"));
    assertEquals(0, count("reconciliation_match"));
  }
}
