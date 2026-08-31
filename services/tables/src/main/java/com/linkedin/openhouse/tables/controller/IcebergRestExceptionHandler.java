package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.common.exception.NamespaceStoreNotBackfilledException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped exception mapper for Iceberg REST endpoints. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = IcebergRestCatalogController.class)
@ConditionalOnProperty(value = "cluster.tables.iceberg-rest.enabled", havingValue = "true")
@Slf4j
public class IcebergRestExceptionHandler {

  @ExceptionHandler(NoSuchTableException.class)
  public ResponseEntity<ErrorResponse> handleNoSuchTable(NoSuchTableException e) {
    return errorResponse(404, e.getMessage(), NoSuchTableException.class.getSimpleName());
  }

  @ExceptionHandler(NoSuchNamespaceException.class)
  public ResponseEntity<ErrorResponse> handleNoSuchNamespace(NoSuchNamespaceException e) {
    return errorResponse(404, e.getMessage(), NoSuchNamespaceException.class.getSimpleName());
  }

  @ExceptionHandler(AlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleAlreadyExists(AlreadyExistsException e) {
    return errorResponse(409, e.getMessage(), AlreadyExistsException.class.getSimpleName());
  }

  @ExceptionHandler(NamespaceNotEmptyException.class)
  public ResponseEntity<ErrorResponse> handleNamespaceNotEmpty(NamespaceNotEmptyException e) {
    return errorResponse(409, e.getMessage(), NamespaceNotEmptyException.class.getSimpleName());
  }

  @ExceptionHandler(CommitFailedException.class)
  public ResponseEntity<ErrorResponse> handleCommitFailed(CommitFailedException e) {
    return errorResponse(409, e.getMessage(), CommitFailedException.class.getSimpleName());
  }

  /**
   * Two unrelated exceptions share the simple name {@code UnprocessableEntityException}:
   * OpenHouse's own, and the one {@code UpdateNamespacePropertiesRequest.validate()} raises for a
   * key present in both {@code removals} and {@code updates}. Only the first was mapped, so the
   * second reached the catch-all and a malformed request was reported as a 500 the client could not
   * tell from an outage.
   */
  @ExceptionHandler(org.apache.iceberg.exceptions.UnprocessableEntityException.class)
  public ResponseEntity<ErrorResponse> handleIcebergUnprocessableEntity(
      org.apache.iceberg.exceptions.UnprocessableEntityException e) {
    return errorResponse(422, e.getMessage(), "UnprocessableEntityException");
  }

  @ExceptionHandler(UnprocessableEntityException.class)
  public ResponseEntity<ErrorResponse> handleUnprocessableEntity(UnprocessableEntityException e) {
    return errorResponse(422, e.getMessage(), "UnprocessableEntityException");
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidation(ValidationException e) {
    return errorResponse(400, e.getMessage(), ValidationException.class.getSimpleName());
  }

  @ExceptionHandler({RequestValidationFailureException.class, IllegalArgumentException.class})
  public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
    return errorResponse(400, e.getMessage(), IllegalArgumentException.class.getSimpleName());
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleForbidden(AccessDeniedException e) {
    return errorResponse(403, "Access denied", ForbiddenException.class.getSimpleName());
  }

  @ExceptionHandler(UnsupportedOperationException.class)
  public ResponseEntity<ErrorResponse> handleNotImplemented(UnsupportedOperationException e) {
    return errorResponse(501, e.getMessage(), UnsupportedOperationException.class.getSimpleName());
  }

  /**
   * The service cannot say which namespaces exist, so it says that rather than answering. Without
   * this mapping the refusal would reach the catch-all below, and a client would be told "internal
   * server error" for a condition an operator can fix in one call — with the call itself replaced
   * by a generic string.
   */
  @ExceptionHandler(NamespaceStoreNotBackfilledException.class)
  public ResponseEntity<ErrorResponse> handleNamespaceStoreNotBackfilled(
      NamespaceStoreNotBackfilledException e) {
    return errorResponse(
        503, e.getMessage(), NamespaceStoreNotBackfilledException.class.getSimpleName());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleDefault(Exception e) {
    log.error("Unhandled Iceberg REST request failure", e);
    return errorResponse(500, "Internal server error", "InternalServerError");
  }

  private ResponseEntity<ErrorResponse> errorResponse(int statusCode, String message, String type) {
    ErrorResponse response =
        ErrorResponse.builder()
            .responseCode(statusCode)
            .withMessage(message)
            .withType(type)
            .build();
    return ResponseEntity.status(statusCode).body(response);
  }
}
