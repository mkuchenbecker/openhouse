package com.linkedin.openhouse.tables.exception;

import java.util.Objects;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * The name a create-view request asked for is already taken.
 *
 * <p><b>Checked on purpose.</b> Two clients racing to create the same view, or a client naming an
 * existing table, are ordinary outcomes of the create path rather than defects — so the compiler
 * should make every caller state what it does about them. The rest of this package extends {@link
 * ViewApiException}, which is unchecked; that vocabulary is the constraint imposed by the Spring
 * advice that renders it, not a pattern to copy inward. New service-layer outcomes that a caller is
 * expected to handle belong in the signature, and the adapter at the HTTP boundary is where they
 * become an unchecked {@link ViewApiException} again.
 *
 * <p>Carrying {@link Kind} rather than splitting into two exception types keeps the caller's
 * handling in one place while still letting it distinguish the two 409s, which render the same
 * status but not the same message.
 */
public class ViewNameConflictException extends Exception {

  private static final long serialVersionUID = 1L;

  /** Which entity already holds the name. */
  public enum Kind {
    /** A view of that name already exists. */
    VIEW(ViewErrorCode.VIEW_ALREADY_EXISTS),
    /** A table of that name already exists, and tables and views share one key space. */
    TABLE(ViewErrorCode.NAME_ALREADY_EXISTS_AS_TABLE);

    private final ViewErrorCode errorCode;

    Kind(ViewErrorCode errorCode) {
      this.errorCode = errorCode;
    }

    /**
     * The wire vocabulary this conflict renders as.
     *
     * @return the error code the HTTP adapter should raise
     */
    public ViewErrorCode getErrorCode() {
      return errorCode;
    }
  }

  private final transient TableIdentifier identifier;
  private final Kind kind;

  /**
   * @param identifier the contested identifier, required
   * @param kind what already holds the name, required
   * @param message text copied verbatim into the response body, so it must never carry SQL or
   *     schema text
   */
  public ViewNameConflictException(TableIdentifier identifier, Kind kind, String message) {
    super(message);
    this.identifier = Objects.requireNonNull(identifier, "identifier is required");
    this.kind = Objects.requireNonNull(kind, "kind is required");
  }

  /** @return the contested identifier */
  public TableIdentifier getIdentifier() {
    return identifier;
  }

  /** @return what already holds the name */
  public Kind getKind() {
    return kind;
  }
}
