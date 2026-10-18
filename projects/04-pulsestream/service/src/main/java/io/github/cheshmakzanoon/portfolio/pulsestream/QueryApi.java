package io.github.cheshmakzanoon.portfolio.pulsestream;

import java.sql.SQLException;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class QueryApi {
  private final PostgresStore store;
  private final PulseApplication.Processor processor;
  private final Environment env;

  public QueryApi(PostgresStore store, PulseApplication.Processor processor, Environment env) {
    this.store = store;
    this.processor = processor;
    this.env = env;
  }

  private void authorize(String run, String tenant, String dev, Authentication auth)
      throws SQLException {
    if (!Engine.identifier(run) || !Engine.identifier(tenant))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER");
    String actual =
        env.getProperty("pulse.security", "local").equals("local")
            ? dev
            : auth != null && auth.getPrincipal() instanceof Jwt jwt
                ? jwt.getClaimAsString("tenant")
                : null;
    if (!tenant.equals(actual))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_MISMATCH");
    try (var c = store.connection();
        var p =
            c.prepareStatement("SELECT 1 FROM seen_event WHERE run_id=? AND tenant_id=? LIMIT 1")) {
      p.setString(1, run);
      p.setString(2, tenant);
      p.setQueryTimeout(5);
      try (var rs = p.executeQuery()) {
        if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
      }
    }
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, Object>> health() {
    var m = processor.metrics();
    return ResponseEntity.status(Boolean.TRUE.equals(m.get("healthy")) ? 200 : 503).body(m);
  }

  @GetMapping("/v1/runs/{run}/windows")
  public List<PostgresStore.WindowRow> windows(
      @PathVariable String run,
      @RequestParam String tenant,
      @RequestParam String sensor,
      @RequestParam String type,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "-9223372036854775808") long after,
      @RequestHeader(value = "X-Dev-Tenant", required = false) String dev,
      Authentication auth)
      throws SQLException {
    authorize(run, tenant, dev, auth);
    if (!Engine.identifier(sensor) || !Set.of("TUMBLING", "SLIDING").contains(type))
      throw new IllegalArgumentException("filter");
    return store.windows(run, tenant, sensor, type, limit, after);
  }

  @GetMapping("/v1/runs/{run}/status")
  public Map<String, Object> status(
      @PathVariable String run,
      @RequestParam String tenant,
      @RequestHeader(value = "X-Dev-Tenant", required = false) String dev,
      Authentication auth)
      throws SQLException {
    authorize(run, tenant, dev, auth);
    return store.status(run, tenant);
  }

  @GetMapping("/v1/runs/{run}/quarantine")
  public List<PostgresStore.Diagnostic> quarantine(
      @PathVariable String run,
      @RequestParam String tenant,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "100") int limit,
      @RequestHeader(value = "X-Dev-Tenant", required = false) String dev,
      Authentication auth)
      throws SQLException {
    authorize(run, tenant, dev, auth);
    return store.quarantine(run, tenant, after, limit);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> invalid() {
    return Map.of("code", "INVALID_REQUEST");
  }

  @ExceptionHandler(SQLException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  Map<String, String> unavailable() {
    return Map.of("code", "STORE_UNAVAILABLE");
  }
}
