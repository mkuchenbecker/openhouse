package com.linkedin.openhouse.optimizer.entity;

/**
 * Enum representing types of maintenance operations supported by the continuous optimizer.
 *
 * <p>Only {@code ORPHAN_FILES_DELETION} is currently implemented. Additional operation types will
 * be added as they are built out.
 */
public enum OperationType {
  /** Removes orphaned data files that are no longer referenced by table metadata. */
  ORPHAN_FILES_DELETION
}
