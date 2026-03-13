package com.linkedin.openhouse.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Combined stats payload stored in the optimizer {@code table_stats} table. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TableStats {

  private SnapshotMetrics snapshot;
  private CommitDelta delta;

  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SnapshotMetrics {
    private String clusterId;
    private String tableVersion;
    private String tableLocation;
    private Integer numSnapshots;
    private Long tableSizeBytes;
  }

  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class CommitDelta {
    private Long numFilesAdded;
    private Long numFilesDeleted;
  }
}
