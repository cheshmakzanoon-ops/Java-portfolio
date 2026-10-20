package io.github.cheshmakzanoon.portfolio.routeweaver;

import static io.github.cheshmakzanoon.portfolio.routeweaver.Model.*;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
public final class Api {
  private final Store store;
  private final String sourceRevision;
  private final String authMode;

  public Api(
      Store store,
      @Value("${routeweaver.source-revision:working-tree-unverified}") String sourceRevision,
      @Value("${routeweaver.auth-mode:protected}") String authMode) {
    this.store = store;
    this.sourceRevision = sourceRevision;
    this.authMode = authMode;
  }

  private String tenant(Authentication auth) {
    if (auth instanceof JwtAuthenticationToken jwt)
      return jwt.getToken().getClaimAsString("tenant");
    if ("dev".equals(authMode) && auth != null) return "demo";
    throw new IllegalArgumentException("authenticated tenant required");
  }

  private ResponseEntity<String> response(Object body, int status) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Json.write(body));
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "UP", "product", "RouteWeaver");
  }

  @PostMapping("/v1/problems")
  public ResponseEntity<String> create(Authentication auth, @RequestBody String body) {
    Problem p = Json.read(body, Problem.class);
    if (!tenant(auth).equals(p.tenant()))
      throw new org.springframework.security.access.AccessDeniedException("tenant mismatch");
    return response(store.create(p), 201);
  }

  @GetMapping("/v1/problems/{id}")
  public ResponseEntity<String> problem(
      Authentication a, @PathVariable String id, @RequestParam(required = false) Long version) {
    String t = tenant(a);
    return response(store.load(t, id, version == null ? store.active(t, id) : version), 200);
  }

  @PostMapping("/v1/problems/{id}/solve")
  public ResponseEntity<String> solve(
      Authentication a, @PathVariable String id, @RequestBody String body) {
    return response(
        store.submit(
            tenant(a),
            id,
            Json.read(body, Store.SolveRequest.class),
            sourceRevision,
            Dispatcher.MAX_OUTSTANDING),
        202);
  }

  @GetMapping("/v1/solve-jobs/{id}")
  public ResponseEntity<String> job(Authentication a, @PathVariable UUID id) {
    return response(store.job(tenant(a), id), 200);
  }

  @GetMapping("/v1/solve-jobs/{id}/trace")
  public ResponseEntity<String> trace(
      Authentication a,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit) {
    return response(store.trace(tenant(a), id, offset, limit), 200);
  }

  @GetMapping("/v1/solve-jobs/{id}/baseline")
  public ResponseEntity<String> baseline(Authentication a, @PathVariable UUID id) {
    return response(store.baseline(tenant(a), id), 200);
  }

  @PostMapping("/v1/solve-jobs/{id}/cancel")
  public ResponseEntity<String> cancel(Authentication a, @PathVariable UUID id) {
    return response(store.cancel(tenant(a), id), 200);
  }

  @GetMapping("/v1/plans/{id}")
  public ResponseEntity<String> plan(Authentication a, @PathVariable String id) {
    return response(store.plan(tenant(a), id), 200);
  }

  @PostMapping("/v1/plans/{id}/publish")
  public ResponseEntity<String> publish(
      Authentication a, @PathVariable String id, @RequestBody String body) {
    return response(store.publish(tenant(a), id, Json.read(body, Store.PublishRequest.class)), 200);
  }

  public record ReplanRequest(
      long expectedVersion,
      long expectedRevision,
      Instant planningInstant,
      List<Replanner.Observation> observations,
      List<Job> urgent) {
    public ReplanRequest {
      if (expectedVersion < 1 || expectedRevision < 0 || planningInstant == null)
        throw new IllegalArgumentException(
            "positive version, revision and planning instant required");
      observations = List.copyOf(observations);
      urgent = List.copyOf(urgent);
      if (planningInstant.getNano() != 0
          || observations.size() > MAX_VEHICLES
          || urgent.size() > MAX_JOBS)
        throw new IllegalArgumentException(
            "replan observations, jobs and integer instant must be bounded");
    }
  }

  @PostMapping("/v1/plans/{id}/replan")
  public ResponseEntity<String> replan(
      Authentication a, @PathVariable String id, @RequestBody String body) {
    ReplanRequest r = Json.read(body, ReplanRequest.class);
    return response(
        store.replan(
            tenant(a),
            id,
            r.expectedVersion(),
            r.expectedRevision(),
            r.planningInstant(),
            r.observations(),
            r.urgent()),
        201);
  }

  @PostMapping("/v1/demo/reset")
  public ResponseEntity<String> reset(Authentication a) {
    if (!"dev".equals(authMode) || !"demo".equals(tenant(a)))
      throw new org.springframework.security.access.AccessDeniedException("development reset only");
    store.resetDemo();
    return response(Map.of("reset", "demo tenant only"), 200);
  }

  @ExceptionHandler(Store.Conflict.class)
  ResponseEntity<String> conflict(Store.Conflict e) {
    return response(Map.of("code", "CONFLICT", "message", e.getMessage()), 409);
  }

  @ExceptionHandler(Store.Missing.class)
  ResponseEntity<String> missing() {
    return response(Map.of("code", "NOT_FOUND"), 404);
  }

  @ExceptionHandler(Store.Busy.class)
  ResponseEntity<String> busy() {
    return response(Map.of("code", "SOLVER_CAPACITY", "retryAfterSeconds", 1), 429);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    ArithmeticException.class,
    NullPointerException.class
  })
  ResponseEntity<String> invalid(RuntimeException e) {
    return response(
        Map.of(
            "code",
            "INVALID_INPUT",
            "message",
            e instanceof NullPointerException
                ? "required field missing"
                : Objects.toString(e.getMessage(), "invalid input")),
        400);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<String> internal(Exception e) {
    if (e instanceof org.springframework.security.access.AccessDeniedException)
      return response(Map.of("code", "FORBIDDEN"), 403);
    if (e instanceof org.springframework.web.ErrorResponse error)
      return response(Map.of("code", "HTTP_ERROR"), error.getStatusCode().value());
    return response(Map.of("code", "INTERNAL_ERROR"), 500);
  }
}
