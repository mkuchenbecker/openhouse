package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.spark.sql.SparkSession;

/**
 * Batched sort-stats-collection Spark app. One Spark job processes a list of {@code (table,
 * operationId)} pairs that the optimizer scheduler bin-packed into a single batch. Each table is
 * handled by a worker thread; per-table failures are caught and reported back independently — the
 * job continues for the remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link SortStatsCollectionSparkApp}; that single-table
 * app stays the canonical reference for the actual collection logic. The operation-agnostic
 * batching, results-callback, and CLI-parsing machinery lives in {@link
 * BatchedMaintenanceSparkApp}; this class supplies only the per-table sort-stats-collection logic.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedSortStatsCollectionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedSortStatsCollectionSparkApp extends BatchedMaintenanceSparkApp {
  private static final int TARGET_FILE_SIZE = 512 * 1024 * 1024; // 512MB
  private static final String SORT_ORDER =
      "header.memberId asc nulls last, header.time asc nulls last";
  private static final String COMPRESSION_RATE_KEY = "sort-compression-rate";

  public BatchedSortStatsCollectionSparkApp(
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
    return UpdateOperationRequest.OperationTypeEnum.SORT_STATS_COLLECTION;
  }

  @Override
  protected String operationLabel() {
    return "SORT_STATS_COLLECTION";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) throws Exception {
    String fqtn = entry.getFqtn();
    SparkSession spark = ops.spark();
    String tempTableName = getTempTableName(fqtn);
    String datepartition = getDatePartition();
    try {
      // sample average record size from table and calculate rows needed for target file size
      long avgRecordSize =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes) * 1.0 / sum(record_count) as avg_bytes_per_record "
                          + "from openhouse.%s.data_files limit 10",
                      fqtn))
              .first()
              .getDecimal(0)
              .longValue();
      int numOfRowsNeeded = (int) (TARGET_FILE_SIZE / avgRecordSize);
      log.info(
          "Avg record size for table {} is {}, copy {} rows for sort stats collection",
          fqtn,
          avgRecordSize,
          numOfRowsNeeded);
      // create a temporary table with limited rows for the given partition
      // if the partition doesn't provide enough rows, the whole partition will be copied
      spark.sql(
          String.format(
              "create table openhouse.%s as select * from spark_catalog.%s where datepartition = '%s' limit %d",
              tempTableName, fqtn, datepartition, numOfRowsNeeded));
      double sizeBefore =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes)/1000/1000 from openhouse.%s.data_files",
                      tempTableName))
              .first()
              .getDouble(0);
      // call rewrite procedure to rewrite data files with sort strategy
      String rewriteOptions = getRewriteOptions();
      spark.sql(
          String.format(
              "call openhouse.system.rewrite_data_files("
                  + "table => '%s', options => map(%s), strategy => 'sort', sort_order => '%s')",
              tempTableName, rewriteOptions, SORT_ORDER));
      double sizeAfter =
          spark
              .sql(
                  String.format(
                      "select sum(file_size_in_bytes)/1000/1000 from openhouse.%s.data_files",
                      tempTableName))
              .first()
              .getDouble(0);
      // calculate compression rate and store it as table property
      double compressionRate = (sizeBefore - sizeAfter) / sizeBefore * 100;
      spark.sql(
          String.format(
              "alter table openhouse.%s set tblproperties ('%s'='%f')",
              fqtn, COMPRESSION_RATE_KEY, compressionRate));
      log.info(
          "Sort stats collection for table {} completed. Size before: {} MB, size after: {} MB, compression rate: {}%",
          fqtn, sizeBefore, sizeAfter, String.format("%.2f", compressionRate));
    } catch (Exception e) {
      log.error("Error during sort stats collection for table {}", fqtn, e);
      throw e;
    } finally {
      spark.sql(String.format("drop table if exists openhouse.%s", tempTableName));
    }
  }

  private String getTempTableName(String fqtn) {
    return fqtn + "_sample";
  }

  private String getDatePartition() {
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    return yesterday.format(formatter);
  }

  private String getRewriteOptions() {
    Map<String, String> options = new HashMap();
    options.put("target-file-size-bytes", String.valueOf(TARGET_FILE_SIZE));
    options.put("rewrite-all", "true");
    options.put("partial-progress.enabled", "true");

    return options.entrySet().stream()
        .map(e -> String.format("'%s', '%s'", e.getKey(), e.getValue()))
        .collect(Collectors.joining(", "));
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedSortStatsCollectionSparkApp createApp(
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

    return new BatchedSortStatsCollectionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")));
  }

  /**
   * Sort-stats-collection {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#SORT_STATS_COLLECTION_MAX_BATCH_SIZE} and surfaces that constant's name in the
   * over-limit error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.SORT_STATS_COLLECTION_MAX_BATCH_SIZE,
        "SORT_STATS_COLLECTION_MAX_BATCH_SIZE");
  }
}
