package com.linkedin.openhouse.optimizer.model;

import com.linkedin.openhouse.optimizer.db.TableOperationsRow;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An operation the analyzer has decided to schedule for a table, and that the scheduler later picks
 * up and submits.
 *
 * <p>Conversion methods cross into the DB layer one-way; the inverse lives on the api side. db/
 * types know nothing about model/ or api/.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableOperationDto {

  /** Unique operation ID (UUID). */
  private String id;

  /** The table this operation targets. */
  private String tableUuid;

  /** Database name. */
  private String databaseName;

  /** Table name. */
  private String tableName;

  /** Operation type. */
  private OperationTypeDto operationType;

  /** What this operation targets (table / database / directory). Defaults to {@code TABLE}. */
  private OperationScopeDto operationScope;

  /** Filesystem directory this operation targets, for {@code DIRECTORY} scope; null otherwise. */
  private String directoryPath;

  /** Current lifecycle status. */
  private OperationStatusDto status;

  /** When this operation record was created. */
  private Instant createdAt;

  /** When the scheduler last submitted a job for this operation. */
  private Instant scheduledAt;

  /** Job ID returned by the Jobs Service after the scheduler submitted; null until SCHEDULED. */
  private String jobId;

  /** Create a new PENDING operation for the given table and operation type. */
  public static TableOperationDto pending(TableDto table, OperationTypeDto operationType) {
    return TableOperationDto.builder()
        .id(UUID.randomUUID().toString())
        .tableUuid(table.getTableUuid())
        .databaseName(table.getDatabaseName())
        .tableName(table.getTableId())
        .operationType(operationType)
        .operationScope(OperationScopeDto.TABLE)
        .status(OperationStatusDto.PENDING)
        .createdAt(Instant.now())
        .build();
  }

  /**
   * Create a new PENDING <em>database-scoped</em> operation. Used by the directory-deletion
   * analyzers, whose unit of work is a database's storage rather than a live table — so {@code
   * tableUuid} and {@code tableName} are left null and {@code operationScope} is {@code DATABASE}.
   */
  public static TableOperationDto pendingForDatabase(
      String databaseName, OperationTypeDto operationType) {
    return TableOperationDto.builder()
        .id(UUID.randomUUID().toString())
        .databaseName(databaseName)
        .operationType(operationType)
        .operationScope(OperationScopeDto.DATABASE)
        .status(OperationStatusDto.PENDING)
        .createdAt(Instant.now())
        .build();
  }

  /** Return the more recently created of two operations. */
  public static TableOperationDto mostRecent(TableOperationDto a, TableOperationDto b) {
    Comparator<TableOperationDto> byCreatedAt =
        Comparator.comparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : Instant.EPOCH);
    return byCreatedAt.compare(a, b) >= 0 ? a : b;
  }

  /** Convert to the corresponding DB row. */
  public TableOperationsRow toRow() {
    return TableOperationsRow.builder()
        .id(id)
        .tableUuid(tableUuid)
        .databaseName(databaseName)
        .tableName(tableName)
        .operationType(operationType == null ? null : operationType.toDb())
        .operationScope((operationScope == null ? OperationScopeDto.TABLE : operationScope).toDb())
        .directoryPath(directoryPath)
        .status(status == null ? null : status.toDb())
        .createdAt(createdAt)
        .scheduledAt(scheduledAt)
        .jobId(jobId)
        .build();
  }

  /** Build a {@link TableOperationDto} from a DB row. */
  public static TableOperationDto fromRow(TableOperationsRow row) {
    if (row == null) {
      return null;
    }
    return TableOperationDto.builder()
        .id(row.getId())
        .tableUuid(row.getTableUuid())
        .databaseName(row.getDatabaseName())
        .tableName(row.getTableName())
        .operationType(OperationTypeDto.fromDb(row.getOperationType()))
        .operationScope(OperationScopeDto.fromDb(row.getOperationScope()))
        .directoryPath(row.getDirectoryPath())
        .status(OperationStatusDto.fromDb(row.getStatus()))
        .createdAt(row.getCreatedAt())
        .scheduledAt(row.getScheduledAt())
        .jobId(row.getJobId())
        .build();
  }
}
