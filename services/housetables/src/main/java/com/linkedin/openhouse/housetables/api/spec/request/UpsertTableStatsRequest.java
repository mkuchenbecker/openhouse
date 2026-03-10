package com.linkedin.openhouse.housetables.api.spec.request;

import com.linkedin.openhouse.housetables.dto.model.TableStats;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PUT /v1/hts/table-stats/{tableUuid}}.
 *
 * <p>{@code tableUuid} is the authoritative identity and comes from the path variable. {@code
 * databaseId} and {@code tableName} are denormalized display columns carried in the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertTableStatsRequest {

  /** Database namespace — denormalized display column. */
  private String databaseId;

  /** Table name — mutable display column; updated on rename via the next commit. */
  private String tableName;

  /** Stats payload. Snapshot fields overwrite existing values; delta fields accumulate. */
  private TableStats stats;
}
