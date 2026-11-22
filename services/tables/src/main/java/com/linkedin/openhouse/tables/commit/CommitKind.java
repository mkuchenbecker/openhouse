package com.linkedin.openhouse.tables.commit;

/**
 * Which native write a commit on an existing table is shaped like. The commit core applies the same
 * rules to both, except where the native routes have always differed: the lock-state flip check and
 * the no-op short-circuit apply to {@link #TABLE_UPDATE} only, and the locked-table message and the
 * conflict error wording differ.
 */
public enum CommitKind {
  /** {@code PUT /v1/databases/{db}/tables/{t}} on an existing table. */
  TABLE_UPDATE,
  /** {@code PUT .../iceberg/v2/snapshots} on an existing table, replace commits included. */
  SNAPSHOTS_UPDATE
}
