package com.linkedin.openhouse.jobs.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class JobConf {
  private JobType jobType;
  private String proxyUser;
  @Builder.Default private Map<String, String> executionConf = new HashMap<>();
  @Builder.Default private List<String> args = new ArrayList<>();

  public enum JobType {
    NO_OP,
    SQL_TEST,
    RETENTION,
    ORPHAN_FILES_DELETION,
    /**
     * Multi-table orphan-files-deletion. One Spark job processes a list of tables grouped by
     * database — bin-packing happens scheduler-side. See {@code
     * BatchedOrphanFilesDeletionSparkApp}.
     */
    ORPHAN_FILES_DELETION_BATCH,
    /**
     * Multi-table retention. One Spark job processes a bin-packed list of tables, re-resolving each
     * table's retention column/granularity/count from its policies at runtime. See {@code
     * BatchedRetentionSparkApp}.
     */
    RETENTION_BATCH,
    SNAPSHOTS_EXPIRATION,
    /**
     * Multi-table snapshots-expiration. One Spark job processes a list of tables grouped by
     * database — bin-packing happens scheduler-side. See {@code
     * BatchedSnapshotsExpirationSparkApp}.
     */
    SNAPSHOTS_EXPIRATION_BATCH,
    STAGED_FILES_DELETION,
    /**
     * Multi-table staged-files-deletion. One Spark job processes a list of tables grouped by
     * database — bin-packing happens scheduler-side. See {@code
     * BatchedStagedFilesDeletionSparkApp}.
     */
    STAGED_FILES_DELETION_BATCH,
    DATA_COMPACTION,
    ORPHAN_DIRECTORY_DELETION,
    TABLE_STATS_COLLECTION,
    /**
     * Multi-table table-stats-collection. One Spark job processes a bin-packed list of tables;
     * bin-packing happens scheduler-side. See {@code BatchedTableStatsCollectionSparkApp}.
     */
    TABLE_STATS_COLLECTION_BATCH,
    DATA_LAYOUT_STRATEGY_GENERATION,
    DATA_LAYOUT_STRATEGY_EXECUTION,
    REPLICATION,
    SORT_STATS_COLLECTION,
    /**
     * Multi-table sort-stats-collection. One Spark job processes a bin-packed list of tables;
     * bin-packing happens scheduler-side. See {@code BatchedSortStatsCollectionSparkApp}.
     */
    SORT_STATS_COLLECTION_BATCH,
    TABLE_DIRECTORY_DELETION
  }
}
