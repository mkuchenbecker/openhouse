package com.linkedin.openhouse.capabilities;

/**
 * The database and table an UNLOCK statement targets. Its components are validated before any
 * catalog operation: an implementation must only carry components that {@link #of} accepts, and
 * {@link #of} is how a target is normally built.
 *
 * <p>It is an interface so that an engine can pass through a target it already validated with
 * another client's equivalent type, unchanged, to a catalog that expects that type.
 */
public interface UnlockTableTarget {

  /** The database, verbatim. */
  String getDatabaseId();

  /** The table, verbatim. */
  String getTableId();

  /**
   * Builds a target from resolved namespace components without changing quoted identifier contents.
   * A normalizing proxy could reroute empty, {@code "."} or {@code ".."} URL segments, so those are
   * refused.
   *
   * @param namespace exactly one database component
   * @param tableId the table
   * @throws UnlockValidationException if the namespace is not one level or a component is unsafe
   */
  static UnlockTableTarget of(String[] namespace, String tableId) throws UnlockValidationException {
    if (namespace == null || namespace.length != 1) {
      throw new UnlockValidationException("UNLOCK requires exactly one database namespace");
    }
    String databaseId = namespace[0];
    ValidatedUnlockTableTarget.validatePathSegment("Database name", databaseId);
    ValidatedUnlockTableTarget.validatePathSegment("Table name", tableId);
    return new ValidatedUnlockTableTarget(databaseId, tableId);
  }
}
