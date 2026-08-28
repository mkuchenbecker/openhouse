package com.linkedin.openhouse.tables.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.exceptions.ValidationException;
import org.springframework.http.HttpStatus;

/**
 * Internal taxonomy of view failure modes. Each value carries the HTTP status of the response and
 * the Iceberg REST error {@code type} string serialized into the {@code IcebergErrorResponse}
 * envelope on the {@code /v1} views routes.
 *
 * <p>The {@code type} strings are derived from Iceberg's own exception vocabulary at compile time
 * wherever a matching class exists, so the wire vocabulary cannot drift from what a stock {@code
 * RESTCatalog} client's {@code ErrorHandlers} understands.
 *
 * <p>Two 404 values are <b>route-sensitive</b> (see {@link #isRouteSensitive404()}): the Iceberg
 * REST spec renders an absent namespace as {@code NoSuchNamespaceException} on the create and list
 * routes but as {@code NoSuchViewException} on the per-view routes (load/replace/drop/exists). The
 * stored type here is the collection-route rendering; the views exception handler swaps in {@code
 * NoSuchViewException} on the per-view routes.
 *
 * <p>The full set is declared up front, including codes the stubbed service never emits, so later
 * milestones (view admission, dependency analysis) add behavior without a breaking change to this
 * enum. The former 422 admission mappings are gone: 422 is not part of the Iceberg REST views
 * vocabulary, so admission failures map to 400 with the distinct {@code ValidationException} type.
 */
@AllArgsConstructor
@Getter
public enum ViewErrorCode {
  NO_SUCH_VIEW(HttpStatus.NOT_FOUND, NoSuchViewException.class.getSimpleName(), false),
  VIEW_ALREADY_EXISTS(
      HttpStatus.CONFLICT,
      org.apache.iceberg.exceptions.AlreadyExistsException.class.getSimpleName(),
      false),
  NAME_ALREADY_EXISTS_AS_TABLE(
      HttpStatus.CONFLICT,
      org.apache.iceberg.exceptions.AlreadyExistsException.class.getSimpleName(),
      false),
  CONCURRENT_VIEW_MODIFICATION(
      HttpStatus.CONFLICT, CommitFailedException.class.getSimpleName(), false),
  DATABASE_NOT_FOUND(HttpStatus.NOT_FOUND, NoSuchNamespaceException.class.getSimpleName(), true),
  VIEWS_DISABLED(HttpStatus.NOT_FOUND, NoSuchNamespaceException.class.getSimpleName(), true),
  INVALID_VIEW_DEFINITION(HttpStatus.BAD_REQUEST, BadRequestException.class.getSimpleName(), false),
  UNSUPPORTED_VIEW_DIALECT(
      HttpStatus.BAD_REQUEST, BadRequestException.class.getSimpleName(), false),
  UNSUPPORTED_VIEW_SCHEMA(HttpStatus.BAD_REQUEST, BadRequestException.class.getSimpleName(), false),
  VIEW_ADMISSION_FAILED(HttpStatus.BAD_REQUEST, ValidationException.class.getSimpleName(), false),
  REQUIRED_REPRESENTATION_MISSING(
      HttpStatus.BAD_REQUEST, ValidationException.class.getSimpleName(), false),
  DEPENDENCY_CYCLE(HttpStatus.BAD_REQUEST, ValidationException.class.getSimpleName(), false),
  MAX_VIEW_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, ValidationException.class.getSimpleName(), false),
  ADMISSION_SERVICE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableException.class.getSimpleName(), false);

  private final HttpStatus httpStatus;

  /**
   * The Iceberg REST error {@code type} for this code as rendered on the create and list routes.
   * The per-view routes swap the two route-sensitive 404 values to {@code NoSuchViewException}.
   */
  private final String errorType;

  /**
   * Whether the spec's per-route 404 vocabulary applies: {@code true} for the codes rendered as
   * {@code NoSuchNamespaceException} on create/list but {@code NoSuchViewException} on the per-view
   * routes. Lombok's {@code @Getter} derives the {@code isRouteSensitive404()} accessor.
   */
  private final boolean routeSensitive404;
}
