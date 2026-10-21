package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorResponses {
  @ExceptionHandler(SecurityException.class)
  ResponseEntity<Map<String, String>> denied() {
    return error(403, "ACCESS_DENIED");
  }

  @ExceptionHandler(IngestionJob.Conflict.class)
  ResponseEntity<Map<String, String>> conflict() {
    return error(409, "IDEMPOTENCY_CONFLICT");
  }

  @ExceptionHandler(GenerationPublisher.RetiredGeneration.class)
  ResponseEntity<Map<String, String>> retired() {
    return error(410, "GENERATION_RETIRED");
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> malformed() {
    return error(400, "INVALID_REQUEST");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> invalid() {
    return error(400, "INVALID_REQUEST");
  }

  @ExceptionHandler(IngestionTasks.Stopping.class)
  ResponseEntity<Map<String, String>> stopping() {
    return error(503, "SERVICE_STOPPING");
  }

  @ExceptionHandler(RejectedExecutionException.class)
  ResponseEntity<Map<String, String>> overload() {
    return error(429, "QUEUE_FULL");
  }

  @ExceptionHandler({IOException.class, UncheckedIOException.class})
  ResponseEntity<Map<String, String>> io() {
    return error(422, "SOURCE_OR_STORAGE_FAILURE");
  }

  private ResponseEntity<Map<String, String>> error(int status, String code) {
    return ResponseEntity.status(status).body(Map.of("error", code));
  }
}
