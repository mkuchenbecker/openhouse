package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import com.linkedin.openhouse.datalayout.persistence.StrategiesDaoTableProps;
import com.linkedin.openhouse.datalayout.strategy.DataLayoutStrategy;
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
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.RewriteDataFiles;

/**
 * Batched data-layout-strategy <b>execution</b> Spark app — the second half of the gen&rarr;exec
 * pair. One Spark job applies the previously generated data-layout (compaction) strategy for a list
 * of {@code (table, operationId)} pairs that the optimizer scheduler bin-packed into a single
 * batch. Each table is handled by a worker thread; per-table failures are caught and reported back
 * independently.
 *
 * <p>Execution reuses the {@link DataCompactionSparkApp} rewrite path, driven by the strategy the
 * generation half stored in the table's own properties (property key {@code
 * write.data-layout.strategies}, written by {@link StrategiesDaoTableProps}). At runtime this app
 * loads that property for each table and rewrites its data files with the strategy's {@link
 * DataCompactionConfig}. A table whose property is absent or holds no strategies is a <b>no-op</b>
 * (logged, counted as success): nothing to execute yet.
 *
 * <p>This runtime no-op is the defense-in-depth counterpart to the {@code
 * CadenceBasedDataLayoutStrategyExecutionAnalyzer}, which already gates scheduling on the strategy
 * property being present on the table. Even if a strategy is consumed or unset between the
 * analyzer's decision and this job running, the batch simply skips that table rather than failing.
 *
 * <p>The operation-agnostic batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the execution-specific per-table logic and
 * CLI options.
 */
@Slf4j
public class BatchedDataLayoutStrategyExecutionSparkApp extends BatchedMaintenanceSparkApp {

  public BatchedDataLayoutStrategyExecutionSparkApp(
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
    return UpdateOperationRequest.OperationTypeEnum.DATA_LAYOUT_STRATEGY_EXECUTION;
  }

  @Override
  protected String operationLabel() {
    return "DLS-EXEC";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    Table table = ops.getTable(fqtn);
    List<DataLayoutStrategy> strategies = loadStrategies(table);
    if (strategies.isEmpty()) {
      // No generated strategy present — nothing to execute. Not a failure: the generation half has
      // not (yet) produced work for this table, or it was already consumed/unset.
      log.info("DLS-EXEC no-op: no pending data-layout strategy for fqtn={}", fqtn);
      return;
    }
    log.info("DLS-EXEC executing {} strategies for fqtn={}", strategies.size(), fqtn);
    for (DataLayoutStrategy strategy : strategies) {
      executeStrategy(ops, fqtn, strategy.getConfig());
    }
  }

  private List<DataLayoutStrategy> loadStrategies(Table table) {
    String propValue =
        table.properties().get(StrategiesDaoTableProps.DATA_LAYOUT_STRATEGIES_PROPERTY_KEY);
    if (StringUtils.isBlank(propValue)) {
      return Collections.emptyList();
    }
    List<DataLayoutStrategy> strategies = StrategiesDaoTableProps.deserializeList(propValue);
    return strategies == null ? Collections.emptyList() : strategies;
  }

  private void executeStrategy(Operations ops, String fqtn, DataCompactionConfig config) {
    log.info("DLS-EXEC rewrite data files start for table {}, config {}", fqtn, config);
    RewriteDataFiles.Result result =
        ops.rewriteDataFiles(
            ops.getTable(fqtn),
            config.getTargetByteSize(),
            (long) (config.getTargetByteSize() * config.getMinByteSizeRatio()),
            (long) (config.getTargetByteSize() * config.getMaxByteSizeRatio()),
            config.getMinInputFiles(),
            config.getMaxConcurrentFileGroupRewrites(),
            config.isPartialProgressEnabled(),
            config.getPartialProgressMaxCommits(),
            config.getDeleteFileThreshold());
    log.info(
        "DLS-EXEC added {} data files, rewritten {} data files, rewritten {} bytes for {}",
        result.addedDataFilesCount(),
        result.rewrittenDataFilesCount(),
        result.rewrittenBytesCount(),
        fqtn);
    Attributes tableAttr = Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), fqtn);
    otelEmitter.count(
        METRICS_SCOPE, AppConstants.ADDED_DATA_FILE_COUNT, result.addedDataFilesCount(), tableAttr);
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_COUNT,
        result.rewrittenDataFilesCount(),
        tableAttr);
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_BYTES,
        result.rewrittenBytesCount(),
        tableAttr);
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.REWRITTEN_DATA_FILE_GROUP_COUNT,
        result.rewriteResults().size(),
        tableAttr);
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedDataLayoutStrategyExecutionSparkApp createApp(
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

    return new BatchedDataLayoutStrategyExecutionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")));
  }

  /**
   * Execution-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#DATA_LAYOUT_STRATEGY_EXECUTION_MAX_BATCH_SIZE} and surfaces that constant's name
   * in the over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.DATA_LAYOUT_STRATEGY_EXECUTION_MAX_BATCH_SIZE,
        "DATA_LAYOUT_STRATEGY_EXECUTION_MAX_BATCH_SIZE");
  }
}
