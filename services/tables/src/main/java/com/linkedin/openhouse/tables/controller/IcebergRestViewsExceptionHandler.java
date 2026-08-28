package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.common.audit.AuditedResponseRenderer;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestViewPaths;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestWire;
import com.linkedin.openhouse.tables.exception.ViewApiException;
import com.linkedin.openhouse.tables.exception.ViewErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Error rendering for the Iceberg REST views routes: every failure a views controller method raises
 * is serialized as the spec's {@code IcebergErrorResponse} envelope — {@code {"error": {"message",
 * "type", "code"}}} — never as the OpenHouse {@code ErrorResponseBody}.
 *
 * <p>Scoped to {@link IcebergRestViewsController} by {@code assignableTypes} and ordered ahead of
 * the global {@link com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler}, so
 * the views surface owns its own error vocabulary while every other controller's contract is
 * untouched. (401 is not rendered here: authentication is rejected by the token interceptor before
 * dispatch and stays a bare status, documented as such.)
 *
 * <p><b>Per-route 404 vocabulary:</b> the spec renders an absent namespace as {@code
 * NoSuchNamespaceException} on the create and list routes but the per-view routes (load, replace,
 * drop, exists) report {@code NoSuchViewException} for their 404s. The two route-sensitive codes
 * ({@code DATABASE_NOT_FOUND}, {@code VIEWS_DISABLED}) carry the collection-route type on the enum
 * and are swapped to {@code NoSuchViewException} here when the failing request targeted a per-view
 * route. This is what makes the views-disabled posture invisible to a stock client: {@code
 * loadView} sees a plain {@code NoSuchViewException} and Spark falls through to {@code loadTable}.
 *
 * <p><b>HEAD:</b> the exists route returns no body on any status per the spec, so the envelope is
 * suppressed for HEAD requests and only the status is sent.
 *
 * <p><b>No stack traces:</b> unlike the OpenHouse envelope, nothing here ever serializes a
 * stacktrace or cause chain; the envelope's optional {@code stack} field is deliberately never
 * populated.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = IcebergRestViewsController.class)
public class IcebergRestViewsExceptionHandler implements AuditedResponseRenderer {

  @Hidden
  @ExceptionHandler(ViewApiException.class)
  public ResponseEntity<String> handleViewApiException(
      ViewApiException exception, HttpServletRequest request) {
    ViewErrorCode errorCode = exception.getErrorCode();
    return envelope(
        request,
        errorCode.getHttpStatus(),
        resolveType(errorCode, request),
        exception.getMessage());
  }

  @Hidden
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<String> handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    return envelope(
        request,
        HttpStatus.FORBIDDEN,
        ForbiddenException.class.getSimpleName(),
        exception.getMessage());
  }

  @Hidden
  @ExceptionHandler(AuthorizationServiceException.class)
  public ResponseEntity<String> handleAuthorizationServiceFailure(
      AuthorizationServiceException exception, HttpServletRequest request) {
    return envelope(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        ServiceUnavailableException.class.getSimpleName(),
        exception.getMessage());
  }

  /**
   * A request parameter that could not be bound to its declared type (e.g. a non-numeric {@code
   * pageSize}) never reaches the handler, so it cannot be reported through validation; it is the
   * same client mistake and gets the same 400. The message is fixed: a binding failure's own
   * message echoes the offending value.
   */
  @Hidden
  @ExceptionHandler(TypeMismatchException.class)
  public ResponseEntity<String> handleParameterBindingFailure(HttpServletRequest request) {
    return envelope(
        request,
        HttpStatus.BAD_REQUEST,
        BadRequestException.class.getSimpleName(),
        "Malformed request parameter: a query or path parameter does not have its declared type");
  }

  /**
   * Anything else is a server fault. The message is fixed: an arbitrary exception's message can
   * carry internals (paths, SQL fragments from lower layers), and unlike the coded paths above it
   * was never written with the redaction invariant in mind.
   */
  @Hidden
  @ExceptionHandler(Exception.class)
  public ResponseEntity<String> handleUnexpected(Exception exception, HttpServletRequest request) {
    log.error("Unexpected failure on an Iceberg REST views route:", exception);
    return envelope(
        request, HttpStatus.INTERNAL_SERVER_ERROR, "InternalServerError", "Internal Server Error");
  }

  private static String resolveType(ViewErrorCode errorCode, HttpServletRequest request) {
    if (errorCode.isRouteSensitive404() && isViewItemRoute(request)) {
      return NoSuchViewException.class.getSimpleName();
    }
    return errorCode.getErrorType();
  }

  /**
   * Whether the failing request targeted the per-view route. Keyed off the matched handler pattern
   * when the dispatcher recorded one — the authoritative answer — with a trailing-slash-tolerant
   * URI match against the owned pattern as the fallback.
   */
  private static boolean isViewItemRoute(HttpServletRequest request) {
    Object bestMatchingPattern =
        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (bestMatchingPattern instanceof String
        && IcebergRestViewPaths.VIEW_ITEM_TEMPLATE.equals(
            stripTrailingSlash((String) bestMatchingPattern))) {
      return true;
    }
    return IcebergRestViewPaths.isViewItemUri(request.getRequestURI());
  }

  /** Trailing-slash matches record the pattern with the slash appended; normalize it away. */
  private static String stripTrailingSlash(String pattern) {
    return pattern.length() > 1 && pattern.endsWith("/")
        ? pattern.substring(0, pattern.length() - 1)
        : pattern;
  }

  private static ResponseEntity<String> envelope(
      HttpServletRequest request, HttpStatus status, String type, String message) {
    if (HttpMethod.HEAD.matches(request.getMethod())) {
      // The exists route never returns a body, success or failure.
      return ResponseEntity.status(status).build();
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(IcebergRestWire.toErrorJson(status, type, message));
  }
}
