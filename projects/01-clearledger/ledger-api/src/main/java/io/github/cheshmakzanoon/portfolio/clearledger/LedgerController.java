package io.github.cheshmakzanoon.portfolio.clearledger;

import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class LedgerController {
  private final LedgerStore store;
  private final Reconciliation reconciliation;
  private final Projector projector;

  public LedgerController(LedgerStore s) {
    store = s;
    reconciliation = new Reconciliation(s);
    projector = new Projector(s);
  }

  UUID tenant(Jwt principal) {
    return UUID.fromString(principal.getClaimAsString("tenant"));
  }

  public record AccountInput(String currency, Journal.Side normalSide, String name) {}

  public record LineInput(UUID account, Journal.Side side, String amount) {}

  public record JournalInput(
      UUID period, LocalDate date, String currency, String reference, List<LineInput> lines) {
    Journal journal() {
      if (lines == null || lines.size() > 100) throw new IllegalArgumentException("BATCH_BOUND");
      return new Journal(
          period,
          date,
          currency,
          reference,
          lines.stream()
              .map(
                  l ->
                      new Journal.Line(
                          l.account(), l.side(), Money.parse(currency, l.amount()).minor()))
              .toList());
    }
  }

  public record ReversalInput(LocalDate date) {}

  public record Decision(UUID journal, int line, String reason, String evidence) {}

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @PostMapping("/v1/accounts")
  Map<String, UUID> account(@AuthenticationPrincipal Jwt p, @RequestBody AccountInput in) {
    return Map.of("id", store.account(tenant(p), in.currency(), in.normalSide(), in.name()));
  }

  @PostMapping("/v1/journals")
  Map<String, UUID> post(
      @AuthenticationPrincipal Jwt p,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JournalInput in) {
    return Map.of("journalId", store.post(tenant(p), key, in.journal()));
  }

  @PostMapping("/v1/journals/{id}/reversals")
  Map<String, UUID> reverse(
      @AuthenticationPrincipal Jwt p,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ReversalInput in) {
    return Map.of("journalId", store.reverse(tenant(p), id, key, in.date()));
  }

  @GetMapping("/v1/requests/{key}")
  List<Map<String, String>> request(@AuthenticationPrincipal Jwt p, @PathVariable String key) {
    return store.receipt(tenant(p), key);
  }

  @PostMapping("/v1/periods/{id}/close")
  Map<String, String> close(@AuthenticationPrincipal Jwt p, @PathVariable UUID id) {
    store.close(tenant(p), id);
    return Map.of("status", "CLOSED");
  }

  @GetMapping("/v1/accounts/{id}/statement")
  Map<String, Object> statement(
      @AuthenticationPrincipal Jwt p,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "1900-01-01") LocalDate afterDate,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID afterId,
      @RequestParam(defaultValue = "0") int afterLine,
      @RequestParam(required = false) Instant boundary,
      @RequestParam(required = false) String snapshot,
      @RequestParam(defaultValue = "100") int limit) {
    var cutoff = boundary == null ? Instant.now() : boundary;
    var visibility = snapshot == null ? store.visibilitySnapshot() : snapshot;
    var rows =
        store.statement(tenant(p), id, afterDate, afterId, afterLine, cutoff, visibility, limit);
    return Map.of(
        "consistency",
        "immutable source filtered by PostgreSQL transaction visibility snapshot; stable across pages",
        "snapshot",
        visibility,
        "boundary",
        cutoff,
        "account",
        id,
        "summary",
        store.statementSummary(tenant(p), id, cutoff, visibility),
        "rows",
        rows,
        "next",
        rows.isEmpty()
            ? Map.of()
            : Map.of(
                "afterDate",
                rows.getLast().get("effective_date"),
                "afterId",
                rows.getLast().get("id"),
                "afterLine",
                rows.getLast().get("line_no")),
        "filters",
        Map.of("limit", limit));
  }

  @PostMapping(value = "/v1/statements", consumes = "text/csv")
  Map<String, UUID> ingest(@AuthenticationPrincipal Jwt p, @RequestBody byte[] csv) {
    return Map.of("importId", reconciliation.ingest(tenant(p), csv));
  }

  @GetMapping("/v1/reconciliation/{id}")
  List<Map<String, String>> rows(@AuthenticationPrincipal Jwt p, @PathVariable UUID id) {
    return reconciliation.rows(tenant(p), id);
  }

  @PostMapping("/v1/reconciliation/{id}/{row}/resolve")
  Map<String, String> resolve(
      @AuthenticationPrincipal Jwt p,
      @PathVariable UUID id,
      @PathVariable int row,
      @RequestBody Decision d) {
    reconciliation.resolve(
        tenant(p), id, row, d.journal(), d.line(), p.getSubject(), d.reason(), d.evidence());
    return Map.of("status", "MATCHED");
  }

  @GetMapping(value = "/v1/reconciliation/{id}/export", produces = "text/csv")
  String export(@AuthenticationPrincipal Jwt p, @PathVariable UUID id) {
    return reconciliation.export(tenant(p), id);
  }

  @GetMapping("/v1/reports/accounts/{id}")
  Map<String, Object> report(@AuthenticationPrincipal Jwt p, @PathVariable UUID id) {
    return projector.report(tenant(p), id);
  }

  @PostMapping("/v1/reports/replay")
  Map<String, UUID> replay() {
    return Map.of("generation", projector.replay());
  }

  @GetMapping("/v1/reports/freshness")
  Map<String, Object> freshness() {
    return projector.freshness();
  }

  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  ResponseEntity<Map<String, String>> invalid(RuntimeException e) {
    return ResponseEntity.unprocessableContent()
        .body(
            Map.of(
                "code",
                e instanceof NullPointerException
                    ? "MISSING_FIELD"
                    : e.getMessage() == null ? "INVALID_INPUT" : e.getMessage()));
  }

  @ExceptionHandler(LedgerStore.LedgerException.class)
  ResponseEntity<Map<String, String>> db(LedgerStore.LedgerException e) {
    return ResponseEntity.status(
            e.temporary
                ? 503
                : e.code.contains("CONFLICT") || e.code.contains("REVERSED") ? 409 : 422)
        .body(
            Map.of(
                "code", e.code, "retry", e.temporary ? "same key after backoff" : "correct input"));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<Map<String, String>> malformed(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code", "MALFORMED_REQUEST"));
  }
}
