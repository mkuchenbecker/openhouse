package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
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
 * Batched snapshots-expiration Spark app. One Spark job processes a list of {@code (table,
 * operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each table is
 * handled by a worker thread; per-table failures are caught and reported back independently — the
 * job continues for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link SnapshotsExpirationSparkApp}. The single-table
 * app remains the deployment unit when bin size is 1, and stays the canonical reference for the
 * actual expiration logic. The operation-agnostic batching, results-callback, and CLI-parsing
 * machinery lives in {@link BatchedMaintenanceSparkApp}; this class supplies only the
 * snapshots-expiration per-table logic and CLI options.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedSnapshotsExpirationSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4 \
 *   --maxAge 3 --granularity day --versions 10
 * }</pre>
 */
@Slf4j
public class BatchedSnapshotsExpirationSparkApp extends BatchedMaintenanceSparkApp {

  private final int maxAge;
  private final String granularity;
  private final int versions;

  public BatchedSnapshotsExpirationSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      int maxAge,
      String granularity,
      int versions) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    // Mirror SnapshotsExpirationSparkApp: always enforce a snapshot TTL even when unconfigured, so
    // a
    // table opted in with no explicit age still gets a sane default retention window applied.
    if (maxAge == 0) {
      this.maxAge = SnapshotsExpirationSparkApp.DEFAULT_CONFIGURATION.MAX_AGE;
      this.granularity = SnapshotsExpirationSparkApp.DEFAULT_CONFIGURATION.GRANULARITY;
    } else {
      this.maxAge = maxAge;
      this.granularity = granularity;
    }
    this.versions = versions;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.SNAPSHOTS_EXPIRATION;
  }

  @Override
  protected String operationLabel() {
    return "SNAPSHOTS_EXPIRATION";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    // Same single-table call the reference SnapshotsExpirationSparkApp makes; current snapshot is
    // always preserved. Throwing here marks this one operation FAILED without affecting siblings.
    ops.expireSnapshots(entry.getFqtn(), maxAge, granularity, versions);
    log.info(
        "SNAPSHOTS_EXPIRATION done: fqtn={} maxAge={} granularity={} versions={}",
        entry.getFqtn(),
        maxAge,
        granularity,
        versions);
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedSnapshotsExpirationSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt("maxAge", "a", "Delete snapshots older than <maxAge> <granularity>s"),
            valueOpt("granularity", "g", "Granularity: day"),
            valueOpt("versions", "v", "Number of versions to keep after snapshot expiration"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedSnapshotsExpirationSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        Integer.parseInt(cmdLine.getOptionValue("maxAge", "0")),
        cmdLine.getOptionValue("granularity", ""),
        Integer.parseInt(cmdLine.getOptionValue("versions", "0")));
  }

  /**
   * Snapshots-expiration-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces
   * {@link AppConstants#SNAPSHOTS_EXPIRATION_MAX_BATCH_SIZE} and surfaces that constant's name in
   * the over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.SNAPSHOTS_EXPIRATION_MAX_BATCH_SIZE,
        "SNAPSHOTS_EXPIRATION_MAX_BATCH_SIZE");
  }
}
