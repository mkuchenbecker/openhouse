package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableOperationsRequest;
import java.util.List;

/** Service interface for optimizer data operations. */
public interface OptimizerDataService {

  // --- TableOperations ---

  /**
   * List all active (PENDING or SCHEDULED) operation recommendations, optionally filtered by {@code
   * operationType}. Pass {@code null} to return all types.
   */
  List<TableOperationsDto> getAllTableOperations(OperationType operationType);

  /**
   * Create or update an operation recommendation identified by {@code id}.
   *
   * <p>If a row with {@code id} exists, its {@code metrics} are updated. If not, a new row is
   * inserted with {@code status=PENDING}. Fully idempotent: repeating the same call is safe.
   */
  TableOperationsDto upsertTableOperation(String id, UpsertTableOperationsRequest request);

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
