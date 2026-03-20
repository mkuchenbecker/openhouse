package com.linkedin.openhouse.optimizer.api.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps service-layer exceptions to HTTP error responses. */
@RestControllerAdvice
public class OptimizerExceptionHandler {

  @ExceptionHandler(InvalidPatchStatusException.class)
  public ResponseEntity<Map<String, String>> handleInvalidPatchStatus(
      InvalidPatchStatusException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(OperationStateConflictException.class)
  public ResponseEntity<Map<String, String>> handleStateConflict(
      OperationStateConflictException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
  }
}
