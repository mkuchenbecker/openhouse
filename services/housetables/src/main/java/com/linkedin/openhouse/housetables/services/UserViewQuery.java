package com.linkedin.openhouse.housetables.services;

import lombok.Builder;
import lombok.Value;

/**
 * The service-owned vocabulary for a view query: exactly the accepted database id and optional
 * table-id pattern, nothing else. The handler parses a validated transport request into this value
 * at its boundary, so wire nullability, ignored transport fields, and the inert {@code entityType}
 * property never become part of the service contract — a view query that filters by anything else
 * is structurally unrepresentable here.
 */
@Builder
@Value
public class UserViewQuery {

  /** Database to list views from; {@code null} lists every view across databases. */
  String databaseId;

  /**
   * Optional SQL {@code LIKE} pattern over table ids, applied case-insensitively within {@code
   * databaseId}. Requires {@code databaseId}: the API validator rejects a table filter without a
   * database, and this type carries the same rule for direct callers.
   */
  String tableIdPattern;
}
