package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestWire;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Owns the unresolved-path surface for {@code /v1/**}. The tables service runs with {@code
 * spring.mvc.throw-exception-if-no-handler-found=true}, and the global handler renders every
 * unknown path as an OpenHouse-envelope <b>400</b>. On the Iceberg REST surface that is the wrong
 * contract in both fields: a probe of protocol surface this server does not serve — {@code
 * rename-view}, {@code register-view}, the tables/namespaces REST routes, a typo — must be a plain
 * <b>404</b> in the {@code IcebergErrorResponse} envelope, which is exactly how a stock client
 * discovers the endpoint is absent (and deliberately not {@code 406}: this server does not claim
 * protocol surface it does not serve).
 *
 * <p>{@code NoHandlerFoundException} is raised before any controller is selected, so the
 * views-scoped advice cannot catch it; this advice is global, ordered ahead of the global handler,
 * and hands every non-{@code /v1} path the exact legacy body via {@link
 * OpenHouseExceptionHandler#unresolvedRouteErrorResponseBody}, so nothing changes off the {@code
 * /v1} surface.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class V1RestUnresolvedPathExceptionHandler
    implements com.linkedin.openhouse.common.audit.AuditedResponseRenderer {

  private static final String V1_PATH_PREFIX = "/v1/";

  private static final String NOT_FOUND_TYPE = "NotFoundException";

  @Hidden
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<?> handleNoHandlerFound(NoHandlerFoundException ex, WebRequest request) {
    String path = ex.getRequestURL();
    if (path != null && path.startsWith(V1_PATH_PREFIX)) {
      if (HttpMethod.HEAD.matches(ex.getHttpMethod())) {
        // Spec HEAD routes carry no body on any status.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              IcebergRestWire.toErrorJson(
                  HttpStatus.NOT_FOUND,
                  NOT_FOUND_TYPE,
                  String.format("Route does not exist: %s %s", ex.getHttpMethod(), path)));
    }
    ErrorResponseBody legacyBody =
        OpenHouseExceptionHandler.unresolvedRouteErrorResponseBody(ex, request);
    return new ResponseEntity<>(legacyBody, legacyBody.getStatus());
  }
}
