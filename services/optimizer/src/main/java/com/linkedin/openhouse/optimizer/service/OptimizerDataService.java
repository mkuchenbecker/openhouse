package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.PatchTableOperationRequest;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableStatsRequest;
import java.util.List;
import java.util.Optional;

/** Service interface for optimizer data operations. */
public interface OptimizerDataService {

  // --- TableOperations ---

  /**
   * List all active (PENDING or SCHEDULED) operation recommendations, optionally filtered by {@code
   * operationType}. Pass {@code null} to return all types.
   */
  List<TableOperationsDto> getAllTableOperations(OperationType operationType);

  /**
   * Transition an operation to {@code SUCCESS} or {@code FAILED}.
   *
   * <p>Called by the Spark job after completing work for a single table within a batch. Only {@code
   * SUCCESS} and {@code FAILED} are valid target statuses. Returns empty if the row does not exist.
   */
  Optional<TableOperationsDto> patchTableOperation(String id, PatchTableOperationRequest request);

  /**
   * Return the operation row for {@code id} regardless of status, or empty if it does not exist.
   * Used to poll a specific operation (e.g. waiting for SUCCESS after a Spark job completes).
   */
  Optional<TableOperationsDto> getTableOperation(String id);

  // --- TableStats ---

  /**
   * Create or update the stats row for {@code tableUuid}. Fully idempotent: the same call
   * overwrites the previous snapshot with the latest commit values.
   */
  TableStatsDto upsertTableStats(String tableUuid, UpsertTableStatsRequest request);

  /** Return the stats row for {@code tableUuid}, or empty if none exists. */
  Optional<TableStatsDto> getTableStats(String tableUuid);

  // --- TableOperationsHistory ---

  /** Append a completed-job result record. */
  TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto);

  /**
   * Return the most recent history rows for a table UUID, newest first.
   *
   * @param tableUuid the stable table UUID
   * @param limit maximum number of rows to return
   */
  List<TableOperationsHistoryDto> getHistory(String tableUuid, int limit);
}
