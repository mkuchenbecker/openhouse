package com.linkedin.openhouse.housetables.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Combined stats payload stored as a single JSON blob per table. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TableStats {

  /** Snapshot fields — overwritten on every upsert. */
  private SnapshotMetrics snapshot;

  /** Delta fields — accumulated across commit events. */
  private CommitDelta delta;

  /** Point-in-time metadata read from Iceberg at scan time. */
  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SnapshotMetrics {
    private String clusterId;
    private String tableVersion;
    private String tableLocation;
    private Long tableSizeBytes;
  }

  /** Per-commit incremental counters; accumulated across all recorded commit events. */
  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CommitDelta {
    /** Running total of data files added across all recorded commit events. */
    private Long numFilesAdded;

    /** Running total of data files deleted across all recorded commit events. */
    private Long numFilesDeleted;
  }
}
