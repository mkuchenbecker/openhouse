package com.linkedin.openhouse.tables.exception;

import com.linkedin.openhouse.common.exception.CodedApiException;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * Failure of an Iceberg REST views API operation, carrying the {@link ViewErrorCode} that selects
 * both the response status and the {@code type} string serialized into the {@code
 * IcebergErrorResponse} envelope by the views exception handler.
 *
 * <p>The enum itself stays tables-local: what reaches the wire is the status plus the spec error
 * type the code maps to (with the per-route 404 swap applied by the handler), never the enum name.
 * The typed {@link #getErrorCode()} getter exists so the handler can resolve the type and so unit
 * tests can assert the internal taxonomy directly.
 *
 * <p>Messages carried by this exception are copied verbatim into the error response body and into
 * service audit events, so callers must never interpolate SQL text or schema text into them.
 *
 * <p>The code is required. Without the null check the failure would surface only when {@link
 * #getHttpStatus()} is called, which happens inside the exception handler: the resulting {@code
 * NullPointerException} would be reported as a generic 500 instead of the status the throwing site
 * intended. Rejecting the null at construction keeps the fault at its origin.
 */
public class ViewApiException extends CodedApiException {

  private static final String ERROR_CODE_REQUIRED =
      "ViewApiException requires a non-null ViewErrorCode: it selects the response status";

  private final ViewErrorCode errorCode;

  public ViewApiException(ViewErrorCode errorCode, String message) {
    super(message);
    this.errorCode = Objects.requireNonNull(errorCode, ERROR_CODE_REQUIRED);
  }

  public ViewApiException(ViewErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, ERROR_CODE_REQUIRED);
  }

  public ViewErrorCode getErrorCode() {
    return errorCode;
  }

  @Override
  public HttpStatus getHttpStatus() {
    return errorCode.getHttpStatus();
  }
}
