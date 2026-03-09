package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import java.util.List;
import java.util.Optional;

/** Service interface for optimizer data operations. */
public interface OptimizerDataService {

  // --- TableStats ---

  /** Return the latest stats for a table, or empty if no stats have been recorded. */
  Optional<TableStatsDto> getTableStats(String databaseName, String tableName);

  /**
   * Upsert stats for a table. Delta fields ({@code numFilesAdded}, {@code numFilesDeleted}) are
   * accumulated; all other fields are overwritten with the incoming values.
   */
  TableStatsDto upsertTableStats(TableStatsDto dto);

  // --- TableOperations ---

  /**
   * List all operation recommendations, optionally filtered by {@code operationType}. Pass {@code
   * null} to return all.
   *
   * <p>TODO: unbounded query — add pagination before production use. At high write volumes this
   * query will return O(100M) rows per operation type. The Scheduler should pass a batch-size limit
   * or time window before this endpoint goes to production.
   */
  List<TableOperationsDto> getAllTableOperations(OperationType operationType);

  /** Upsert (create or refresh) an operation recommendation for {@code (db, table, opType)}. */
  TableOperationsDto upsertTableOperation(
      String databaseName, String tableName, OperationType operationType, TableOperationsDto dto);

  // --- TableOperationsHistory ---

  /** Append a completed-job result record. */
  TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto);

  /**
   * Return the most recent history rows for a table, newest first.
   *
   * @param databaseName the database namespace
   * @param tableName the table name
   * @param limit maximum number of rows to return
   */
  List<TableOperationsHistoryDto> getHistory(String databaseName, String tableName, int limit);
}
