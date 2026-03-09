package com.linkedin.openhouse.optimizer.api.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO for {@code table_stats} — used for both request bodies and response payloads. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableStatsDto {

  /** Iceberg-assigned stable UUID; must be present in PUT request bodies. */
  private String tableUuid;

  private String databaseName;
  private String tableName;

  /** Server-set; ignored in PUT request bodies. */
  private Instant lastUpdatedAt;

  /**
   * Stats payload. Delta fields are accumulated across commit events; snapshot fields are
   * overwritten. See {@link TableStats} for the field-level contract.
   */
  private TableStats stats;
}
