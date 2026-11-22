package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.CodedApiException;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.common.exception.DependencyUnavailableException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.HistoricPartitionColumnRemovedException;
import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.exception.OpenHouseCommitStateUnknownException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.StorageDependencyUnavailableException;
import com.linkedin.openhouse.common.exception.SystemOnlyLockAccessDeniedException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.AuthorizationServiceException;

/**
 * Maps a failure on an IRC route to the spec's {@code ErrorResponse}. The status is the one the
 * native API answers for the same failure, so both APIs agree; the {@code type} is the name of the
 * Iceberg exception {@code RESTCatalog} raises for that status (its error handlers use the type to
 * tell a missing namespace from a missing table). Where Iceberg has no exception for a native
 * status (422 capability missing, 423 locked), the type is the OpenHouse exception's name and the
 * client raises its generic {@code RESTException} with the native message.
 */
public final class IcebergRestErrors {

  /** The native API's wording of a conflict ({@code OpenHouseExceptionHandler}). */
  private static final String CONFLICT_MSG_TMPL =
      "Entity with key[%s] is modified by another process already, nested exception message: %s";

  /** The native API's wording of an unknown commit state ({@code OpenHouseExceptionHandler}). */
  private static final String COMMIT_STATE_UNKNOWN_TMPL =
      "Table with key[%s] isn't ack'ed by server about the commit status, nested exception: %s";

  private IcebergRestErrors() {}

  public static ErrorResponse toError(Throwable failure) {
    String message = failure.getMessage() != null ? failure.getMessage() : failure.toString();
    if (failure instanceof NoSuchUserTableException || failure instanceof NoSuchTableException) {
      return error(HttpStatus.NOT_FOUND, NoSuchTableException.class, message);
    }
    if (failure instanceof NoSuchNamespaceException) {
      return error(HttpStatus.NOT_FOUND, NoSuchNamespaceException.class, message);
    }
    if (failure instanceof IcebergRestRouteException) {
      return ((IcebergRestRouteException) failure).isSpecRoute()
          ? error(HttpStatus.NOT_ACCEPTABLE, UnsupportedOperationException.class, message)
          : error(HttpStatus.BAD_REQUEST, BadRequestException.class, message);
    }
    if (failure instanceof EntityConcurrentModificationException) {
      // The core's guards refused a commit built on a base that has moved; the client may retry.
      EntityConcurrentModificationException conflict =
          (EntityConcurrentModificationException) failure;
      return error(
          HttpStatus.CONFLICT,
          CommitFailedException.class,
          String.format(
              CONFLICT_MSG_TMPL,
              conflict.getEntityId(),
              conflict.getCause() != null ? conflict.getCause().getMessage() : message));
    }
    if (failure instanceof CommitFailedException) {
      return error(HttpStatus.CONFLICT, CommitFailedException.class, message);
    }
    if (failure instanceof OpenHouseCommitStateUnknownException) {
      OpenHouseCommitStateUnknownException unknown = (OpenHouseCommitStateUnknownException) failure;
      return commitStateUnknown(
          String.format(
              COMMIT_STATE_UNKNOWN_TMPL,
              unknown.getTableId(),
              unknown.getCause() != null ? unknown.getCause().getMessage() : message));
    }
    if (failure instanceof CommitStateUnknownException) {
      return commitStateUnknown(message);
    }
    if (failure instanceof AlreadyExistsException
        || failure instanceof org.apache.iceberg.exceptions.AlreadyExistsException) {
      return error(
          HttpStatus.CONFLICT, org.apache.iceberg.exceptions.AlreadyExistsException.class, message);
    }
    if (failure instanceof InvalidSchemaEvolutionException
        || failure instanceof HistoricPartitionColumnRemovedException
        || failure instanceof CommitRejectedException
        || failure instanceof ValidationException) {
      return error(HttpStatus.BAD_REQUEST, BadRequestException.class, message);
    }
    if (failure instanceof UnprocessableEntityException) {
      return error(HttpStatus.UNPROCESSABLE_ENTITY, failure.getClass(), message);
    }
    if (failure instanceof RequestValidationFailureException
        || failure instanceof BadRequestException
        || failure instanceof IllegalArgumentException) {
      return error(HttpStatus.BAD_REQUEST, BadRequestException.class, message);
    }
    if (failure instanceof AccessDeniedException || failure instanceof ForbiddenException) {
      return error(HttpStatus.FORBIDDEN, ForbiddenException.class, message);
    }
    if (failure instanceof NotAuthorizedException) {
      return error(HttpStatus.UNAUTHORIZED, NotAuthorizedException.class, message);
    }
    if (failure instanceof AuthorizationServiceException
        || failure instanceof StorageDependencyUnavailableException
        || failure instanceof DependencyUnavailableException) {
      return error(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class, message);
    }
    if (failure instanceof SystemOnlyLockAccessDeniedException) {
      return error(HttpStatus.LOCKED, failure.getClass(), message);
    }
    if (failure instanceof UnsupportedClientOperationException) {
      return error(HttpStatus.BAD_REQUEST, BadRequestException.class, message);
    }
    if (failure instanceof CodedApiException) {
      HttpStatus status = ((CodedApiException) failure).getHttpStatus();
      return error(status, typeFor(status, failure.getClass()), message);
    }
    return error(HttpStatus.INTERNAL_SERVER_ERROR, ServiceFailureException.class, message);
  }

  /**
   * The commit's outcome is not known. The native API answers 503, which Iceberg's clients read as
   * "service unavailable" and may treat as a failed commit; 500 with the spec's own type is read as
   * {@code CommitStateUnknownException} by the commit error handler of both the 1.5.2 and the 1.12
   * clients, so the client keeps the files the commit may have published.
   */
  private static ErrorResponse commitStateUnknown(String message) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, CommitStateUnknownException.class, message);
  }

  /** The error for a request the token interceptor turned away with {@code status}. */
  public static ErrorResponse authenticationFailure(HttpStatus status, String message) {
    return error(status, typeFor(status, NotAuthorizedException.class), message);
  }

  private static Class<?> typeFor(HttpStatus status, Class<?> fallback) {
    switch (status) {
      case BAD_REQUEST:
        return BadRequestException.class;
      case UNAUTHORIZED:
        return NotAuthorizedException.class;
      case FORBIDDEN:
        return ForbiddenException.class;
      case NOT_FOUND:
        return NoSuchTableException.class;
      case SERVICE_UNAVAILABLE:
        return ServiceUnavailableException.class;
      case INTERNAL_SERVER_ERROR:
        return ServiceFailureException.class;
      default:
        return fallback;
    }
  }

  private static ErrorResponse error(HttpStatus status, Class<?> type, String message) {
    return ErrorResponse.builder()
        .responseCode(status.value())
        .withType(type.getSimpleName())
        .withMessage(message)
        .build();
  }
}
