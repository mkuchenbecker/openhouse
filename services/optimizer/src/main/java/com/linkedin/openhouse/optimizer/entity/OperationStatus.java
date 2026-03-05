package com.linkedin.openhouse.optimizer.entity;

/** Lifecycle states for a table operation recommendation. */
public enum OperationStatus {

  /** Operation has been recommended by the Analyzer but not yet claimed by the Scheduler. */
  PENDING,

  /** Operation has been claimed and submitted to Spark by the Scheduler. */
  SCHEDULED
}
