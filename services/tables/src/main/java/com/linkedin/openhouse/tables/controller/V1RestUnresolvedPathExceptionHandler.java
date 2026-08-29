package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import com.linkedin.openhouse.common.audit.AuditedResponseRenderer;
import com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler;
import com.linkedin.openhouse.tables.api.icebergrest.IcebergRestWire;
import io.swagger.v3.oas.annotations.Hidden;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Owns the request-mapping failure surface for {@code /v1/**}: unresolved paths, unsupported
 * methods and unsupported media types. The tables service runs with {@code
 * spring.mvc.throw-exception-if-no-handler-found=true}, and the global handler renders every
 * unknown path as an OpenHouse-envelope <b>400</b>. On the Iceberg REST surface that is the wrong
 * contract in both fields: a probe of protocol surface this server does not serve — {@code
 * rename-view}, {@code register-view}, the tables/namespaces REST routes, a typo — must be a plain
 * <b>404</b> in the {@code IcebergErrorResponse} envelope, which is exactly how a stock client
 * discovers the endpoint is absent (and deliberately not {@code 406}: this server does not claim
 * protocol surface it does not serve). A wrong method or content type on a {@code /v1} path is
 * similarly rendered as the spec envelope with its true status (405/415) rather than falling into
 * the legacy shapes.
 *
 * <p>These exceptions are raised before any controller method is selected, so the views-scoped
 * advice cannot catch them; this advice is global, ordered ahead of the global handler, and hands
 * every non-{@code /v1} path the legacy behavior (the exact legacy 400 body via {@link
 * OpenHouseExceptionHandler#unresolvedRouteErrorResponseBody} for unresolved paths; the framework
 * defaults — bare status plus {@code Allow}/{@code Accept} headers — for 405/415), so nothing
 * changes off the {@code /v1} surface.
 *
 * <p><b>Message hygiene:</b> client-facing messages are fixed — the requested URL is never echoed
 * into the envelope (it is attacker-chosen text and the message is copied into audit events); the
 * method and path are logged server-side instead.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class V1RestUnresolvedPathExceptionHandler implements AuditedResponseRenderer {

  private static final String V1_PATH_PREFIX = "/v1/";

  private static final String NOT_FOUND_TYPE = NotFoundException.class.getSimpleName();

  /** Not Iceberg exception vocabulary — the spec has no 405/415 examples — but self-describing. */
  private static final String METHOD_NOT_ALLOWED_TYPE = "MethodNotAllowedException";

  private static final String UNSUPPORTED_MEDIA_TYPE_TYPE = "UnsupportedMediaTypeException";

  @Hidden
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<?> handleNoHandlerFound(NoHandlerFoundException ex, WebRequest request) {
    String path = ex.getRequestURL();
    if (path != null && path.startsWith(V1_PATH_PREFIX)) {
      log.info("Unresolved /v1 route probed: {} {}", ex.getHttpMethod(), path);
      if (HttpMethod.HEAD.matches(ex.getHttpMethod())) {
        // Spec HEAD routes carry no body on any status.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              IcebergRestWire.toErrorJson(
                  HttpStatus.NOT_FOUND, NOT_FOUND_TYPE, "Route does not exist"));
    }
    ErrorResponseBody legacyBody =
        OpenHouseExceptionHandler.unresolvedRouteErrorResponseBody(ex, request);
    return new ResponseEntity<>(legacyBody, legacyBody.getStatus());
  }

  /**
   * A known {@code /v1} path probed with a method it does not serve: 405 in the spec envelope, with
   * the {@code Allow} header either way. Off {@code /v1}, the framework default (bare 405 plus
   * {@code Allow}) is reproduced, which is what the global handler's base class rendered before
   * this advice took precedence.
   */
  @Hidden
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    if (ex.getSupportedHttpMethods() != null) {
      headers.setAllow(ex.getSupportedHttpMethods());
    }
    if (isV1Request(request)) {
      log.info("Unsupported method on a /v1 route: {} {}", ex.getMethod(), request.getRequestURI());
      if (HttpMethod.HEAD.matches(request.getMethod())) {
        return new ResponseEntity<>(null, headers, HttpStatus.METHOD_NOT_ALLOWED);
      }
      headers.setContentType(MediaType.APPLICATION_JSON);
      return new ResponseEntity<>(
          IcebergRestWire.toErrorJson(
              HttpStatus.METHOD_NOT_ALLOWED,
              METHOD_NOT_ALLOWED_TYPE,
              "The route exists but does not support this method"),
          headers,
          HttpStatus.METHOD_NOT_ALLOWED);
    }
    return new ResponseEntity<>(null, headers, HttpStatus.METHOD_NOT_ALLOWED);
  }

  /**
   * A known {@code /v1} route addressed with a content type it does not consume: 415 in the spec
   * envelope. Off {@code /v1}, the framework default (bare 415 plus the supported types in {@code
   * Accept}) is reproduced.
   */
  @Hidden
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<?> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    if (!ex.getSupportedMediaTypes().isEmpty()) {
      headers.setAccept(ex.getSupportedMediaTypes());
    }
    if (isV1Request(request)) {
      log.info(
          "Unsupported content type on a /v1 route: {} {}",
          request.getMethod(),
          request.getRequestURI());
      headers.setContentType(MediaType.APPLICATION_JSON);
      return new ResponseEntity<>(
          IcebergRestWire.toErrorJson(
              HttpStatus.UNSUPPORTED_MEDIA_TYPE,
              UNSUPPORTED_MEDIA_TYPE_TYPE,
              "The route consumes application/json"),
          headers,
          HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
    return new ResponseEntity<>(null, headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
  }

  private static boolean isV1Request(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri != null && uri.startsWith(V1_PATH_PREFIX);
  }
}
