package com.linkedin.openhouse.jobs.spark;

import com.google.common.collect.Lists;
import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.iceberg.Table;

/**
 * Batched orphan file deletion job that processes multiple tables in parallel.
 *
 * <p>Example invocation: com.linkedin.openhouse.jobs.spark.BatchedOrphanFilesDeletionSparkApp
 * --tableNames db.table1,db.table2,db.table3 --parallelism 10
 */
@Slf4j
public class BatchedOrphanFilesDeletionSparkApp extends BaseSparkApp {
  private final List<String> tableNames;
  private final int parallelism;
  private final long ttlSeconds;
  private final String backupDir;
  private final int concurrentDeletes;
  private final List<Long> operationIds;
  private final String resultsEndpoint;

  public BatchedOrphanFilesDeletionSparkApp(
      String jobId,
      StateManager stateManager,
      List<String> tableNames,
      int parallelism,
      long ttlSeconds,
      OtelEmitter otelEmitter,
      String backupDir,
      int concurrentDeletes,
      List<Long> operationIds,
      String resultsEndpoint) {
    super(jobId, stateManager, otelEmitter);
    this.tableNames = tableNames;
    this.parallelism = parallelism;
    this.ttlSeconds = ttlSeconds;
    this.backupDir = backupDir;
    this.concurrentDeletes = concurrentDeletes;
    this.operationIds = operationIds;
    this.resultsEndpoint = resultsEndpoint;
  }

  @Override
  protected void runInner(Operations ops) throws Exception {
    if (resultsEndpoint != null && operationIds.size() != tableNames.size()) {
      throw new IllegalArgumentException(
          "operationIds count ("
              + operationIds.size()
              + ") must equal tableNames count ("
              + tableNames.size()
              + ") when resultsEndpoint is provided");
    }

    Map<String, Long> tableToOperationId = new HashMap<>();
    if (resultsEndpoint != null) {
      for (int i = 0; i < tableNames.size(); i++) {
        tableToOperationId.put(tableNames.get(i), operationIds.get(i));
      }
    }

    long olderThanTimestampMillis =
        System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(ttlSeconds);

    log.info(
        "Starting batched orphan files deletion for {} tables with parallelism {}",
        tableNames.size(),
        parallelism);

    // Process tables in parallel using a thread pool in the driver.
    // JavaRDD.map() cannot be used here because Operations is not Serializable.
    int poolSize = Math.min(parallelism, Math.max(1, tableNames.size()));
    ExecutorService executor = Executors.newFixedThreadPool(poolSize);
    List<Future<OrphanDeletionResult>> futures = new ArrayList<>();

    for (String tableName : tableNames) {
      futures.add(
          executor.submit(
              () -> {
                long startTime = System.currentTimeMillis();
                try {
                  Table table = ops.getTable(tableName);
                  boolean backupEnabled =
                      Boolean.parseBoolean(
                          table
                              .properties()
                              .getOrDefault(AppConstants.BACKUP_ENABLED_KEY, "false"));

                  log.info("Processing orphan files for table: {}", tableName);
                  Operations.OrphanFilesResult result =
                      ops.deleteOrphanFilesWithMetrics(
                          table,
                          olderThanTimestampMillis,
                          backupEnabled,
                          backupDir,
                          concurrentDeletes);

                  List<String> orphanFiles =
                      Lists.newArrayList(result.orphanFileLocations().iterator());
                  long durationMs = System.currentTimeMillis() - startTime;

                  long bytesDeleted = result.getBytesDeleted();

                  log.info(
                      "Successfully processed table {}: {} orphan files deleted in {}ms",
                      tableName,
                      orphanFiles.size(),
                      durationMs);

                  return OrphanDeletionResult.success(
                      tableName, orphanFiles.size(), bytesDeleted, durationMs);

                } catch (Exception e) {
                  long durationMs = System.currentTimeMillis() - startTime;
                  log.error("Failed to process table: {}", tableName, e);
                  return OrphanDeletionResult.failure(tableName, e, durationMs);
                }
              }));
    }

    executor.shutdown();
    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

    List<OrphanDeletionResult> allResults = new ArrayList<>();
    for (Future<OrphanDeletionResult> future : futures) {
      try {
        allResults.add(future.get());
      } catch (ExecutionException e) {
        // Should not happen — the callable catches all exceptions internally
        throw new RuntimeException("Unexpected exception in table processing", e.getCause());
      }
    }

    reportResults(allResults, tableToOperationId);
  }

  private void reportResults(
      List<OrphanDeletionResult> results, Map<String, Long> tableToOperationId) throws Exception {
    int successCount = 0;
    int failureCount = 0;
    long totalOrphanFiles = 0;
    long totalBytesDeleted = 0;

    List<TableResultEntry> tableResultEntries = new ArrayList<>();

    for (OrphanDeletionResult result : results) {
      if (result.isSuccess()) {
        successCount++;
        totalOrphanFiles += result.getOrphanFilesDeleted();
        totalBytesDeleted += result.getBytesDeleted();

        tableResultEntries.add(
            TableResultEntry.builder()
                .fqtn(result.getTableName())
                .status("SUCCESS")
                .orphanFilesDeleted((long) result.getOrphanFilesDeleted())
                .bytesDeleted(result.getBytesDeleted())
                .durationMs(result.getDurationMs())
                .build());

        otelEmitter.count(
            METRICS_SCOPE,
            AppConstants.ORPHAN_FILE_COUNT,
            result.getOrphanFilesDeleted(),
            Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), result.getTableName()));
      } else {
        failureCount++;
        tableResultEntries.add(
            TableResultEntry.builder()
                .fqtn(result.getTableName())
                .status("FAILED")
                .errorMessage(result.getErrorMessage())
                .errorType(result.getErrorType())
                .durationMs(result.getDurationMs())
                .build());
      }
    }

    log.info(
        "Batch completed: {} succeeded, {} failed, {} total orphan files, {} bytes deleted",
        successCount,
        failureCount,
        totalOrphanFiles,
        totalBytesDeleted);

    otelEmitter.count(METRICS_SCOPE, "batch_success_count", successCount, null);
    otelEmitter.count(METRICS_SCOPE, "batch_failure_count", failureCount, null);
    otelEmitter.count(METRICS_SCOPE, "batch_total_orphan_files", totalOrphanFiles, null);
    otelEmitter.gauge(METRICS_SCOPE, "batch_total_bytes_deleted", totalBytesDeleted, null);

    if (resultsEndpoint == null) {
      if (failureCount > 0) {
        throw new RuntimeException(failureCount + " table(s) failed in batch");
      }
      return;
    }

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    for (TableResultEntry entry : tableResultEntries) {
      Long opId = tableToOperationId.get(entry.getFqtn());
      if (opId != null) {
        patchOperationStatus(client, opId, entry);
      }
    }
    if (successCount == 0) {
      throw new RuntimeException("All tables failed in batch");
    }
  }

  private void patchOperationStatus(HttpClient client, Long id, TableResultEntry entry)
      throws Exception {
    String json = buildPatchJson(entry);
    HttpResponse<Void> response =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(resultsEndpoint + "/" + id))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("PATCH operation/" + id + " returned HTTP " + status);
    }
  }

  private String buildPatchJson(TableResultEntry entry) {
    if ("SUCCESS".equals(entry.getStatus())) {
      return String.format(
          "{\"status\":\"SUCCESS\",\"orphanFilesDeleted\":%d,\"bytesDeleted\":%d,\"durationMs\":%d}",
          entry.getOrphanFilesDeleted(), entry.getBytesDeleted(), entry.getDurationMs());
    } else {
      return String.format(
          "{\"status\":\"FAILED\",\"durationMs\":%d,\"errorMessage\":\"%s\",\"errorType\":\"%s\"}",
          entry.getDurationMs(),
          jsonEscape(entry.getErrorMessage()),
          jsonEscape(entry.getErrorType()));
    }
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Result of orphan deletion for a single table. */
  @Value
  @Builder
  public static class OrphanDeletionResult implements Serializable {
    String tableName;
    boolean success;
    int orphanFilesDeleted;
    long bytesDeleted;
    long durationMs;
    String errorMessage;
    String errorType;

    public static OrphanDeletionResult success(
        String tableName, int orphanFileCount, long bytesDeleted, long durationMs) {
      return OrphanDeletionResult.builder()
          .tableName(tableName)
          .success(true)
          .orphanFilesDeleted(orphanFileCount)
          .bytesDeleted(bytesDeleted)
          .durationMs(durationMs)
          .build();
    }

    public static OrphanDeletionResult failure(String tableName, Throwable error, long durationMs) {
      return OrphanDeletionResult.builder()
          .tableName(tableName)
          .success(false)
          .orphanFilesDeleted(0)
          .bytesDeleted(0)
          .durationMs(durationMs)
          .errorMessage(error.getMessage())
          .errorType(error.getClass().getSimpleName())
          .build();
    }
  }

  /** Table result entry for Jobs Service API. */
  @Value
  @Builder
  public static class TableResultEntry implements Serializable {
    String fqtn;
    String status;
    Long orphanFilesDeleted;
    Long bytesDeleted;
    Long durationMs;
    String errorMessage;
    String errorType;
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Arrays.asList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedOrphanFilesDeletionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions = new ArrayList<>();
    extraOptions.add(
        new Option("tn", "tableNames", true, "Comma-separated fully-qualified table names"));
    extraOptions.add(new Option("p", "parallelism", true, "Number of parallel table processes"));
    extraOptions.add(
        new Option(
            "r",
            "ttl",
            true,
            "How old files should be to be considered orphaned in seconds, minimum 1d is"
                + " enforced"));
    extraOptions.add(new Option("b", "backupDir", true, "Backup directory for deleted data"));
    extraOptions.add(new Option("c", "concurrentDeletes", true, "Number of concurrent deletes"));
    extraOptions.add(new Option("oi", "operationIds", true, "Comma-separated operation IDs"));
    extraOptions.add(new Option("re", "resultsEndpoint", true, "Base URL for per-table PATCH"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    String tableNamesStr = cmdLine.getOptionValue("tableNames");
    List<String> tableNames =
        tableNamesStr != null ? Arrays.asList(tableNamesStr.split(",")) : new ArrayList<>();

    String idsStr = cmdLine.getOptionValue("operationIds");
    List<Long> operationIds =
        idsStr != null
            ? Arrays.stream(idsStr.split(",")).map(Long::parseLong).collect(Collectors.toList())
            : Collections.emptyList();
    String resultsEndpoint = cmdLine.getOptionValue("resultsEndpoint");

    return new BatchedOrphanFilesDeletionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        tableNames,
        Integer.parseInt(cmdLine.getOptionValue("parallelism", "10")),
        Math.max(
            NumberUtils.toLong(cmdLine.getOptionValue("ttl"), TimeUnit.DAYS.toSeconds(7)),
            TimeUnit.DAYS.toSeconds(1)),
        otelEmitter,
        cmdLine.getOptionValue("backupDir", ".backup"),
        Integer.parseInt(cmdLine.getOptionValue("concurrentDeletes", "10")),
        operationIds,
        resultsEndpoint);
  }
}
