package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.common.audit.ServiceAuditAspect;
import com.linkedin.openhouse.common.exception.handler.OpenHouseExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

/**
 * Renders every failure on the IRC routes as the spec's {@code ErrorResponse}, ahead of the global
 * {@code OpenHouseExceptionHandler}, which keeps rendering the native routes' own error bodies.
 * Scoped to the IRC controllers only, by their package: naming the write or the view controller
 * would load it where its Iceberg API is absent. No stack trace is sent.
 *
 * <p>Every failure rendered here is also put in the service audit, as the event a failed native
 * request gets ({@code ServiceAuditAspect}).
 */
@Slf4j
@RestControllerAdvice(basePackageClasses = IcebergRestCatalogController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IcebergRestExceptionHandler {

  @Autowired private ServiceAuditAspect serviceAudit;

  /**
   * @param handler the route that failed: the OpenHouse routes under the base path ({@link
   *     IcebergRestOpenHouseController}) keep the native wording of their failures
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> handle(Exception failure, HandlerMethod handler) {
    ErrorResponse error =
        IcebergRestErrors.toError(
            failure,
            handler != null
                && IcebergRestOpenHouseController.class.isAssignableFrom(handler.getBeanType()));
    if (error.code() >= 500) {
      log.error("IRC request failed with {}", error.code(), failure);
    } else {
      log.info("IRC request refused with {}: {}", error.code(), error.message());
    }
    // The request audit advises the native exception handler, whose error body carries what it
    // records. This handler renders another body, so it hands the audit the same things itself:
    // the status and message answered, and the failure's stack trace and cause as the native
    // handler would have put them in its body. The stack trace still goes nowhere else.
    serviceAudit.auditFailedRequest(
        error.code(),
        error.message(),
        OpenHouseExceptionHandler.getAbbreviatedStackTrace(failure),
        OpenHouseExceptionHandler.getExceptionCause(failure));
    return IcebergRestJson.error(error);
  }
}
