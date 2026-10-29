package io.github.cheshmakzanoon.portfolio.daybook;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/** Authoritative reads and period management against a real PostgreSQL. */
class LedgerReadModelsIT extends LedgerPostgresFixture {
  private final JsonMapper json = JsonMapper.builder().build();

  private Jwt token(UUID forTenant) {
    return Jwt.withTokenValue("fixture")
        .header("alg", "RS256")
        .subject("reader")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("tenant", forTenant.toString())
        .build();
  }

  private Journal between(
      UUID debit, UUID credit, String currency, LocalDate date, long minor, String ref) {
    return new Journal(
        period,
        date,
        currency,
        ref,
        List.of(
            new Journal.Line(debit, Journal.Side.DEBIT, BigInteger.valueOf(minor)),
            new Journal.Line(credit, Journal.Side.CREDIT, BigInteger.valueOf(minor))));
  }

  @Test
  void trialBalanceAgreesWithAnIndependentLedgerAtEveryCutoffAndNeverCrossesTenants() {
    UUID usdCash = store.account(tenant, "USD", Journal.Side.DEBIT, "cash");
    UUID usdSales = store.account(tenant, "USD", Journal.Side.CREDIT, "sales");
    UUID cadTax = store.account(tenant, "CAD", Journal.Side.CREDIT, "tax");
    UUID idle = store.account(tenant, "JPY", Journal.Side.DEBIT, "idle");
    List<UUID> cad = List.of(a, b, cadTax);
    Map<UUID, Account> directory = new HashMap<>();
    directory.put(a, new Account("cash", "CAD", Journal.Side.DEBIT));
    directory.put(b, new Account("sales", "CAD", Journal.Side.CREDIT));
    directory.put(cadTax, new Account("tax", "CAD", Journal.Side.CREDIT));
    directory.put(usdCash, new Account("cash", "USD", Journal.Side.DEBIT));
    directory.put(usdSales, new Account("sales", "USD", Journal.Side.CREDIT));
    directory.put(idle, new Account("idle", "JPY", Journal.Side.DEBIT));

    Random random = new Random(20261029);
    List<Journal> posted = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      LocalDate date = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365));
      long amount = 1 + random.nextInt(500_000);
      Journal journal =
          random.nextBoolean()
              ? between(
                  cad.get(random.nextInt(cad.size())),
                  cad.get((random.nextInt(cad.size() - 1) + 1 + cad.indexOf(a)) % cad.size()),
                  "CAD",
                  date,
                  amount,
                  "cad-" + i)
              : between(usdCash, usdSales, "USD", date, amount, "usd-" + i);
      if (journal.lines().getFirst().account().equals(journal.lines().getLast().account()))
        continue;
      UUID id = store.post(tenant, "k-" + i, journal);
      posted.add(journal);
      if (i % 7 == 0) {
        LocalDate reversalDate = date.plusDays(random.nextInt(20));
        store.reverse(tenant, id, "r-" + i, reversalDate);
        posted.add(journal.reversed(reversalDate));
      }
    }
    // Another tenant's activity must not appear in this tenant's report.
    UUID foreign = store.account(other, "CAD", Journal.Side.DEBIT, "foreign");
    assertNotNull(foreign);

    // The inclusive boundary matters most: probe the day before, the day of and the day after the
    // effective date of several actual journals, in addition to dates outside the data.
    Set<LocalDate> cutoffs =
        new TreeSet<>(
            List.of(
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                LocalDate.of(2030, 1, 1)));
    for (int i = 0; i < posted.size(); i += 5) {
      LocalDate date = posted.get(i).date();
      cutoffs.addAll(List.of(date.minusDays(1), date, date.plusDays(1)));
    }
    assertTrue(cutoffs.size() > 20, "enough distinct cutoffs: " + cutoffs.size());
    for (LocalDate cutoff : cutoffs) {
      TrialBalance actual = store.trialBalance(tenant, cutoff);
      TrialBalance expected = expected(cutoff, posted, directory);
      assertEquals(expected, actual, "cutoff " + cutoff);
      assertTrue(actual.balanced(), "cutoff " + cutoff);
      assertEquals(
          directory.size(), actual.currencies().stream().mapToInt(c -> c.accounts().size()).sum());
    }

    // A second, separately maintained structure must agree once every journal is included.
    TrialBalance everything = store.trialBalance(tenant, LocalDate.of(2099, 1, 1));
    Map<String, String[]> maintained = new HashMap<>();
    for (Map<String, String> row : store.balances(tenant))
      maintained.put(row.get("id"), new String[] {row.get("debits"), row.get("credits")});
    for (TrialBalance.CurrencyTotals currency : everything.currencies())
      for (TrialBalance.Row row : currency.accounts()) {
        String[] totals = maintained.get(row.account().toString());
        assertEquals(totals[0], row.debits().toString(), row.name());
        assertEquals(totals[1], row.credits().toString(), row.name());
      }
  }

  private record Account(String name, String currency, Journal.Side side) {}

  private TrialBalance expected(
      LocalDate cutoff, List<Journal> journals, Map<UUID, Account> accounts) {
    ReferenceLedger reference = new ReferenceLedger();
    Map<UUID, BigInteger> debits = new HashMap<>(), credits = new HashMap<>();
    for (Journal journal : journals) {
      if (journal.date().isAfter(cutoff)) continue;
      reference.apply(journal);
      for (Journal.Line line : journal.lines())
        (line.side() == Journal.Side.DEBIT ? debits : credits)
            .merge(line.account(), line.minor(), BigInteger::add);
    }
    List<TrialBalance.Row> rows = new ArrayList<>();
    accounts.forEach(
        (id, account) -> {
          var row =
              new TrialBalance.Row(
                  id,
                  account.name(),
                  account.currency(),
                  account.side(),
                  debits.getOrDefault(id, BigInteger.ZERO),
                  credits.getOrDefault(id, BigInteger.ZERO));
          assertEquals(reference.balances().getOrDefault(id, BigInteger.ZERO), row.net());
          rows.add(row);
        });
    return TrialBalance.of(cutoff, rows);
  }

  @Test
  void anEmptyTenantHasAnEmptyBalancedReportAndTheAccountBoundIsEnforced() {
    UUID empty = UUID.randomUUID();
    admin.tx(
        c -> {
          LedgerStore.execute(c, "INSERT INTO ledger.tenant VALUES(?)", empty);
          return null;
        });
    TrialBalance report = store.trialBalance(empty, LocalDate.of(2026, 6, 30));
    assertTrue(report.currencies().isEmpty());
    assertTrue(report.balanced());
    assertThrows(IllegalArgumentException.class, () -> store.trialBalance(tenant, null));
    assertThrows(IllegalArgumentException.class, () -> store.trialBalance(null, LocalDate.now()));
  }

  @Test
  void journalDetailLinksReversalsBothWaysAndIsTenantScoped() {
    Journal original = journal(1250, "original");
    UUID id = store.post(tenant, "detail", original);
    assertEquals(Optional.empty(), store.journal(tenant, UUID.randomUUID()));
    assertEquals(Optional.empty(), store.journal(other, id), "another tenant cannot read it");

    var detail = store.journal(tenant, id).orElseThrow();
    assertEquals(id, detail.id());
    assertEquals("original", detail.header().get("reference"));
    assertEquals("CAD", detail.header().get("currency"));
    assertEquals("2026-01-02", detail.header().get("effective_date"));
    assertEquals(period.toString(), detail.header().get("period_id"));
    assertNull(detail.header().get("reverses"));
    assertNull(detail.header().get("reversed_by"));
    assertEquals(2, detail.lines().size());
    assertEquals(List.of("1", "2"), detail.lines().stream().map(l -> l.get("line_no")).toList());

    UUID reversal = store.reverse(tenant, id, "undo", LocalDate.of(2026, 1, 9));
    assertEquals(
        reversal.toString(), store.journal(tenant, id).orElseThrow().header().get("reversed_by"));
    var undo = store.journal(tenant, reversal).orElseThrow();
    assertEquals(id.toString(), undo.header().get("reverses"));
    assertNull(undo.header().get("reversed_by"));
  }

  @Test
  void controllerShapesAreExactStringsAndMissingIdentitiesAreNotFound() throws Exception {
    var controller = new LedgerController(store);
    UUID id = store.post(tenant, "shape", journal(1250, "shape"));
    var jwt = token(tenant);

    var response = controller.journal(jwt, id);
    assertEquals(200, response.getStatusCode().value());
    @SuppressWarnings("unchecked")
    var body = (Map<String, Object>) response.getBody();
    assertEquals("12.50", ((Map<?, ?>) ((List<?>) body.get("lines")).getFirst()).get("amount"));
    assertEquals("1250", ((Map<?, ?>) ((List<?>) body.get("lines")).getFirst()).get("amountMinor"));
    assertEquals("DEBIT", ((Map<?, ?>) ((List<?>) body.get("lines")).getFirst()).get("side"));
    assertNotNull(json.writeValueAsString(body), "must serialize");

    assertEquals(404, controller.journal(jwt, UUID.randomUUID()).getStatusCode().value());
    assertEquals(404, controller.journal(token(other), id).getStatusCode().value());
    assertEquals(404, controller.account(jwt, UUID.randomUUID()).getStatusCode().value());

    var cash = controller.account(jwt, a);
    assertEquals(200, cash.getStatusCode().value());
    @SuppressWarnings("unchecked")
    var cashBody = (Map<String, Object>) cash.getBody();
    assertEquals("12.50", cashBody.get("normalBalance"));
    assertEquals("1250", cashBody.get("normalBalanceMinor"));
    @SuppressWarnings("unchecked")
    var salesBody = (Map<String, Object>) controller.account(jwt, b).getBody();
    assertEquals(
        "12.50", salesBody.get("normalBalance"), "credit-normal account shows a positive balance");

    var report = controller.trialBalance(jwt, LocalDate.of(2026, 6, 30));
    assertEquals(true, report.get("balanced"));
    String text = json.writeValueAsString(report);
    assertTrue(text.contains("\"asOf\":\"2026-06-30\""), text);
    assertTrue(
        text.contains("\"debits\":\"12.50\"") && text.contains("\"credits\":\"12.50\""), text);
    assertTrue(controller.trialBalance(jwt, null).containsKey("asOf"), "asOf defaults to today");
  }

  @Test
  void accountPagesFollowTheCursorWithoutGapsOrRepeats() {
    for (int i = 0; i < 7; i++) store.account(tenant, "CAD", Journal.Side.DEBIT, "extra-" + i);
    var controller = new LedgerController(store);
    var jwt = token(tenant);
    List<String> seen = new ArrayList<>();
    String after = "00000000-0000-0000-0000-000000000000";
    for (int pages = 0; pages < 10; pages++) {
      var page = controller.accounts(jwt, UUID.fromString(after), 4);
      @SuppressWarnings("unchecked")
      var accounts = (List<Map<String, Object>>) page.get("accounts");
      accounts.forEach(account -> seen.add((String) account.get("id")));
      @SuppressWarnings("unchecked")
      var next = (Map<String, Object>) page.get("next");
      if (next.isEmpty()) break;
      after = (String) next.get("after");
    }
    assertEquals(9, seen.size());
    assertEquals(seen.stream().sorted().toList(), seen);
    assertEquals(9, seen.stream().distinct().count());
    for (int invalid : new int[] {0, -1, 501})
      assertThrows(
          IllegalArgumentException.class, () -> store.accounts(tenant, new UUID(0, 0), invalid));
  }

  @Test
  void periodsOpenIdempotentlyRefuseOverlapAndAreUsableImmediately() throws Exception {
    LocalDate start = LocalDate.of(2027, 1, 1), end = LocalDate.of(2027, 12, 31);
    UUID created = store.openPeriod(tenant, start, end);
    assertEquals(created, store.openPeriod(tenant, start, end), "identical range is idempotent");
    assertEquals(
        List.of("2026-01-01", "2027-01-01"),
        store.periods(tenant).stream().map(p -> p.get("start_date")).toList());

    for (LocalDate[] overlap :
        new LocalDate[][] {
          {LocalDate.of(2027, 6, 1), LocalDate.of(2028, 1, 31)},
          {LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)},
          {LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31)},
          {LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)},
        }) {
      var failure =
          assertThrows(
              LedgerStore.LedgerException.class,
              () -> store.openPeriod(tenant, overlap[0], overlap[1]));
      assertEquals("PERIOD_OVERLAP_CONFLICT", failure.code);
      assertFalse(failure.temporary);
    }
    // Adjacent ranges do not overlap.
    UUID next = store.openPeriod(tenant, LocalDate.of(2028, 1, 1), LocalDate.of(2028, 1, 31));
    assertNotEquals(created, next);

    var accountsHere = List.of(a, b);
    Journal inNewPeriod =
        new Journal(
            created,
            LocalDate.of(2027, 5, 5),
            "CAD",
            "new-period",
            List.of(
                new Journal.Line(accountsHere.get(0), Journal.Side.DEBIT, BigInteger.TEN),
                new Journal.Line(accountsHere.get(1), Journal.Side.CREDIT, BigInteger.TEN)));
    assertNotNull(store.post(tenant, "in-new-period", inNewPeriod));
    store.close(tenant, created);
    assertEquals(
        "true",
        store.periods(tenant).stream()
            .filter(p -> p.get("id").equals(created.toString()))
            .findFirst()
            .orElseThrow()
            .get("closed"));
  }

  @Test
  void invalidRangesUnknownTenantsAndDirectWritesAreRefused() {
    LocalDate day = LocalDate.of(2030, 1, 1);
    assertEquals(
        "PERIOD_RANGE",
        assertThrows(
                LedgerStore.LedgerException.class,
                () -> store.openPeriod(tenant, day, day.minusDays(1)))
            .code);
    assertEquals(
        "PERIOD_RANGE",
        assertThrows(
                LedgerStore.LedgerException.class,
                () -> store.openPeriod(tenant, day, day.plusDays(3661)))
            .code);
    assertNotNull(
        store.openPeriod(tenant, day, day.plusDays(3660)), "the inclusive limit is a valid span");
    assertEquals(
        "TENANT_NOT_FOUND",
        assertThrows(
                LedgerStore.LedgerException.class,
                () -> store.openPeriod(UUID.randomUUID(), day, day))
            .code);
    assertThrows(IllegalArgumentException.class, () -> store.openPeriod(tenant, null, day));
    assertThrows(IllegalArgumentException.class, () -> store.openPeriod(null, day, day));
    // The runtime role has no table privilege: only the restricted entry point can create periods.
    assertThrows(
        LedgerStore.LedgerException.class,
        () ->
            store.tx(
                c ->
                    LedgerStore.execute(
                        c,
                        "INSERT INTO ledger.accounting_period VALUES(?,?,DATE '2040-01-01',DATE '2040-12-31',false)",
                        tenant,
                        UUID.randomUUID())));
  }

  @Test
  void concurrentOpensOfOneRangeAgreeAndOfOverlappingRangesAdmitExactlyOne() throws Exception {
    LocalDate start = LocalDate.of(2031, 1, 1), end = LocalDate.of(2031, 12, 31);
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<UUID>> same = new ArrayList<>();
      for (int i = 0; i < 8; i++) same.add(pool.submit(() -> store.openPeriod(tenant, start, end)));
      Set<UUID> ids = new HashSet<>();
      for (var future : same) ids.add(future.get(30, TimeUnit.SECONDS));
      assertEquals(1, ids.size());

      List<Future<Boolean>> overlapping = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        LocalDate from = LocalDate.of(2032, 1, 1).plusDays(i * 10L);
        overlapping.add(
            pool.submit(
                () -> {
                  try {
                    store.openPeriod(tenant, from, from.plusDays(45));
                    return true;
                  } catch (LedgerStore.LedgerException conflict) {
                    assertEquals("PERIOD_OVERLAP_CONFLICT", conflict.code);
                    return false;
                  }
                }));
      }
      long admitted = 0;
      for (var future : overlapping) if (future.get(30, TimeUnit.SECONDS)) admitted++;
      assertTrue(admitted >= 1, "at least one range is admitted");
      // Every admitted range is disjoint from every other.
      var all = store.periods(tenant);
      for (int i = 0; i < all.size(); i++)
        for (int j = i + 1; j < all.size(); j++) {
          LocalDate s1 = LocalDate.parse(all.get(i).get("start_date")),
              e1 = LocalDate.parse(all.get(i).get("end_date"));
          LocalDate s2 = LocalDate.parse(all.get(j).get("start_date")),
              e2 = LocalDate.parse(all.get(j).get("end_date"));
          assertTrue(e1.isBefore(s2) || e2.isBefore(s1), "periods " + i + " and " + j + " overlap");
        }
    }
  }
}
