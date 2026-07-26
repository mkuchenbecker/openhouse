package com.linkedin.openhouse.optimizer.model;

/**
 * Internal enum for the operation types the analyzer and scheduler know about. Intentionally
 * separate from the wire-API and DB representations so the internal model can evolve its set of
 * supported operations without churning either boundary.
 */
public enum OperationTypeDto {

  /** Removes orphaned data files no longer referenced by table metadata. */
  ORPHAN_FILES_DELETION,

  /** Expires Iceberg snapshots older than the table's snapshot-retention window. */
  SNAPSHOTS_EXPIRATION,

  /** Drops rows/partitions that fall outside the table's data-retention policy. */
  RETENTION,

  /** Deletes files left behind by staged/aborted writes that were never committed. */
  STAGED_FILES_DELETION,

  /** Rewrites small data files into larger ones to improve read performance. */
  DATA_COMPACTION,

  /** Computes and records a recommended data-layout (clustering/sort) strategy for the table. */
  DATA_LAYOUT_STRATEGY_GENERATION,

  /** Applies a previously generated data-layout strategy by rewriting the table's data. */
  DATA_LAYOUT_STRATEGY_EXECUTION,

  /** Removes orphaned directories no longer referenced by table metadata. */
  ORPHAN_DIRECTORY_DELETION,

  /** Removes the storage directory of a dropped/purged table. */
  TABLE_DIRECTORY_DELETION,

  /** Collects table-level statistics (row counts, file sizes, etc.). */
  TABLE_STATS_COLLECTION,

  /** Collects sort/clustering statistics used to inform layout decisions. */
  SORT_STATS_COLLECTION;

  /** Convert to the DB-layer counterpart. */
  public com.linkedin.openhouse.optimizer.db.OperationType toDb() {
    return com.linkedin.openhouse.optimizer.db.OperationType.valueOf(name());
  }

  /** Build the internal-model enum from the DB-layer counterpart. */
  public static OperationTypeDto fromDb(com.linkedin.openhouse.optimizer.db.OperationType v) {
    return v == null ? null : OperationTypeDto.valueOf(v.name());
  }
}
