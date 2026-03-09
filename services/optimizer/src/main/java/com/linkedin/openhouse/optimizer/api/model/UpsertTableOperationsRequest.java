package com.linkedin.openhouse.optimizer.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT request body for {@code /v1/table-operations/{db}/{table}/{operationType}}.
 *
 * <p>Carries only the fields the Analyzer supplies at upsert time. {@code databaseName}, {@code
 * tableName}, and {@code operationType} are authoritative from the URL path and are never read from
 * this body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertTableOperationsRequest {

  private OperationMetrics metrics;
}
