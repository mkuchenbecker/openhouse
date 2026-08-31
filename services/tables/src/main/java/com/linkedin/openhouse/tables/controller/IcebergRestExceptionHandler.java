package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import com.linkedin.openhouse.common.exception.InvalidTableMetadataException;
import com.linkedin.openhouse.common.exception.NamespaceStoreNotBackfilledException;
import com.linkedin.openhouse.common.exception.NoSuchSoftDeletedUserTableException;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.exception.OpenHouseCommitStateUnknownException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.TableMetadataFileNotFoundException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
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

  private static final String COMMIT_FAILURE_PREFIX = "Cannot commit";

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

  /**
   * OpenHouse's service layer raises its own exceptions, not Iceberg's, and until the write routes
   * existed none of them could reach this facade -- the read routes translate at the handler. Every
   * one of them below has a settled meaning and an obvious status; without these mappings they all
   * fell through to the catch-all, and a client was told "internal server error" for a table it had
   * already created, a schema change the catalog declines, or a concurrent write it should retry.
   * Worse, the Iceberg client reads a 500 on a commit as {@code CommitStateUnknownException} --
   * "the write may or may not have landed, clean up by hand" -- which is precisely the wrong thing
   * to say about a commit the server refused outright.
   */
  @ExceptionHandler(com.linkedin.openhouse.common.exception.AlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleOpenHouseAlreadyExists(
      com.linkedin.openhouse.common.exception.AlreadyExistsException e) {
    return errorResponse(409, e.getMessage(), AlreadyExistsException.class.getSimpleName());
  }

  @ExceptionHandler({NoSuchUserTableException.class, NoSuchSoftDeletedUserTableException.class})
  public ResponseEntity<ErrorResponse> handleNoSuchUserTable(RuntimeException e) {
    return errorResponse(404, e.getMessage(), NoSuchTableException.class.getSimpleName());
  }

  @ExceptionHandler(EntityConcurrentModificationException.class)
  public ResponseEntity<ErrorResponse> handleConcurrentModification(
      EntityConcurrentModificationException e) {
    return errorResponse(
        409, commitFailureMessage(e.getMessage()), CommitFailedException.class.getSimpleName());
  }

  /**
   * A commit whose outcome really is unknown. Reported as 500 because that is the status the
   * Iceberg client turns back into {@code CommitStateUnknownException}; a 503 would be read as
   * "retry", and retrying a commit that may have landed is how a table ends up with the same
   * snapshot twice.
   */
  @ExceptionHandler(OpenHouseCommitStateUnknownException.class)
  public ResponseEntity<ErrorResponse> handleCommitStateUnknown(
      OpenHouseCommitStateUnknownException e) {
    return errorResponse(500, e.getMessage(), "CommitStateUnknownException");
  }

  @ExceptionHandler({
    UnsupportedClientOperationException.class,
    InvalidSchemaEvolutionException.class
  })
  public ResponseEntity<ErrorResponse> handleUnsupportedClientOperation(RuntimeException e) {
    return errorResponse(400, e.getMessage(), "BadRequestException");
  }

  /** A file the catalog points at but storage no longer holds, reported by Iceberg unchanged. */
  @ExceptionHandler(org.apache.iceberg.exceptions.NotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(
      org.apache.iceberg.exceptions.NotFoundException e) {
    return errorResponse(404, e.getMessage(), "NotFoundException");
  }

  /**
   * The same condition as above, one wrapper further out. A table whose metadata.json has gone
   * missing is reported by the catalog as {@link TableMetadataFileNotFoundException}, and it is a
   * missing resource for the same reason the bare Iceberg exception above is -- the wrapper does
   * not change what happened, only who is telling us.
   *
   * <p>The wider {@link InvalidTableMetadataException} is deliberately not mapped here. It carries
   * metadata that is present but unreadable, which is a server problem rather than a missing
   * resource, and it keeps the catch-all's 500. This edge no longer decides which of the two it is
   * by unwrapping {@code getCause()}: the catalog knew at the point of failure and says so in the
   * type it throws.
   */
  @ExceptionHandler(TableMetadataFileNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleMissingMetadataFile(
      TableMetadataFileNotFoundException e) {
    return errorResponse(404, e.getMessage(), "NotFoundException");
  }

  @ExceptionHandler(NamespaceNotEmptyException.class)
  public ResponseEntity<ErrorResponse> handleNamespaceNotEmpty(NamespaceNotEmptyException e) {
    return errorResponse(409, e.getMessage(), NamespaceNotEmptyException.class.getSimpleName());
  }

  @ExceptionHandler(CommitFailedException.class)
  public ResponseEntity<ErrorResponse> handleCommitFailed(CommitFailedException e) {
    return errorResponse(
        409, commitFailureMessage(e.getMessage()), CommitFailedException.class.getSimpleName());
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

  /**
   * What a client is told when a commit does not go through.
   *
   * <p>The Iceberg client re-wraps a 409 on a commit as {@code CommitFailedException("Commit
   * failed: " + message)} and hands that message straight to the caller, so this string is the
   * whole of what an operator sees. A catalog that evaluates {@code UpdateRequirement}s server-side
   * reports the specification's "Requirement failed: ..." wording, which names the precondition but
   * never says what happened to the commit; Iceberg's own local commit path says "Cannot commit",
   * which is the sentence a client (and the reference conformance suite) looks for. Both belong in
   * the message: the outcome first, then the precondition that produced it.
   */
  private static String commitFailureMessage(String message) {
    if (message == null || message.isEmpty()) {
      return COMMIT_FAILURE_PREFIX;
    }
    return message.startsWith(COMMIT_FAILURE_PREFIX)
        ? message
        : COMMIT_FAILURE_PREFIX + ": " + message;
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
