package com.linkedin.openhouse.optimizer.db;

/**
 * DB-layer enum for the operation types persisted in {@code table_operations.operation_type} and
 * {@code table_operations_history.operation_type}.
 *
 * <p>Self-contained: no references to api/ or model/ types. JPA binds this via
 * {@code @Enumerated(EnumType.STRING)}.
 */
public enum OperationType {

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
  SORT_STATS_COLLECTION
}
