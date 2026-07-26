package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.optimizer.OptimizerServiceClient;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.lang3.StringUtils;

/**
 * Reusable base for batched, results-aware maintenance Spark apps. One Spark job processes a list of
 * {@code (table, operationId)} pairs that the optimizer scheduler bin-packed into a single batch.
 * Each table is handled by a worker thread; per-table failures are caught and reported back
 * independently — the job continues for the remaining tables and exits 0 if at least one table
 * succeeds.
 *
 * <p>This class owns the operation-agnostic machinery: CLI parsing into {@link BatchEntry} rows, the
 * fixed-size worker pool, per-operation SUCCESS/FAILED PATCH callbacks to the Optimizer Service, and
 * the success/failure accounting. Concrete subclasses supply only the operation-specific pieces:
 *
 * <ul>
 *   <li>{@link #maintainTable(Operations, BatchEntry)} — the actual per-table maintenance; throwing
 *       signals a failed operation.
 *   <li>{@link #operationType()} — the wire enum value reported back per operation.
 *   <li>{@link #operationLabel()} — a short label used on log lines.
 *   <li>{@link #maxBatchSize()} — optional cap enforced when parsing {@code --tableNames}.
 * </ul>
 *
 * @see BatchedOrphanFilesDeletionSparkApp the reference concrete implementation.
 */
@Slf4j
public abstract class BatchedMaintenanceSparkApp extends BaseSparkApp {

  private final List<BatchEntry> entries;
  private final String resultsEndpoint;
  private final int driverParallelism;

  protected BatchedMaintenanceSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism) {
    super(jobId, stateManager, otelEmitter);
    this.entries = entries;
    this.resultsEndpoint = resultsEndpoint;
    this.driverParallelism = Math.max(1, driverParallelism);
  }

  /**
   * Performs the operation-specific maintenance for a single table. Throwing any exception marks the
   * operation FAILED (it is caught, logged, and reported back independently); returning normally
   * marks it SUCCESS.
   */
  protected abstract void maintainTable(Operations ops, BatchEntry entry) throws Exception;

  /** The wire enum value reported back to the Optimizer Service for each operation in this batch. */
  protected abstract UpdateOperationRequest.OperationTypeEnum operationType();

  /** Short label for this operation, used on log lines (e.g. {@code "OFD"}). */
  protected abstract String operationLabel();

  /**
   * Maximum number of tables permitted in a single batch. Enforced by {@link #buildEntries} at parse
   * time. Defaults to no practical cap; subclasses override to impose an operation-specific bound.
   */
  protected int maxBatchSize() {
    return Integer.MAX_VALUE;
  }

  @Override
  protected void runInner(Operations ops) {
    log.info(
        "Batched {} start: entries={} driverParallelism={} resultsEndpoint={}",
        operationLabel(),
        entries.size(),
        driverParallelism,
        resultsEndpoint);

    if (entries.isEmpty()) {
      log.warn("Batched {} invoked with no entries; nothing to do", operationLabel());
      return;
    }

    Optional<OptimizerServiceClient> client = newOptimizerClient();
    int successCount = runBatch(ops, client);

    int failureCount = entries.size() - successCount;
    log.info(
        "Batched {} finished: total={} success={} failed={}",
        operationLabel(),
        entries.size(),
        successCount,
        failureCount);

    if (successCount == 0) {
      throw new RuntimeException(
          String.format("All %d operations in batch failed", entries.size()));
    }
  }

  private int runBatch(Operations ops, Optional<OptimizerServiceClient> client) {
    ExecutorService pool = Executors.newFixedThreadPool(driverParallelism);
    try {
      // Two-phase pipeline: submit every worker first (so they run concurrently), then await each.
      // Pairing each Future with its BatchEntry via AbstractMap.SimpleImmutableEntry.
      List<Map.Entry<BatchEntry, Future<Boolean>>> submissions =
          entries.stream()
              .map(
                  entry ->
                      new AbstractMap.SimpleImmutableEntry<>(
                          entry, pool.submit(new TableWorker(ops, entry, client))))
              .collect(Collectors.toList());
      return submissions.stream()
          .mapToInt(submission -> awaitOne(submission.getKey(), submission.getValue(), client))
          .sum();
    } finally {
      shutdownPool(pool);
    }
  }

  private int awaitOne(
      BatchEntry entry, Future<Boolean> future, Optional<OptimizerServiceClient> client) {
    try {
      return Boolean.TRUE.equals(future.get()) ? 1 : 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Worker interrupted: fqtn={}", entry.getFqtn(), e);
      otelEmitter.count(
          METRICS_SCOPE,
          "optimizer_batch_interrupted",
          1,
          Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), entry.getFqtn()));
      return 0;
    } catch (ExecutionException e) {
      // The worker catches Throwable internally and always reports its own result, so reaching
      // here means the worker itself leaked an exception. Be defensive: post FAILED so the
      // operation row doesn't sit SCHEDULED until the stale-timeout.
      log.error(
          "Worker threw outside its own catch for fqtn={} — reporting FAILED",
          entry.getFqtn(),
          e.getCause());
      reportResult(entry, UpdateOperationRequest.StatusEnum.FAILED, client);
      return 0;
    }
  }

  private void shutdownPool(ExecutorService pool) {
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }

  /**
   * Returns a client bound to {@code resultsEndpoint}, or empty when the endpoint was not configured
   * — in that case the legacy {@link com.linkedin.openhouse.jobs.scheduler.JobsScheduler} is the
   * caller and reports lifecycle via HTS; the per-operation optimizer callback is skipped.
   */
  protected Optional<OptimizerServiceClient> newOptimizerClient() {
    return Optional.ofNullable(resultsEndpoint).map(OptimizerServiceClient::new);
  }

  /**
   * POST the per-operation outcome to the Optimizer Service via the generated client. No-op when
   * {@code client} is empty (the legacy scheduler-driven path; lifecycle is already tracked via
   * HTS). When the call exhausts retries we log + count and leave the operation row at SCHEDULED so
   * the Analyzer's stale-timeout can re-queue it.
   *
   * <p>{@code status} is passed in as a {@link UpdateOperationRequest.StatusEnum} rather than a
   * boolean so the caller's intent is unambiguous and new terminal states (e.g. CANCELED) can be
   * plumbed in without changing the signature.
   */
  private void reportResult(
      BatchEntry entry,
      UpdateOperationRequest.StatusEnum status,
      Optional<OptimizerServiceClient> client) {
    if (!client.isPresent()) {
      return;
    }
    UpdateOperationRequest body =
        new UpdateOperationRequest()
            .operationId(entry.getOperationId().orElse(null))
            .status(status)
            .tableUuid(entry.getTableUuid().orElse(null))
            .databaseName(entry.getDatabaseName())
            .tableName(entry.getTableName())
            .operationType(operationType());
    if (!client.get().updateOperation(entry.getOperationId().orElse(null), body).isPresent()) {
      log.error(
          "Failed to report operation result after retries; row will stay SCHEDULED until stale-timeout: operationId={} fqtn={}",
          entry.getOperationId().orElse(null),
          entry.getFqtn());
      otelEmitter.count(
          METRICS_SCOPE,
          "optimizer_update_failed",
          1,
          Attributes.of(AttributeKey.stringKey(AppConstants.TABLE_NAME), entry.getFqtn()));
    }
  }

  /** One unit of work in a batched maintenance job. */
  private final class TableWorker implements Callable<Boolean> {
    private final Operations ops;
    private final BatchEntry entry;
    private final Optional<OptimizerServiceClient> client;

    TableWorker(Operations ops, BatchEntry entry, Optional<OptimizerServiceClient> client) {
      this.ops = ops;
      this.entry = entry;
      this.client = client;
    }

    @Override
    public Boolean call() {
      String fqtn = entry.getFqtn();
      UpdateOperationRequest.StatusEnum status = UpdateOperationRequest.StatusEnum.FAILED;
      try {
        log.info(
            "{} start: fqtn={} operationId={}",
            operationLabel(),
            fqtn,
            entry.getOperationId().orElse(""));
        maintainTable(ops, entry);
        status = UpdateOperationRequest.StatusEnum.SUCCESS;
        log.info("{} success: fqtn={}", operationLabel(), fqtn);
      } catch (Throwable t) {
        log.error(
            "{} failed: fqtn={} operationId={}",
            operationLabel(),
            fqtn,
            entry.getOperationId().orElse(""),
            t);
      } finally {
        // Defensive: reportResult must not throw out of the finally block, since that would mask
        // the original failure and propagate up to awaitOne, which would then report FAILED again.
        try {
          reportResult(entry, status, client);
        } catch (Throwable t) {
          log.error(
              "reportResult itself threw; operation row will stay SCHEDULED until stale-timeout: fqtn={}",
              fqtn,
              t);
        }
      }
      return status == UpdateOperationRequest.StatusEnum.SUCCESS;
    }
  }

  /**
   * Per-table inputs for one operation row inside a bin. {@code operationId} and {@code tableUuid}
   * are exposed as {@link Optional} because the legacy scheduler path leaves them unset (no
   * optimizer-service context); the optimizer-service path always populates them.
   */
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.ToString
  public static class BatchEntry {
    @lombok.Getter private final String fqtn;
    private final String operationId;
    private final String tableUuid;
    @lombok.Getter private final String databaseName;
    @lombok.Getter private final String tableName;

    public Optional<String> getOperationId() {
      return Optional.ofNullable(operationId);
    }

    public Optional<String> getTableUuid() {
      return Optional.ofNullable(tableUuid);
    }
  }

  /**
   * Parses the parallel {@code --tableNames/--operationIds/--tableUuids} CSV lists into {@link
   * BatchEntry} rows, enforcing {@code maxBatchSize}. {@code maxBatchSizeLabel} is the human-readable
   * name of the cap surfaced in the over-limit error message (e.g. {@code "OFD_MAX_BATCH_SIZE"}).
   */
  protected static List<BatchEntry> buildEntries(
      String tableNames,
      String operationIds,
      String tableUuids,
      int maxBatchSize,
      String maxBatchSizeLabel) {
    if (tableNames == null || tableNames.isEmpty()) {
      throw new IllegalArgumentException("--tableNames is required and must be non-empty");
    }
    String[] tables = tableNames.split(",");
    if (tables.length > maxBatchSize) {
      throw new IllegalArgumentException(
          String.format(
              "Batch size %d exceeds %s=%d; reduce --batchMaxItems on the scheduler",
              tables.length, maxBatchSizeLabel, maxBatchSize));
    }
    String[] ops = StringUtils.isBlank(operationIds) ? null : operationIds.split(",");
    String[] uuids = StringUtils.isBlank(tableUuids) ? null : tableUuids.split(",");
    if (ops != null && ops.length != tables.length) {
      throw new IllegalArgumentException(
          String.format(
              "Parallel-list length mismatch: tableNames=%d operationIds=%d",
              tables.length, ops.length));
    }
    if (uuids != null && uuids.length != tables.length) {
      throw new IllegalArgumentException(
          String.format(
              "Parallel-list length mismatch: tableNames=%d tableUuids=%d",
              tables.length, uuids.length));
    }
    List<BatchEntry> entries = new ArrayList<>(tables.length);
    for (int i = 0; i < tables.length; i++) {
      String fqtn = tables[i].trim();
      String[] dbAndTable = fqtn.split("\\.", 2);
      if (dbAndTable.length != 2 || dbAndTable[0].isEmpty() || dbAndTable[1].isEmpty()) {
        throw new IllegalArgumentException(
            "tableNames entries must be fully-qualified (db.table): " + fqtn);
      }
      entries.add(
          BatchEntry.builder()
              .fqtn(fqtn)
              .operationId(ops == null ? null : ops[i].trim())
              .tableUuid(uuids == null ? null : uuids[i].trim())
              .databaseName(dbAndTable[0])
              .tableName(dbAndTable[1])
              .build());
    }
    return entries;
  }

  protected static String requireOption(CommandLine cmdLine, String name) {
    String value = cmdLine.getOptionValue(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  /** Long-only CLI option carrying a value (read with {@code cmdLine.getOptionValue(name)}). */
  protected static Option valueOpt(String name, String description) {
    return new Option(null, name, true, description);
  }

  /** Aliased CLI option carrying a value. {@code shortOpt} is the legacy single-letter alias. */
  protected static Option valueOpt(String name, String shortOpt, String description) {
    return new Option(shortOpt, name, true, description);
  }

  /** Long-only boolean CLI flag (read with {@code cmdLine.hasOption(name)}). */
  protected static Option flagOpt(String name, String description) {
    return new Option(null, name, false, description);
  }

  /** Visible for tests. */
  List<BatchEntry> getEntries() {
    return Collections.unmodifiableList(entries);
  }

  /** Visible for tests. */
  int getDriverParallelism() {
    return driverParallelism;
  }
}
