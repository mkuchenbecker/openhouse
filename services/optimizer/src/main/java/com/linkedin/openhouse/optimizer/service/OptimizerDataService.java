package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import java.util.List;
import java.util.Optional;

/** Service interface for optimizer data operations. */
public interface OptimizerDataService {

  // --- TableStats ---

  Optional<TableStatsDto> getTableStats(String databaseName, String tableName);

  TableStatsDto upsertTableStats(TableStatsDto dto);

  // --- TableOperations ---

  /**
   * List all operation recommendations, optionally filtered by {@code operationType}. Pass {@code
   * null} to return all.
   */
  List<TableOperationsDto> getAllTableOperations(String operationType);

  /** Upsert (create or refresh) an operation recommendation for {@code (db, table, opType)}. */
  TableOperationsDto upsertTableOperation(TableOperationsDto dto);

  // --- TableOperationsHistory ---

  /** Append a completed-job result record. */
  TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto);

  /** Return all history rows for a table, newest first. */
  List<TableOperationsHistoryDto> getHistory(String databaseName, String tableName);
}
