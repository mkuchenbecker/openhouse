package com.linkedin.openhouse.optimizer.model;

/**
 * Internal-model enum mirroring {@link com.linkedin.openhouse.optimizer.db.OperationScope}. Kept
 * separate from the DB layer so the internal model can evolve independently, matching the pattern
 * used by {@link OperationTypeDto} and {@link OperationStatusDto}.
 */
public enum OperationScopeDto {

  /** Targets a single live table, keyed by {@code tableUuid}. Default. */
  TABLE,

  /** Targets an entire database, keyed by {@code databaseName} with a null {@code tableUuid}. */
  DATABASE,

  /** Targets a single filesystem directory ({@code directoryPath}); reserved for future use. */
  DIRECTORY;

  /** Convert to the DB-layer counterpart. */
  public com.linkedin.openhouse.optimizer.db.OperationScope toDb() {
    return com.linkedin.openhouse.optimizer.db.OperationScope.valueOf(name());
  }

  /**
   * Build the internal-model enum from the DB-layer counterpart. A {@code null} DB value (rows
   * written before the column existed) maps to {@link #TABLE}.
   */
  public static OperationScopeDto fromDb(com.linkedin.openhouse.optimizer.db.OperationScope v) {
    return v == null ? TABLE : OperationScopeDto.valueOf(v.name());
  }
}
