package io.github.cheshmakzanoon.portfolio.switchback;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Transport failure policy; unexpected programming faults remain server failures, never input
 * errors.
 */
@RestControllerAdvice
public final class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  static ResponseEntity<String> response(Object body, int status) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Json.write(body));
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

  @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
  ResponseEntity<String> invalid(RuntimeException e) {
    // Parser, path, and domain errors can contain full client-supplied values or internal paths.
    // Do not reflect exception text to an unaudited caller, even for HTTP 400 responses.
    return response(Map.of("code", "INVALID_INPUT", "message", "Invalid request"), 400);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<String> internal(Exception e) {
    if (e instanceof org.springframework.security.access.AccessDeniedException)
      return response(Map.of("code", "FORBIDDEN"), 403);
    if (e instanceof org.springframework.web.ErrorResponse error)
      return response(Map.of("code", "HTTP_ERROR"), error.getStatusCode().value());
    String incident = UUID.randomUUID().toString();
    LOG.error("Dispatch request failed; incident={}", incident, e);
    return response(Map.of("code", "INTERNAL_ERROR", "incident", incident), 500);
  }
}
