package com.linkedin.openhouse.housetables.api.spec.request;

import com.linkedin.openhouse.housetables.dto.model.TableStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PUT /v1/hts/table-stats/{databaseId}/{tableName}}.
 *
 * <p>Carries only the fields the caller controls. {@code databaseId} and {@code tableName} come
 * from the path variables and are not accepted in the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertTableStatsRequest {

  /** Iceberg-assigned stable UUID for the table. Required to identify the stats row. */
  private String tableUuid;

  /** Stats payload. Snapshot fields overwrite existing values; delta fields accumulate. */
  private TableStats stats;
}
