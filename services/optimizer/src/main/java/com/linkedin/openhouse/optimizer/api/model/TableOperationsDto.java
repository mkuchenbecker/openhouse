package com.linkedin.openhouse.optimizer.api.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO for {@code table_operations} — Analyzer recommendations read by the Scheduler. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableOperationsDto {

  /** UUID; server-assigned on creation, returned in responses. */
  private String id;

  private String databaseName;
  private String tableName;
  private OperationType operationType;

  /** {@code PENDING} or {@code SCHEDULED}. Defaults to {@code PENDING} on creation. */
  private OperationStatus status;

  /** Server-set when the row is first created by the Analyzer. */
  private Instant createdAt;

  /** Set by the Scheduler when claiming; {@code null} while PENDING. */
  private Instant scheduledAt;

  /** Denormalized stats snapshot captured at analysis time. */
  private OperationMetrics metrics;
}
