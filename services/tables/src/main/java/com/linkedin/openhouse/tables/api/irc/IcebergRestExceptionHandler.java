package com.linkedin.openhouse.tables.api.irc;

import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders every failure on the IRC routes as the spec's {@code ErrorResponse}, ahead of the global
 * {@code OpenHouseExceptionHandler}, which keeps rendering the native routes' own error bodies.
 * Scoped to the IRC controllers only. No stack trace is sent.
 */
@Slf4j
@RestControllerAdvice(
    assignableTypes = {IcebergRestCatalogController.class, IcebergRestViewController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IcebergRestExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> handle(Exception failure) {
    ErrorResponse error = IcebergRestErrors.toError(failure);
    if (error.code() >= 500) {
      log.error("IRC request failed with {}", error.code(), failure);
    } else {
      log.info("IRC request refused with {}: {}", error.code(), error.message());
    }
    return IcebergRestJson.error(error);
  }
}
