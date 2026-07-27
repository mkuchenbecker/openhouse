package com.linkedin.openhouse.optimizer.model;

import com.linkedin.openhouse.optimizer.db.TableOperationsHistoryRow;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Internal-model view of a completed operation history record.
 *
 * <p>Mirrors the field set of the underlying history row but in internal types only. Used by
 * components that need to reason about completed operations (e.g., scheduling-cadence analyzers).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TableOperationsHistoryDto {

  /** Same UUID as the originating live-operations row. */
  private String id;

  /** Stable table identity from the Tables Service. */
  private String tableUuid;

  /** Denormalized database name. */
  private String databaseName;

  /** Denormalized table name. */
  private String tableName;

  /** Operation type for this completed run. */
  private OperationTypeDto operationType;

  /** What the completed operation targeted (table / database / directory). */
  private OperationScopeDto operationScope;

  /** Filesystem directory targeted, for {@code DIRECTORY} scope; null otherwise. */
  private String directoryPath;

  /** When the operation completed, as recorded by the complete endpoint. */
  private Instant completedAt;

  /** Terminal outcome: {@link HistoryStatusDto#SUCCESS} or {@link HistoryStatusDto#FAILED}. */
  private HistoryStatusDto status;

  /** Convert to the corresponding DB row. */
  public TableOperationsHistoryRow toRow() {
    return TableOperationsHistoryRow.builder()
        .id(id)
        .tableUuid(tableUuid)
        .databaseName(databaseName)
        .tableName(tableName)
        .operationType(operationType == null ? null : operationType.toDb())
        .operationScope((operationScope == null ? OperationScopeDto.TABLE : operationScope).toDb())
        .directoryPath(directoryPath)
        .completedAt(completedAt)
        .status(status == null ? null : status.toDb())
        .build();
  }

  /** Build a {@link TableOperationsHistoryDto} from a DB row. */
  public static TableOperationsHistoryDto fromRow(TableOperationsHistoryRow row) {
    if (row == null) {
      return null;
    }
    return TableOperationsHistoryDto.builder()
        .id(row.getId())
        .tableUuid(row.getTableUuid())
        .databaseName(row.getDatabaseName())
        .tableName(row.getTableName())
        .operationType(OperationTypeDto.fromDb(row.getOperationType()))
        .operationScope(OperationScopeDto.fromDb(row.getOperationScope()))
        .directoryPath(row.getDirectoryPath())
        .completedAt(row.getCompletedAt())
        .status(HistoryStatusDto.fromDb(row.getStatus()))
        .build();
  }

  /**
   * Return whichever of {@code this} and {@code other} completed later (or {@code this} on tie).
   * Shaped for use as a {@link java.util.function.BinaryOperator} in stream collectors.
   */
  public TableOperationsHistoryDto after(TableOperationsHistoryDto other) {
    return this.completedAt.isBefore(other.completedAt) ? other : this;
  }
}
