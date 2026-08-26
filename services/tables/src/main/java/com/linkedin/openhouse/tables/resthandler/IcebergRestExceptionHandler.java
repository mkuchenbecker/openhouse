package com.linkedin.openhouse.tables.resthandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps commit-path exceptions to Iceberg REST spec {@code ErrorResponse} bodies for the prototype
 * commit route only ({@code assignableTypes} keeps every legacy endpoint on the existing OpenHouse
 * error envelope).
 *
 * <p>Status mapping follows the REST spec / {@code ErrorHandlers} semantics rather than the legacy
 * OpenHouse envelope:
 *
 * <ul>
 *   <li>requirement failure or lost store-level race (retries exhausted) → 409 {@code
 *       CommitFailedException}: the commit is known NOT to have happened; clients refresh and
 *       re-derive.
 *   <li>ambiguous persistence failure → 500 {@code CommitStateUnknownException}: the commit may or
 *       may not have happened; clients must NOT blind-retry (stock clients keep the uncommitted
 *       metadata files and probe).
 *   <li>invalid update/requirement payloads → 400; unknown table → 404.
 * </ul>
 */
@RestControllerAdvice(assignableTypes = IcebergRestCommitController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class IcebergRestExceptionHandler {

  @Autowired private IcebergRestSerde icebergRestSerde;

  @ExceptionHandler(CommitFailedException.class)
  public ResponseEntity<String> handleCommitFailed(CommitFailedException e)
      throws JsonProcessingException {
    return toErrorResponse(HttpStatus.CONFLICT, CommitFailedException.class.getSimpleName(), e);
  }

  @ExceptionHandler(CommitStateUnknownException.class)
  public ResponseEntity<String> handleCommitStateUnknown(CommitStateUnknownException e)
      throws JsonProcessingException {
    // 500 (not 503): per the REST spec, 5xx-ambiguous means "state unknown - do not assume the
    // commit failed"; 503 would signal a retryable not-committed condition.
    return toErrorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR, CommitStateUnknownException.class.getSimpleName(), e);
  }

  @ExceptionHandler(NoSuchTableException.class)
  public ResponseEntity<String> handleNoSuchTable(NoSuchTableException e)
      throws JsonProcessingException {
    return toErrorResponse(HttpStatus.NOT_FOUND, NoSuchTableException.class.getSimpleName(), e);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<String> handleBadRequest(BadRequestException e)
      throws JsonProcessingException {
    return toErrorResponse(HttpStatus.BAD_REQUEST, BadRequestException.class.getSimpleName(), e);
  }

  /** A {@code TableMetadata.Builder} rejecting an update surfaces as ValidationException. */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<String> handleValidation(ValidationException e)
      throws JsonProcessingException {
    return toErrorResponse(HttpStatus.BAD_REQUEST, ValidationException.class.getSimpleName(), e);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e)
      throws JsonProcessingException {
    return toErrorResponse(
        HttpStatus.BAD_REQUEST, IllegalArgumentException.class.getSimpleName(), e);
  }

  private ResponseEntity<String> toErrorResponse(HttpStatus status, String type, Exception e)
      throws JsonProcessingException {
    log.info("REST commit request failed with {} ({}): {}", status, type, e.getMessage());
    ErrorResponse body =
        ErrorResponse.builder()
            .responseCode(status.value())
            .withType(type)
            .withMessage(e.getMessage())
            .build();
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(icebergRestSerde.toJson(body));
  }
}
