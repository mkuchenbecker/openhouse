package com.linkedin.openhouse.optimizer.api.model;

import java.time.Instant;
import java.util.Map;
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
   * Stats payload: {@code cluster_id}, {@code table_version}, {@code num_snapshots}, {@code
   * table_location}, {@code operation_type}, {@code num_files_added}, {@code num_files_deleted},
   * {@code table_size_bytes}.
   */
  private Map<String, Object> stats;
}
