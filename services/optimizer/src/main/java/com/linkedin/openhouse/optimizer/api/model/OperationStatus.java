package com.linkedin.openhouse.optimizer.api.model;

/** Lifecycle states for a table operation recommendation. */
public enum OperationStatus {

  /** Recommended by the Analyzer but not yet claimed by the Scheduler. */
  PENDING,

  /** Claimed and submitted to Spark by the Scheduler. */
  SCHEDULED
}
