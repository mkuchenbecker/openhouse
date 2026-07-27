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
    /**
     * Multi-directory orphan-table-directory deletion. One Spark job stages/deletes a list of
     * orphaned (dropped-table) directories. This is the batched counterpart the optimizer's {@code
     * OperationTypeDto.toJobType()} resolves {@code ORPHAN_DIRECTORY_DELETION} to. See {@code
     * BatchedOrphanTableDirectoryDeletionSparkApp}.
     */
    ORPHAN_DIRECTORY_DELETION_BATCH,
    TABLE_STATS_COLLECTION,
    DATA_LAYOUT_STRATEGY_GENERATION,
    DATA_LAYOUT_STRATEGY_EXECUTION,
    REPLICATION,
    SORT_STATS_COLLECTION,
    TABLE_DIRECTORY_DELETION,
    /**
     * Multi-directory dropped-table-directory deletion. One Spark job purges a list of
     * dropped/purged table storage directories. Batched counterpart of {@code
     * TABLE_DIRECTORY_DELETION} that {@code OperationTypeDto.toJobType()} resolves to. See {@code
     * BatchedTableDirectoryDeletionSparkApp}.
     */
    TABLE_DIRECTORY_DELETION_BATCH
  }
}
