package com.linkedin.openhouse.common.exception.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.common.api.spec.ErrorResponseBody;
import java.lang.reflect.Method;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * The native routes' rendering of "the namespace does not exist".
 *
 * <p>Refusing a table write into a database whose parent namespace has no row is the caller's
 * mistake and the caller can fix it, so the surface it reaches has to say so. Without a mapping the
 * exception falls to {@code handleGenericException} and the client gets a 500 it cannot tell from
 * an outage — it would retry rather than create the parent.
 *
 * <p>Resolved through {@link ExceptionHandlerMethodResolver}, which is the same resolution Spring
 * performs at dispatch time, rather than by calling the handler method directly: what has to hold
 * is that this exception reaches this method, and a direct call assumes exactly that.
 */
public class OpenHouseExceptionHandlerTest {

  @Test
  @SuppressWarnings("unchecked")
  void anAbsentNamespaceIsNotFoundRatherThanTheCatchAll() throws Exception {
    NoSuchNamespaceException exception =
        new NoSuchNamespaceException("Namespace does not exist: %s", "a");

    Method resolved =
        new ExceptionHandlerMethodResolver(OpenHouseExceptionHandler.class)
            .resolveMethod(exception);
    assertThat(resolved).isNotNull();
    assertThat(resolved.getName()).isEqualTo("handleNoSuchNamespace");

    resolved.setAccessible(true);
    ResponseEntity<ErrorResponseBody> response =
        (ResponseEntity<ErrorResponseBody>)
            resolved.invoke(new OpenHouseExceptionHandler(), exception);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().getMessage()).contains("Namespace does not exist: a");
  }

  /**
   * Calibration for the test above: deleting the new handler leaves the resolver picking {@code
   * handleGenericException}, whose rendering is asserted here so that "not the catch-all" names a
   * concrete alternative rather than an absence.
   */
  @Test
  @SuppressWarnings("unchecked")
  void anUnmappedExceptionStillFallsToTheCatchAllAsAServerError() throws Exception {
    Exception exception = new IllegalCallerException("something nobody mapped");

    Method resolved =
        new ExceptionHandlerMethodResolver(OpenHouseExceptionHandler.class)
            .resolveMethod(exception);
    assertThat(resolved.getName()).isEqualTo("handleGenericException");

    resolved.setAccessible(true);
    ResponseEntity<ErrorResponseBody> response =
        (ResponseEntity<ErrorResponseBody>)
            resolved.invoke(new OpenHouseExceptionHandler(), exception);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
