package com.linkedin.openhouse.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client-side view of a table operation record returned by the Optimizer Service. Mirrors the
 * fields in {@code TableOperationsDto} that the Analyzer needs; unknown fields are ignored.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableOperationView {

  private String id;
  private String tableUuid;
  private String databaseName;
  private String tableName;
  private String operationType;
  private String status;
  private Instant createdAt;
  private Instant scheduledAt;
}
