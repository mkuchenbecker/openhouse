package com.linkedin.openhouse.housetables.services;

import lombok.Value;

/**
 * The service-owned vocabulary for a view query: exactly the accepted database id and optional
 * table-id pattern, nothing else. The handler parses a validated transport request into this value
 * at its boundary, so wire nullability, ignored transport fields, and the inert {@code entityType}
 * property never become part of the service contract — a view query that filters by anything else
 * is structurally unrepresentable here, since no field exists to hold such a filter. A pattern with
 * no database to scope it is rejected rather than unrepresentable: the factory methods are the only
 * constructors, and {@link #matching} rejects that combination at construction.
 */
@Value
public class UserViewQuery {

  /** Database to list views from; {@code null} lists every view across databases. */
  String databaseId;

  /**
   * Optional SQL {@code LIKE} pattern over table ids, applied case-insensitively within {@code
   * databaseId}; {@code null} on the unpatterned forms.
   */
  String tableIdPattern;

  private UserViewQuery(String databaseId, String tableIdPattern) {
    this.databaseId = databaseId;
    this.tableIdPattern = tableIdPattern;
  }

  /**
   * Every view in one database, unpatterned; a {@code null} {@code databaseId} widens the query to
   * every view across every database.
   */
  public static UserViewQuery allViews(String databaseId) {
    return new UserViewQuery(databaseId, null);
  }

  /**
   * Views in {@code databaseId} whose table id matches the {@code LIKE} pattern. Both parts are
   * required: a pattern has no scope without a database. The API validator rejects that request
   * shape at ingress, and this factory rejects it for direct callers too, with an {@link
   * IllegalArgumentException}.
   */
  public static UserViewQuery matching(String databaseId, String tableIdPattern) {
    if (databaseId == null) {
      throw new IllegalArgumentException(
          "A view query with a tableIdPattern requires a databaseId");
    }
    if (tableIdPattern == null) {
      throw new IllegalArgumentException(
          "matching requires a tableIdPattern; use allViews for an unpatterned query");
    }
    return new UserViewQuery(databaseId, tableIdPattern);
  }
}
