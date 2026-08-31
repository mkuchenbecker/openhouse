package com.linkedin.openhouse.common.exception;

/**
 * The metadata file a table points at is not in storage. The catalog knows this at the point of
 * failure -- the file system said the file is not there -- so it says so, rather than collapsing
 * the outcome into "invalid metadata" and leaving every caller to re-derive the distinction by
 * unwrapping {@code getCause()}.
 *
 * <p>It stays a subtype of {@link InvalidTableMetadataException} deliberately. Every existing
 * handler and caller of the supertype keeps catching it and keeps answering exactly what it
 * answered before; only a caller that wants the narrower case has to name it. The message is the
 * supertype's, unchanged, because it is already in client-visible response bodies.
 *
 * <p>The sibling case -- a file that is present but does not parse, or parses into metadata Iceberg
 * rejects -- remains a plain {@link InvalidTableMetadataException}. That is a server problem, not a
 * missing resource, and the two must not be answered the same way.
 */
public class TableMetadataFileNotFoundException extends InvalidTableMetadataException {

  public TableMetadataFileNotFoundException(
      String databaseId, String tableId, String reason, Throwable cause) {
    super(databaseId, tableId, reason, cause);
  }
}
