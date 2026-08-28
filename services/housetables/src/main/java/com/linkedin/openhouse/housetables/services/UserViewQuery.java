package com.linkedin.openhouse.housetables.services;

import lombok.Value;

/**
 * The service-owned vocabulary for a view query: exactly the accepted database id and optional
 * table-id pattern, nothing else. The handler parses a validated transport request into this value
 * at its boundary, so wire nullability, ignored transport fields, and the inert {@code entityType}
 * property never become part of the service contract — a view query that filters by anything else
 * is structurally unrepresentable here, and so is a pattern with no database to scope it: the
 * factory methods are the only constructors, and {@link #matching} requires both parts.
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

  /** Every view across every database. */
  public static UserViewQuery allViews() {
    return new UserViewQuery(null, null);
  }

  /**
   * Every view in one database; {@code null} keeps the all-databases semantics of {@link
   * #allViews()}.
   */
  public static UserViewQuery allViews(String databaseId) {
    return new UserViewQuery(databaseId, null);
  }

  /**
   * Views in {@code databaseId} whose table id matches the {@code LIKE} pattern. Both parts are
   * required: a pattern has no scope without a database (the API validator rejects that request
   * shape at ingress, and this factory makes it unrepresentable for direct callers too).
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
