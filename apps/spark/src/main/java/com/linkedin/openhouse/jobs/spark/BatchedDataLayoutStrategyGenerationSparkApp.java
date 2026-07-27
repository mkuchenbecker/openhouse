package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.datalayout.datasource.TableFileStats;
import com.linkedin.openhouse.datalayout.datasource.TablePartitionStats;
import com.linkedin.openhouse.datalayout.datasource.TableSnapshotStats;
import com.linkedin.openhouse.datalayout.generator.OpenHouseDataLayoutStrategyGenerator;
import com.linkedin.openhouse.datalayout.persistence.StrategiesDao;
import com.linkedin.openhouse.datalayout.persistence.StrategiesDaoTableProps;
import com.linkedin.openhouse.datalayout.strategy.DataLayoutStrategy;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.spark.sql.SparkSession;

/**
 * Batched data-layout-strategy <b>generation</b> Spark app — the first half of the gen&rarr;exec
 * pair. One Spark job (re)generates data-layout (compaction) strategies for a list of {@code
 * (table, operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each
 * table is handled by a worker thread; per-table failures are caught and reported back
 * independently.
 *
 * <p>This is the multi-table counterpart of {@link DataLayoutStrategyGeneratorSparkApp}, whose
 * per-table generation logic it wraps: for each table it builds file/partition/snapshot stats, runs
 * {@link OpenHouseDataLayoutStrategyGenerator}, and persists the resulting strategies into the
 * table's own properties via {@link StrategiesDaoTableProps} (property key {@code
 * write.data-layout.strategies}). That stored property is the signal the <b>execution</b> half
 * ({@link BatchedDataLayoutStrategyExecutionSparkApp}) keys on to know a table has pending work.
 *
 * <p>The operation-agnostic batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the generation-specific per-table logic and
 * CLI options.
 */
@Slf4j
public class BatchedDataLayoutStrategyGenerationSparkApp extends BatchedMaintenanceSparkApp {

  public BatchedDataLayoutStrategyGenerationSparkApp(
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
    return UpdateOperationRequest.OperationTypeEnum.DATA_LAYOUT_STRATEGY_GENERATION;
  }

  @Override
  protected String operationLabel() {
    return "DLS-GEN";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    SparkSession spark = ops.spark();
    TableFileStats tableFileStats = TableFileStats.builder().tableName(fqtn).spark(spark).build();
    TablePartitionStats tablePartitionStats =
        TablePartitionStats.builder().tableName(fqtn).spark(spark).build();
    TableSnapshotStats tableSnapshotStats =
        TableSnapshotStats.builder().tableName(fqtn).spark(spark).build();
    boolean isPartitioned = ops.getTable(fqtn).spec().isPartitioned();
    OpenHouseDataLayoutStrategyGenerator strategiesGenerator =
        OpenHouseDataLayoutStrategyGenerator.builder()
            .tableFileStats(tableFileStats)
            .tablePartitionStats(tablePartitionStats)
            .tableSnapshotStats(tableSnapshotStats)
            .partitioned(isPartitioned)
            .build();
    StrategiesDao dao = StrategiesDaoTableProps.builder().spark(spark).build();

    // Run table scope for every table, and additionally partition scope for partitioned tables —
    // mirrors DataLayoutStrategyGeneratorSparkApp. The table-scope save writes the
    // write.data-layout.strategies property the execution half consumes.
    List<DataLayoutStrategy> tableStrategies = strategiesGenerator.generateTableLevelStrategies();
    log.info("DLS-GEN generated {} table-level strategies for {}", tableStrategies.size(), fqtn);
    dao.save(fqtn, tableStrategies);

    if (isPartitioned) {
      List<DataLayoutStrategy> partitionStrategies =
          strategiesGenerator.generatePartitionLevelStrategies();
      log.info(
          "DLS-GEN generated {} partition-level strategies for {}",
          partitionStrategies.size(),
          fqtn);
      dao.deletePartitionScope(fqtn);
      dao.savePartitionScope(fqtn, partitionStrategies);
    }
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedDataLayoutStrategyGenerationSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        java.util.Arrays.asList(
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

    return new BatchedDataLayoutStrategyGenerationSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")));
  }

  /**
   * Generation-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#DATA_LAYOUT_STRATEGY_GENERATION_MAX_BATCH_SIZE} and surfaces that constant's name
   * in the over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.DATA_LAYOUT_STRATEGY_GENERATION_MAX_BATCH_SIZE,
        "DATA_LAYOUT_STRATEGY_GENERATION_MAX_BATCH_SIZE");
  }
}
