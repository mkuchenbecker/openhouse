package com.linkedin.openhouse.capabilities.rest;

import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.ErrorHandler;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.responses.ErrorResponse;

/**
 * How an error from an OpenHouse route reaches the SQL user. The server answers in the IRC error
 * shape with the native status and message; this handler keeps the native message and chooses the
 * exception:
 *
 * <ul>
 *   <li>400: {@link IllegalArgumentException} with the native message, the type the Java client
 *       throws for a refused GRANT or REVOKE (for example "db.t is not a shared table").
 *   <li>404: {@link NoSuchTableException} on table routes, {@link NoSuchNamespaceException} on
 *       database routes, with the native message.
 *   <li>409 (an UNLOCK whose reason does not match the lock): {@link IllegalStateException} with
 *       the native message. Iceberg's table handler would make it an "already exists".
 *   <li>Anything else as Iceberg's default handler: 401 {@code NotAuthorizedException}, 403 {@code
 *       ForbiddenException}, 500 {@code ServiceFailureException}, and so on, each with Iceberg's
 *       prefix before the native message, as for every other request of the REST catalog.
 * </ul>
 */
final class CapabilityErrorHandler extends ErrorHandler {
  static final CapabilityErrorHandler TABLE = new CapabilityErrorHandler(true);
  static final CapabilityErrorHandler NAMESPACE = new CapabilityErrorHandler(false);

  private static final ErrorHandler DEFAULT = (ErrorHandler) ErrorHandlers.defaultErrorHandler();

  private final boolean tableRoute;

  private CapabilityErrorHandler(boolean tableRoute) {
    this.tableRoute = tableRoute;
  }

  @Override
  public ErrorResponse parseResponse(int code, String json) {
    return DEFAULT.parseResponse(code, json);
  }

  @Override
  public void accept(ErrorResponse error) {
    Integer code = error.code();
    if (code != null) {
      switch (code) {
        case 400:
          throw new IllegalArgumentException(error.message());
        case 404:
          if (tableRoute) {
            throw new NoSuchTableException("%s", error.message());
          }
          throw new NoSuchNamespaceException("%s", error.message());
        case 409:
          throw new IllegalStateException(error.message());
        default:
          break;
      }
    }
    DEFAULT.accept(error);
  }
}
