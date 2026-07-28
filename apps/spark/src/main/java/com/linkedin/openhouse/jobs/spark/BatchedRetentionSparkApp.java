package com.linkedin.openhouse.jobs.spark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.common.stats.model.RetentionStatsSchema;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.StringUtils;
import org.apache.iceberg.Table;
import org.apache.iceberg.types.Types;

/**
 * Batched RETENTION Spark app. One Spark job processes a list of {@code (table, operationId)} pairs
 * that the optimizer scheduler bin-packed into a single batch. Each table is handled by a worker
 * thread; per-table failures are caught and reported back independently — the job continues for the
 * remaining tables and exits 0 if at least one table succeeds.
 *
 * <p>This is the multi-table counterpart of {@link RetentionSparkApp}. The single-table app remains
 * the deployment unit for one-off runs and stays the canonical reference for the actual per-table
 * delete logic ({@link Operations#runRetention}). The operation-agnostic batching,
 * results-callback, and CLI-parsing machinery lives in {@link BatchedMaintenanceSparkApp}; this
 * class supplies only the retention-specific per-table logic and CLI options.
 *
 * <h2>Per-table retention config resolution</h2>
 *
 * <p>Unlike the single-table app — where {@code --columnName/--granularity/--count/--columnPattern}
 * come from CLI args that the scheduler derived per table — a batch carries only table names. Each
 * table's retention parameters therefore differ, so this app re-resolves them at runtime from the
 * table's own OpenHouse {@code "policies"} property, exactly as {@code
 * TablesClient#getTableRetention} does server-side:
 *
 * <ul>
 *   <li>{@code count} / {@code granularity} come from the {@code policies.retention} block.
 *   <li>the retention <b>column</b> is the {@code retention.columnPattern.columnName} when present
 *       (string time column), otherwise the table's time-partition column.
 *   <li>{@code columnPattern} is passed through when present, else empty (partition-column path).
 * </ul>
 *
 * <p>A table whose retention column cannot be resolved (retention unset, or non-partitioned with no
 * columnPattern) is a no-op success — the optimizer analyzer's eligibility guard should have kept
 * it out of the batch, and skipping is the safe backstop rather than failing the whole bin.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedRetentionSparkApp \
 *   --tableNames db.t1,db.t2,db.t3 \
 *   --operationIds op-uuid-1,op-uuid-2,op-uuid-3 \
 *   --tableUuids tab-uuid-1,tab-uuid-2,tab-uuid-3 \
 *   --resultsEndpoint http://optimizer.svc:8080 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedRetentionSparkApp extends BatchedMaintenanceSparkApp {

  private final String backupDir;

  public BatchedRetentionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      String backupDir) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.backupDir = backupDir;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.RETENTION;
  }

  @Override
  protected String operationLabel() {
    return "RETENTION";
  }

  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    String fqtn = entry.getFqtn();
    Table table = ops.getTable(fqtn);
    RetentionParams params = resolveRetentionParams(table);
    if (params == null) {
      log.warn(
          "RETENTION skipped (no resolvable retention time column): fqtn={} operationId={}",
          fqtn,
          entry.getOperationId().orElse(""));
      return;
    }
    boolean backupEnabled =
        Boolean.parseBoolean(
            table.properties().getOrDefault(AppConstants.BACKUP_ENABLED_KEY, "false"));
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    log.info(
        "RETENTION start: fqtn={} column={} pattern={} ttl={} {}s backupEnabled={} backupDir={} ts={}",
        fqtn,
        params.columnName,
        params.columnPattern,
        params.count,
        params.granularity,
        backupEnabled,
        backupDir,
        now);
    ops.runRetention(
        fqtn,
        params.columnName,
        params.columnPattern,
        params.granularity,
        params.count,
        backupEnabled,
        backupDir,
        now);
  }

  /**
   * Resolve this table's retention parameters from its {@code "policies"} property, mirroring
   * {@code TablesClient#getTableRetention}. Returns {@code null} when the table has no configured
   * retention policy or no resolvable time column (so the caller no-ops it).
   */
  private RetentionParams resolveRetentionParams(Table table) {
    String policies = table.properties().get("policies");
    if (StringUtils.isBlank(policies)) {
      return null;
    }
    JsonObject policiesObject;
    try {
      policiesObject = new Gson().fromJson(policies, JsonObject.class);
    } catch (Exception e) {
      log.warn("Unparseable policies for table {}; skipping retention", table.name(), e);
      return null;
    }
    if (policiesObject == null || !policiesObject.has("retention")) {
      return null;
    }
    RetentionStatsSchema retention =
        new GsonBuilder()
            .registerTypeAdapter(
                RetentionStatsSchema.class, new RetentionStatsSchema.RetentionPolicyDeserializer())
            .create()
            .fromJson(policiesObject.get("retention"), RetentionStatsSchema.class);
    if (retention == null
        || StringUtils.isBlank(retention.getGranularity())
        || retention.getCount() == null) {
      return null;
    }
    // Column: string columnPattern column when present, else the table's time-partition column.
    String columnName =
        retention.getColumnName() != null
            ? retention.getColumnName()
            : getPartitionColumnName(table);
    if (StringUtils.isBlank(columnName)) {
      return null;
    }
    String columnPattern = retention.getColumnPattern() != null ? retention.getColumnPattern() : "";
    return new RetentionParams(
        columnName, columnPattern, retention.getGranularity(), retention.getCount());
  }

  /** First date-typed partition column, or {@code null} for a non-time-partitioned table. */
  private static String getPartitionColumnName(Table table) {
    return table.spec().partitionType().fields().stream()
        .filter(field -> field.type() instanceof Types.DateType)
        .map(Types.NestedField::name)
        .findFirst()
        .orElse(null);
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedRetentionSparkApp createApp(String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableNames", "Comma-separated list of fully-qualified table names"),
            valueOpt("operationIds", "Comma-separated operation UUIDs, parallel to tableNames"),
            valueOpt("tableUuids", "Comma-separated table UUIDs, parallel to tableNames"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt("backupDir", "b", "Backup directory for deleted data"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    List<BatchEntry> entries =
        buildEntries(
            cmdLine.getOptionValue("tableNames"),
            cmdLine.getOptionValue("operationIds"),
            cmdLine.getOptionValue("tableUuids"));

    return new BatchedRetentionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        requireOption(cmdLine, "resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        cmdLine.getOptionValue("backupDir", ".backup"));
  }

  /**
   * RETENTION-specific {@link BatchedMaintenanceSparkApp#buildEntries} wrapper: enforces {@link
   * AppConstants#RETENTION_MAX_BATCH_SIZE} and surfaces that constant's name in the over-limit
   * error.
   */
  static List<BatchEntry> buildEntries(String tableNames, String operationIds, String tableUuids) {
    return buildEntries(
        tableNames,
        operationIds,
        tableUuids,
        AppConstants.RETENTION_MAX_BATCH_SIZE,
        "RETENTION_MAX_BATCH_SIZE");
  }

  /** Resolved per-table retention parameters for {@link Operations#runRetention}. */
  private static final class RetentionParams {
    private final String columnName;
    private final String columnPattern;
    private final String granularity;
    private final int count;

    RetentionParams(String columnName, String columnPattern, String granularity, int count) {
      this.columnName = columnName;
      this.columnPattern = columnPattern;
      this.granularity = granularity;
      this.count = count;
    }
  }
}
