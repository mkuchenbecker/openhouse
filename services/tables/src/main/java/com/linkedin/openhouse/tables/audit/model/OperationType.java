package com.linkedin.openhouse.tables.audit.model;

/** The type of specific table operation */
public enum OperationType {
  CREATE,
  READ,
  UPDATE,
  COMMIT,
  DELETE,
  GRANT,
  REVOKE,
  STAGED_CREATE,
  STAGED_COMMIT,
  STAGED_REPLACE,
  REPLACE_COMMIT,
  RENAME_FROM,
  RENAME_TO,
  /**
   * A write raised the table's Iceberg format version to 3 or above, which cannot be undone: sent,
   * after the event of the write that did it, when that write succeeded.
   */
  UPGRADE_FORMAT_VERSION
}
