package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.common.exception.CodedApiException;
import com.linkedin.openhouse.common.exception.DependencyUnavailableException;
import com.linkedin.openhouse.common.exception.NoSuchUserTableException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.StorageDependencyUnavailableException;
import com.linkedin.openhouse.common.exception.SystemOnlyLockAccessDeniedException;
import com.linkedin.openhouse.common.exception.UnsupportedClientOperationException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
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
