package com.linkedin.openhouse.tables.exception;

import java.util.Objects;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * A view commit was rejected because the view moved under the writer.
 *
 * <p><b>Checked on purpose,</b> for the reason given on {@link ViewNameConflictException}: losing a
 * race is an expected outcome of a compare-and-swap commit, not a defect, and a caller that has not
 * decided what to do about it has not finished writing the commit path.
 *
 * <p>This is the one failure on the views surface a client can usefully retry — an {@code
 * assert-view-uuid} requirement failing means the client's base is stale, and re-reading and
 * re-committing may succeed. Iceberg's {@code ViewCommitErrorHandler} turns the 409 this becomes
 * into {@code CommitFailedException}, which its own commit loops treat as retriable, so the message
 * must survive the trip: it is prefixed with {@code "Commit failed: "} by that handler and surfaced
 * to the user.
 */
public class ViewCommitConflictException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient TableIdentifier identifier;

  /**
   * @param identifier the view whose commit was rejected, required
   * @param message text copied verbatim into the response body, so it must never carry SQL or
   *     schema text
   */
  public ViewCommitConflictException(TableIdentifier identifier, String message) {
    super(message);
    this.identifier = Objects.requireNonNull(identifier, "identifier is required");
  }

  /**
   * @param identifier the view whose commit was rejected, required
   * @param message text copied verbatim into the response body
   * @param cause the underlying commit failure, kept so the storage-level detail is not lost when
   *     the adapter rewraps this for the wire
   */
  public ViewCommitConflictException(TableIdentifier identifier, String message, Throwable cause) {
    super(message, cause);
    this.identifier = Objects.requireNonNull(identifier, "identifier is required");
  }

  /** @return the view whose commit was rejected */
  public TableIdentifier getIdentifier() {
    return identifier;
  }
}
