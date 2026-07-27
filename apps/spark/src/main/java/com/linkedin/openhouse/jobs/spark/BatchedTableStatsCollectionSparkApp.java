package com.linkedin.openhouse.jobs.spark;

import com.google.gson.Gson;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.common.stats.model.CommitEventTable;
import com.linkedin.openhouse.common.stats.model.CommitEventTablePartitionStats;
import com.linkedin.openhouse.common.stats.model.CommitEventTablePartitions;
import com.linkedin.openhouse.common.stats.model.IcebergTableStats;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

/**
 * Batched table-stats-collection Spark app. One Spark job processes a list of {@code (table,
 * operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each table is
 * handled by a worker thread; per-table failures are caught and reported back independently — the
 * job continues for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link TableStatsCollectionSparkApp}; that single-table
 * app stays the canonical reference for the actual collection logic. The operation-agnostic
 * batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the per-table stats-collection logic.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedTableStatsCollectionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedTableStatsCollectionSparkApp extends BatchedMaintenanceSparkApp {

  public BatchedTableStatsCollectionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.TABLE_STATS_COLLECTION;
  }

  @Override
  protected String operationLabel() {
    return "TABLE_STATS_COLLECTION";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    long startTime = System.currentTimeMillis();

    IcebergTableStats icebergStats = ops.collectTableStats(fqtn);
    List<CommitEventTable> commitEvents = ops.collectCommitEventTable(fqtn);
    List<CommitEventTablePartitions> partitionEvents = ops.collectCommitEventTablePartitions(fqtn);
    List<CommitEventTablePartitionStats> partitionStats =
        ops.collectCommitEventTablePartitionStats(fqtn);

    log.info(
        "Total collection time for table: {} in {} ms",
        fqtn,
        System.currentTimeMillis() - startTime);

    if (icebergStats != null) {
      publishStats(fqtn, icebergStats);
    } else {
      log.warn("Skipping stats publishing for table: {} due to collection failure", fqtn);
    }

    if (commitEvents != null && !commitEvents.isEmpty()) {
      publishCommitEvents(fqtn, commitEvents);
    } else {
      log.warn(
          "Skipping commit events publishing for table: {} due to collection failure or no events",
          fqtn);
    }

    if (partitionEvents != null && !partitionEvents.isEmpty()) {
      publishPartitionEvents(fqtn, partitionEvents);
    } else {
      log.info(
          "Skipping partition events publishing for table: {} "
              + "(unpartitioned table or collection failure or no events)",
          fqtn);
    }

    if (partitionStats != null && !partitionStats.isEmpty()) {
      publishPartitionStats(fqtn, partitionStats);
    } else {
      log.info(
          "Skipping partition stats publishing for table: {} "
              + "(unpartitioned table or collection failure or no stats)",
          fqtn);
    }
  }

  /** Publish table stats. */
  protected void publishStats(String fqtn, IcebergTableStats icebergTableStats) {
    log.info("Publishing stats for table: {}", fqtn);
    log.info(new Gson().toJson(icebergTableStats));
  }

  /** Publish commit events. */
  protected void publishCommitEvents(String fqtn, List<CommitEventTable> commitEvents) {
    log.info("Publishing commit events for table: {}", fqtn);
    log.info(new Gson().toJson(commitEvents));
  }

  /** Publish partition-level commit events. */
  protected void publishPartitionEvents(
      String fqtn, List<CommitEventTablePartitions> partitionEvents) {
    log.info("Publishing partition events for table: {}", fqtn);
    log.info(new Gson().toJson(partitionEvents));
  }

  /** Publish partition-level statistics. */
  protected void publishPartitionStats(
      String fqtn, List<CommitEventTablePartitionStats> partitionStats) {
    log.info("Publishing partition stats for table: {} ({} stats)", fqtn, partitionStats.size());
    log.info(new Gson().toJson(partitionStats));
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedTableStatsCollectionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedTableStatsCollectionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")));
  }

  /**
   * Table-stats-collection {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#TABLE_STATS_COLLECTION_MAX_BATCH_SIZE} and surfaces that constant's name in the
   * over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.TABLE_STATS_COLLECTION_MAX_BATCH_SIZE,
        "TABLE_STATS_COLLECTION_MAX_BATCH_SIZE");
  }
}
