package com.linkedin.openhouse.jobs.spark;

import com.google.common.collect.Iterables;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.exception.TableValidationException;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.jobs.util.TableStateValidator;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.DeleteOrphanFiles;

/**
 * Batched orphan-files-deletion Spark app. One Spark job processes a list of {@code (table,
 * operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each table is
 * handled by a worker thread; per-table failures are caught and reported back independently — the
 * job continues for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link OrphanFilesDeletionSparkApp}. The single-table
 * app remains the deployment unit when bin size is 1, and stays the canonical reference for the
 * actual deletion logic.
 *
 * <p>The operation-agnostic batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the OFD-specific per-table deletion logic
 * and CLI options.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedOrphanFilesDeletionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedOrphanFilesDeletionSparkApp extends BatchedMaintenanceSparkApp {

  private static final int DEFAULT_MAX_ORPHAN_FILE_SAMPLE_SIZE = 20000;
  private static final int DEFAULT_MIN_OFD_TTL_IN_DAYS = 3;

  private final long ttlSeconds;
  private final String backupDir;
  private final int concurrentDeletes;
  private final boolean streamResults;
  private final int maxOrphanFileSampleSize;

  public BatchedOrphanFilesDeletionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      long ttlSeconds,
      String backupDir,
      int concurrentDeletes,
      boolean streamResults,
      int maxOrphanFileSampleSize) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.ttlSeconds = ttlSeconds;
    this.backupDir = backupDir;
    this.concurrentDeletes = concurrentDeletes;
    this.streamResults = streamResults;
    this.maxOrphanFileSampleSize = maxOrphanFileSampleSize;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.ORPHAN_FILES_DELETION;
  }

  @Override
  protected String operationLabel() {
    return "OFD";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    Table table = ops.getTable(fqtn);
    long olderThanTimestampMillis =
        System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(resolveTtlSeconds(table));
    DeleteOrphanFiles.Result result =
        ops.deleteOrphanFiles(
            table,
            olderThanTimestampMillis,
            backupDir,
            concurrentDeletes,
            streamResults,
            maxOrphanFileSampleSize);
    // Count via iteration rather than materializing the full path list: a table with millions
    // of orphan files would otherwise OOM the driver, and that risk multiplies with
    // driverParallelism workers running concurrently.
    int orphanCount = Iterables.size(result.orphanFileLocations());
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.ORPHAN_FILE_COUNT,
        orphanCount,
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
    validate(ops, fqtn);
    log.info("OFD orphansDetected={} fqtn={}", orphanCount, fqtn);
  }

  /**
   * Re-runs {@link TableStateValidator} — the same post-job consistency check the single-table
   * {@link OrphanFilesDeletionSparkApp} uses — to confirm the table's manifests and metadata are
   * intact after deletion. A failure here is treated as a failed operation: it's logged, counted,
   * and re-thrown so the worker marks {@code success=false}.
   */
  private void validate(Operations ops, String fqtn) {
    try {
      TableStateValidator.run(ops.spark(), fqtn);
    } catch (TableValidationException e) {
      log.error("Post-job validation failed: fqtn={}", fqtn, e);
      otelEmitter.count(
          METRICS_SCOPE,
          "post_run_validation_error",
          1,
          Attributes.of(
              AttributeKey.stringKey(AppConstants.TABLE_NAME),
              fqtn,
              AttributeKey.stringKey(AppConstants.JOB_NAME),
              BatchedOrphanFilesDeletionSparkApp.class.getSimpleName()));
      throw e;
    }
  }

  private long resolveTtlSeconds(Table table) {
    long resolved = ttlSeconds;
    if (Boolean.parseBoolean(
        table.properties().getOrDefault(AppConstants.OFD_ONE_DAY_TTL_ENABLED_KEY, "false"))) {
      resolved = TimeUnit.DAYS.toSeconds(1);
    }
    String tableType =
        table
            .properties()
            .getOrDefault(AppConstants.OPENHOUSE_TABLE_TYPE_KEY, AppConstants.TABLE_TYPE_PRIMARY);
    if (AppConstants.TABLE_TYPE_REPLICA.equals(tableType)
        && Duration.ofSeconds(resolved).toDays() < DEFAULT_MIN_OFD_TTL_IN_DAYS) {
      resolved = TimeUnit.DAYS.toSeconds(DEFAULT_MIN_OFD_TTL_IN_DAYS);
    }
    return resolved;
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedOrphanFilesDeletionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt("trashDir", "tr", "Orphan files staging dir before deletion"),
            valueOpt(
                "ttl",
                "r",
                "How old files should be to be considered orphaned in seconds, minimum 1d is enforced"),
            valueOpt("backupDir", "b", "Backup directory for deleted data"),
            valueOpt("concurrentDeletes", "c", "Number of concurrent deletes per table"),
            flagOpt("streamResults", "Stream orphan file deletions instead of collecting"),
            valueOpt("maxOrphanFileSampleSize", "Max orphan file sample paths returned"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedOrphanFilesDeletionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        Math.max(
            NumberUtils.toLong(cmdLine.getOptionValue("ttl"), TimeUnit.DAYS.toSeconds(7)),
            TimeUnit.DAYS.toSeconds(1)),
        cmdLine.getOptionValue("backupDir", ".backup"),
        Integer.parseInt(cmdLine.getOptionValue("concurrentDeletes", "10")),
        cmdLine.hasOption("streamResults"),
        Integer.parseInt(
            cmdLine.getOptionValue(
                "maxOrphanFileSampleSize", String.valueOf(DEFAULT_MAX_ORPHAN_FILE_SAMPLE_SIZE))));
  }

  /**
   * OFD-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#OFD_MAX_BATCH_SIZE} and surfaces that constant's name in the over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.OFD_MAX_BATCH_SIZE,
        "OFD_MAX_BATCH_SIZE");
  }
}
