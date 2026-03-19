package com.linkedin.openhouse.optimizer.api.model;

/** Lifecycle states for a table operation recommendation. */
public enum OperationStatus {

  /** Recommended by the Analyzer but not yet claimed by the Scheduler. */
  PENDING,

  /** Claimed and submitted to Spark by the Scheduler. */
  SCHEDULED,

  /** The Spark job completed successfully. */
  SUCCESS,

  /** The Spark job failed or was lost without a response. */
  FAILED,

  /**
   * Marked by the Scheduler when it detects duplicate PENDING rows for the same {@code (table_uuid,
   * operation_type)}. Only the most-recent PENDING row is claimed; older duplicates are CANCELED
   * before the claim step.
   */
  CANCELED
}
