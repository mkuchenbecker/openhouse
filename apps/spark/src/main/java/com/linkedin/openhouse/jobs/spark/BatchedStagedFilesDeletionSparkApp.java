package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.hadoop.fs.Path;

/**
 * Batched staged-files-deletion (SFD) Spark app. One Spark job processes a list of {@code (table,
 * operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each table is
 * handled by a worker thread; per-table failures are caught and reported back independently — the
 * job continues for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link StagedFilesDeletionSparkApp}. The single-table
 * app remains the deployment unit when bin size is 1, and stays the canonical reference for the
 * actual deletion logic.
 *
 * <p>The operation-agnostic batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the SFD-specific per-table deletion logic
 * and CLI options.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedStagedFilesDeletionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedStagedFilesDeletionSparkApp extends BatchedMaintenanceSparkApp {

  private static final String DEFAULT_TRASH_DIR = ".trash";
  private static final int DEFAULT_DAYS_OLD = 3;
  private static final boolean DEFAULT_RECURSIVE = true;

  private final String trashDir;
  private final int olderThanDays;
  private final boolean recursive;

  public BatchedStagedFilesDeletionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      String trashDir,
      int olderThanDays,
      boolean recursive) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.trashDir = trashDir;
    this.olderThanDays = olderThanDays;
    this.recursive = recursive;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.STAGED_FILES_DELETION;
  }

  @Override
  protected String operationLabel() {
    return "SFD";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) throws Exception {
    String fqtn = entry.getFqtn();
    // Same per-table logic and defaults as the single-table StagedFilesDeletionSparkApp: delete
    // files older than the day-threshold from the table's <location>/<trashDir> staging directory.
    Path trashPathForTable = new Path(ops.getTable(fqtn).location(), trashDir);
    List<Path> deletedFiles = ops.deleteStagedFiles(trashPathForTable, olderThanDays, recursive);
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.STAGED_FILE_COUNT,
        deletedFiles.size(),
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
    log.info("SFD stagedFilesDeleted={} fqtn={}", deletedFiles.size(), fqtn);
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedStagedFilesDeletionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt(
                "trashDir", "b", "Base dir under the table location to delete staged files from"),
            valueOpt("daysOld", "o", "Days old files must be to be deleted"),
            valueOpt("recursive", "r", "Delete files recursively from <trashDir>"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedStagedFilesDeletionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        cmdLine.getOptionValue("trashDir", DEFAULT_TRASH_DIR),
        Integer.parseInt(cmdLine.getOptionValue("daysOld", String.valueOf(DEFAULT_DAYS_OLD))),
        Boolean.parseBoolean(
            cmdLine.getOptionValue("recursive", String.valueOf(DEFAULT_RECURSIVE))));
  }

  /**
   * SFD-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#SFD_MAX_BATCH_SIZE} and surfaces that constant's name in the over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.SFD_MAX_BATCH_SIZE,
        "SFD_MAX_BATCH_SIZE");
  }
}
