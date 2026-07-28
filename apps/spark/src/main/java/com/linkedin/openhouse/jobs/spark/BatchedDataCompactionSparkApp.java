package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
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
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.RewriteDataFiles;

/**
 * Batched data-compaction Spark app. One Spark job processes a list of {@code (table, operationId)}
 * pairs that the optimizer scheduler bin-packed into a single batch. Each table is handled by a
 * worker thread; per-table failures are caught and reported back independently — the job continues
 * for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link DataCompactionSparkApp}. The single-table app
 * remains the deployment unit when bin size is 1, and stays the canonical reference for the actual
 * rewrite/bin-pack logic; {@link #maintainTable} replays exactly that per-table {@code
 * ops.rewriteDataFiles(...)} call with the same parameters, defaults, and emitted metrics.
 *
 * <p>The operation-agnostic batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the compaction-specific per-table logic and
 * CLI options.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedDataCompactionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedDataCompactionSparkApp extends BatchedMaintenanceSparkApp {

  private final DataCompactionConfig config;

  public BatchedDataCompactionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      DataCompactionConfig config) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.config = config;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.DATA_COMPACTION;
  }

  @Override
  protected String operationLabel() {
    return "DataCompaction";
  }

  /**
   * Compacts a single table's data files. Mirrors {@link DataCompactionSparkApp#runInner} verbatim:
   * same {@code ops.rewriteDataFiles(...)} arguments derived from {@link DataCompactionConfig},
   * same per-file-group logging, and the same four emitted metrics. Throwing marks the operation
   * FAILED.
   */
  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    log.info("Rewrite data files app start for table {}, config {}", fqtn, config);
    Table table = ops.getTable(fqtn);
    RewriteDataFiles.Result result =
        ops.rewriteDataFiles(
            table,
            config.getTargetByteSize(),
            (long) (config.getTargetByteSize() * config.getMinByteSizeRatio()),
            (long) (config.getTargetByteSize() * config.getMaxByteSizeRatio()),
            config.getMinInputFiles(),
            config.getMaxConcurrentFileGroupRewrites(),
            config.isPartialProgressEnabled(),
            config.getPartialProgressMaxCommits(),
            config.getDeleteFileThreshold());
    log.info(
        "Added {} data files, rewritten {} data files, rewritten {} bytes",
        result.addedDataFilesCount(),
        result.rewrittenDataFilesCount(),
        result.rewrittenBytesCount());
    log.info("Processed {} file groups", result.rewriteResults().size());
    for (RewriteDataFiles.FileGroupRewriteResult fileGroupRewriteResult : result.rewriteResults()) {
      log.info(
          "File group {} has {} added files, {} rewritten files, {} rewritten bytes",
          Operations.groupInfoToString(fileGroupRewriteResult.info()),
          fileGroupRewriteResult.addedDataFilesCount(),
          fileGroupRewriteResult.rewrittenDataFilesCount(),
          fileGroupRewriteResult.rewrittenBytesCount());
    }
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.ADDED_DATA_FILE_COUNT,
        result.addedDataFilesCount(),
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_COUNT,
        result.rewrittenDataFilesCount(),
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_BYTES,
        result.rewrittenBytesCount(),
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_GROUP_COUNT,
        result.rewriteResults().size(),
        Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn));
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedDataCompactionSparkApp createApp(String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt("targetByteSize", "Target data file byte size"),
            valueOpt(
                "minByteSizeRatio",
                "Minimum data file byte size ratio; files smaller than this fraction of the target are rewritten"),
            valueOpt(
                "maxByteSizeRatio",
                "Maximum data file byte size ratio; files larger than this multiple of the target are rewritten"),
            valueOpt(
                "minInputFiles", "Minimum number of input files in a group sufficient for rewrite"),
            valueOpt(
                "maxConcurrentFileGroupRewrites",
                "Maximum number of file groups to be simultaneously rewritten"),
            flagOpt(
                "partialProgressEnabled",
                "Enable committing groups of files prior to the entire rewrite completing"),
            valueOpt(
                "partialProgressMaxCommits",
                "Maximum amount of commits that this rewrite is allowed to produce if partial progress is enabled"),
            valueOpt(
                "deleteFileThreshold",
                "Minimum number of deletes associated with a data file for it to be considered for rewriting"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedDataCompactionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        buildConfig(cmdLine));
  }

  /**
   * Builds the {@link DataCompactionConfig} from CLI options, defaulting each field to the same
   * {@link DataCompactionConfig} defaults the single-table {@link DataCompactionSparkApp} uses, and
   * applying the same ratio-range validation.
   */
  private static DataCompactionConfig buildConfig(CommandLine cmdLine) {
    long targetByteSize =
        NumberUtils.toLong(
            cmdLine.getOptionValue("targetByteSize"),
            DataCompactionConfig.TARGET_BYTE_SIZE_DEFAULT);
    double minByteSizeRatio =
        NumberUtils.toDouble(
            cmdLine.getOptionValue("minByteSizeRatio"),
            DataCompactionConfig.MIN_BYTE_SIZE_RATIO_DEFAULT);
    if (minByteSizeRatio <= 0.0 || minByteSizeRatio >= 1.0) {
      throw new RuntimeException("minByteSizeRatio must be in range (0.0, 1.0)");
    }
    double maxByteSizeRatio =
        NumberUtils.toDouble(
            cmdLine.getOptionValue("maxByteSizeRatio"),
            DataCompactionConfig.MAX_BYTE_SIZE_RATIO_DEFAULT);
    if (maxByteSizeRatio <= 1.0) {
      throw new RuntimeException("maxByteSizeRatio must be greater than 1.0");
    }
    return DataCompactionConfig.builder()
        .targetByteSize(targetByteSize)
        .minByteSizeRatio(minByteSizeRatio)
        .maxByteSizeRatio(maxByteSizeRatio)
        .minInputFiles(
            NumberUtils.toInt(
                cmdLine.getOptionValue("minInputFiles"),
                DataCompactionConfig.MIN_INPUT_FILES_DEFAULT))
        .maxConcurrentFileGroupRewrites(
            NumberUtils.toInt(
                cmdLine.getOptionValue("maxConcurrentFileGroupRewrites"),
                DataCompactionConfig.MAX_CONCURRENT_FILE_GROUP_REWRITES_DEFAULT))
        .partialProgressEnabled(cmdLine.hasOption("partialProgressEnabled"))
        .partialProgressMaxCommits(
            NumberUtils.toInt(
                cmdLine.getOptionValue("partialProgressMaxCommits"),
                DataCompactionConfig.PARTIAL_PROGRESS_MAX_COMMITS_DEFAULT))
        .deleteFileThreshold(
            NumberUtils.toInt(
                cmdLine.getOptionValue("deleteFileThreshold"),
                DataCompactionConfig.DELETE_FILE_THRESHOLD_DEFAULT))
        .build();
  }

  /**
   * Data-compaction-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces
   * {@link AppConstants#DATA_COMPACTION_MAX_BATCH_SIZE} and surfaces that constant's name in the
   * over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.DATA_COMPACTION_MAX_BATCH_SIZE,
        "DATA_COMPACTION_MAX_BATCH_SIZE");
  }
}
