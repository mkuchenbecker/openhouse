package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.model.TableOperationView;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.util.Optional;

/**
 * Strategy interface for a single operation type. Each implementation decides whether a given table
 * needs an operation recommendation upserted in the Optimizer Service.
 */
public interface OperationAnalyzer {

  /** The operation type this analyzer handles (e.g., {@code "ORPHAN_FILES_DELETION"}). */
  String getOperationType();

  /**
   * Returns {@code true} if this operation is opted-in for the given table. Tables that return
   * {@code false} are skipped entirely — no upsert is issued.
   */
  boolean isEnabled(GetTableResponseBody table);

  /**
   * Returns {@code true} if a new or refreshed operation record should be upserted.
   *
   * @param table the table entry from the Tables Service
   * @param currentOp the existing active operation record, or empty if none exists
   */
  boolean shouldSchedule(GetTableResponseBody table, Optional<TableOperationView> currentOp);
}
