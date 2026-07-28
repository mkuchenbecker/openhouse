package com.linkedin.openhouse.optimizer.db;

/**
 * DB-layer enum for the {@code operation_scope} column of {@code table_operations} and {@code
 * table_operations_history}. It records what a given operation row <em>targets</em>, so that
 * operations which are not keyed by a live table can coexist with the per-table majority.
 *
 * <p>Self-contained: no references to api/ or model/ types. JPA-bound as a string; a {@code null}
 * column value is read as {@link #TABLE} for backward compatibility with rows written before this
 * column existed.
 */
public enum OperationScope {

  /**
   * The operation targets a single live table, keyed by {@code table_uuid} (non-null). This is the
   * default and the shape every pre-existing operation uses.
   */
  TABLE,

  /**
   * The operation targets an entire database (all of its storage), keyed by {@code database_name}
   * with a {@code null} {@code table_uuid}. Used by the directory-deletion operations, whose work
   * is discovered by enumerating databases rather than iterating live tables.
   */
  DATABASE,

  /**
   * The operation targets a single filesystem directory, carried in {@code directory_path}, with a
   * {@code null} {@code table_uuid}. Reserved for a future per-directory discovery path (see {@code
   * services/optimizer/DIRECTORY-DELETION-DESIGN.md}); not emitted by the current analyzer.
   */
  DIRECTORY
}
