package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import java.util.Optional;

/**
 * Strategy interface for a <em>database-scoped</em> maintenance operation, sibling to {@link
 * OperationAnalyzer}.
 *
 * <p>It exists because the directory-deletion operations do not fit {@link OperationAnalyzer}: that
 * interface's {@link OperationAnalyzer#isEnabled(com.linkedin.openhouse.optimizer.model.TableDto)}
 * and {@code shouldSchedule(TableDto, ...)} are keyed on a live {@code TableDto}, but directory
 * deletion has no live table — its work is a database's storage (dropped-table / orphan
 * directories). The discovery loop ({@link DirectoryDeletionAnalyzerRunner}) enumerates databases
 * from {@code table_stats} instead of iterating tables, and this interface encapsulates the per-op
 * opt-in and cadence decision at database granularity.
 *
 * <p>See {@code services/optimizer/DIRECTORY-DELETION-DESIGN.md} for the model rationale.
 */
public interface DirectoryOperationAnalyzer {

  /** The operation type this analyzer handles. */
  OperationTypeDto getOperationType();

  /**
   * Global per-operation opt-in. Because the unit of work is a whole database rather than a single
   * table, the opt-in is a service-level flag (default off) rather than a per-table property. When
   * {@code false}, the analyzer emits nothing for this operation type.
   */
  boolean isEnabled();

  /**
   * Returns {@code true} if a new PENDING database-scoped operation should be created for {@code
   * databaseName}.
   *
   * @param databaseName the database being evaluated
   * @param currentOp the existing active (non-CANCELED) database-scoped operation, or empty
   * @param latestHistory the most recent completed history entry for this (database, type), or
   *     empty
   */
  boolean shouldSchedule(
      String databaseName,
      Optional<TableOperationDto> currentOp,
      Optional<TableOperationsHistoryDto> latestHistory);
}
