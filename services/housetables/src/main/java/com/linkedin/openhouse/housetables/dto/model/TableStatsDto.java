package com.linkedin.openhouse.housetables.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO for {@code table_stats} — used for both request bodies and response payloads. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TableStatsDto {

  private String databaseId;
  private String tableId;

  /** Iceberg-assigned stable UUID; must be present in PUT request bodies for new rows. */
  private String tableUuid;

  // --- Snapshot fields (overwritten on every upsert) ---

  private String clusterId;
  private String tableVersion;
  private String tableLocation;
  private Integer numSnapshots;
  private Long tableSizeBytes;

  // --- Delta fields (accumulated across commit events) ---

  /** Increment of data files added in this commit event. */
  private Long numFilesAdded;

  /** Increment of data files deleted in this commit event. */
  private Long numFilesDeleted;
}
