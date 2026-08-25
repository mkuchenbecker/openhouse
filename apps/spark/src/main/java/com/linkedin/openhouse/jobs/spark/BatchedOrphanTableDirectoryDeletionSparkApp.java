package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.spark.state.StateManager;
import com.linkedin.openhouse.jobs.util.AppConstants;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.optimizer.client.model.UpdateOperationRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.hadoop.fs.Path;

/**
 * Batched orphan-table-directory deletion Spark app. One Spark job stages-then-deletes a list of
 * <em>orphaned</em> table directories — storage directories whose owning table has been dropped and
 * is no longer registered in the Tables Service. It is the multi-directory counterpart of {@link
 * OrphanTableDirectoryDeletionSparkApp}, and reuses that app's exact two-phase staging/deletion
 * logic per directory ({@link Operations#deleteOrphanDirectory} then {@link
 * Operations#deleteStagedOrphanDirectory}).
 *
 * <h2>Why the batch unit is a directory path, not a {@code db.table}</h2>
 *
 * <p>Unlike OFD, the unit of work here is a <b>directory of a table that no longer exists</b>.
 * There is no live table to resolve via {@code ops.getTable(fqtn)}, no {@code tableUuid}, and no
 * {@code table_stats}/{@code table_operations} row to key on. Each {@link BatchEntry} therefore
 * carries the full directory path in its {@code fqtn} slot; {@code databaseName}/{@code tableName}
 * are populated best-effort (parent-dir / basename) for log and trace echo only. {@code
 * operationId} and {@code tableUuid} are always absent, so the per-operation Optimizer-Service
 * callback in {@link BatchedMaintenanceSparkApp} is a no-op for this app.
 *
 * <p>The Optimizer drives this app <b>database-scoped</b>: its analyzer/scheduler discover work by
 * enumerating databases (not {@code table_stats} tables) and launch this app with {@code
 * --databaseNames}. Per-database orphan-directory <em>discovery</em> inside the app is the one
 * remaining execution-side piece (see {@link #maintainTable}); until it lands, a {@code
 * --databaseNames} invocation fails fast. The app is fully functional with an explicit {@code
 * --tableDirectoryPaths} list (e.g. from the legacy {@code JobsScheduler}). See {@code
 * services/optimizer/DIRECTORY-DELETION-DESIGN.md} for the full rationale.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedOrphanTableDirectoryDeletionSparkApp \
 *   --tableDirectoryPaths /data/openhouse/db/uuid-dir-1,/data/openhouse/db/uuid-dir-2 \
 *   --trashDir .trash \
 *   --orphanDaysOld 7 \
 *   --stagedDeleteDaysOld 3 \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedOrphanTableDirectoryDeletionSparkApp extends BatchedMaintenanceSparkApp {

  private final String trashDir;
  private final int orphanOlderThanDays;
  private final int stagedDeleteOlderThanDays;

  /**
   * When true, each {@link BatchEntry} names a <em>database</em> (from the optimizer's
   * database-scoped scheduler via {@code --databaseNames}) rather than a concrete directory path.
   * Per-database orphan-directory discovery inside this app is the one remaining execution-side
   * piece (see {@link #maintainTable}); when false the app deletes the explicit {@code
   * --tableDirectoryPaths} it was given.
   */
  private final boolean databaseScoped;

  public BatchedOrphanTableDirectoryDeletionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      String trashDir,
      int orphanOlderThanDays,
      int stagedDeleteOlderThanDays,
      boolean databaseScoped) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.trashDir = trashDir;
    this.orphanOlderThanDays = orphanOlderThanDays;
    this.stagedDeleteOlderThanDays = stagedDeleteOlderThanDays;
    this.databaseScoped = databaseScoped;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.ORPHAN_DIRECTORY_DELETION;
  }

  @Override
  protected String operationLabel() {
    return "ORPHAN_DIRECTORY_DELETION";
  }

  /**
   * Applies the reference two-phase orphan-directory cleanup to a single directory. Phase one
   * stages files older than {@code orphanOlderThanDays} into the trash subdirectory; a directory
   * that is already fully staged (nothing new to stage) is then hard-deleted once its staged files
   * clear the {@code stagedDeleteOlderThanDays} grace window. Mirrors {@link
   * OrphanTableDirectoryDeletionSparkApp#runInner}.
   */
  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    if (databaseScoped) {
      // The optimizer discovered work at DATABASE granularity (it has no storage access to list
      // individual orphan directories). Safely turning a database into its orphan directories
      // requires the Tables-Service list of live tables (to know which dirs are orphaned) — a
      // TablesClient-backed scan that is the one remaining execution-side piece. Fail loudly rather
      // than risk deleting live directories. The analyzer opt-in is OFF by default, so this only
      // fires once an operator explicitly enables it. See DIRECTORY-DELETION-DESIGN.md.
      throw new UnsupportedOperationException(
          "Per-database orphan-directory discovery is not yet implemented in the batched app; "
              + "database="
              + entry.getDatabaseName()
              + ". Invoke with --tableDirectoryPaths for explicit paths, or complete the "
              + "TablesClient-backed scan. See services/optimizer/DIRECTORY-DELETION-DESIGN.md.");
    }
    Path tableDirectoryPath = new Path(entry.getFqtn());
    long orphanThresholdMillis =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(orphanOlderThanDays);
    if (ops.deleteOrphanDirectory(tableDirectoryPath, trashDir, orphanThresholdMillis)) {
      log.info(
          "Staged orphan table directory path {}; timeForSelection {}d",
          tableDirectoryPath,
          orphanOlderThanDays);
      otelEmitter.count(
          METRICS_SCOPE,
          AppConstants.ORPHAN_DIRECTORY_COUNT,
          1,
          Attributes.of(
              AttributeKey.stringKey(AppConstants.TABLE_DIRECTORY_PATH),
              tableDirectoryPath.toString()));
    } else {
      long deleteThresholdMillis =
          orphanThresholdMillis - TimeUnit.DAYS.toMillis(stagedDeleteOlderThanDays);
      ops.deleteStagedOrphanDirectory(tableDirectoryPath, trashDir, deleteThresholdMillis);
      log.info(
          "Deleted staged orphan table directory path {}; timeForSelection {}d",
          tableDirectoryPath,
          stagedDeleteOlderThanDays);
      otelEmitter.count(
          METRICS_SCOPE,
          AppConstants.STAGED_DIRECTORY_COUNT,
          1,
          Attributes.of(
              AttributeKey.stringKey(AppConstants.TABLE_DIRECTORY_PATH),
              tableDirectoryPath.toString()));
    }
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedOrphanTableDirectoryDeletionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt("tableDirectoryPaths", "Comma-separated list of orphan table directory paths"),
            valueOpt(
                "databaseNames",
                "Comma-separated databases to scan (optimizer database-scoped dispatch)"),
            valueOpt(
                "operationIds", "Comma-separated operation UUIDs, parallel to the work targets"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service (optional)"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt("trashDir", "b", "Trash subdirectory to stage files into before deletion"),
            valueOpt("orphanDaysOld", "o", "Directories whose files are this old are staged"),
            valueOpt("stagedDeleteDaysOld", "d", "Staged directories this old are deleted"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    // The optimizer scheduler dispatches --databaseNames; direct/legacy invocation uses explicit
    // --tableDirectoryPaths. Exactly one is expected.
    boolean databaseScoped = cmdLine.hasOption("databaseNames");
    List<BatchEntry> entries =
        databaseScoped
            ? buildDatabaseEntries(
                cmdLine.getOptionValue("databaseNames"),
                cmdLine.getOptionValue("operationIds"),
                AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE,
                "ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE")
            : buildEntries(
                cmdLine.getOptionValue("tableDirectoryPaths"),
                cmdLine.getOptionValue("operationIds"));

    return new BatchedOrphanTableDirectoryDeletionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        cmdLine.getOptionValue("resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        cmdLine.getOptionValue("trashDir", ".trash"),
        Integer.parseInt(cmdLine.getOptionValue("orphanDaysOld", "7")),
        Integer.parseInt(cmdLine.getOptionValue("stagedDeleteDaysOld", "3")),
        databaseScoped);
  }

  /**
   * Parses the {@code --tableDirectoryPaths} CSV (with the optional parallel {@code
   * --operationIds}) into {@link BatchEntry} rows, enforcing {@link
   * AppConstants#ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE} at parse time — before any instance
   * exists — so an over-long argv fails fast. The directory path is stored in {@code fqtn}; {@code
   * databaseName}/{@code tableName} are best-effort echo values.
   */
  static List<BatchEntry> buildEntries(String tableDirectoryPaths, String operationIds) {
    return buildDirectoryEntries(
        tableDirectoryPaths,
        operationIds,
        AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE,
        "ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE");
  }

  /**
   * Shared parser for directory-scoped batched apps (this app and {@link
   * BatchedTableDirectoryDeletionSparkApp}). The unit of work is a filesystem directory path rather
   * than a {@code db.table}, so it cannot reuse {@link BatchedMaintenanceSparkApp#buildEntries}
   * (which requires fully-qualified table names). {@code operationIds}, when present, must be
   * parallel to the paths.
   */
  static List<BatchEntry> buildDirectoryEntries(
      String directoryPaths, String operationIds, int maxBatchSize, String maxBatchSizeLabel) {
    if (directoryPaths == null || directoryPaths.isEmpty()) {
      throw new IllegalArgumentException("--tableDirectoryPaths is required and must be non-empty");
    }
    String[] paths = directoryPaths.split(",");
    if (paths.length > maxBatchSize) {
      throw new IllegalArgumentException(
          String.format(
              "Batch size %d exceeds %s=%d; reduce --batchMaxItems on the scheduler",
              paths.length, maxBatchSizeLabel, maxBatchSize));
    }
    String[] ops =
        (operationIds == null || operationIds.trim().isEmpty()) ? null : operationIds.split(",");
    if (ops != null && ops.length != paths.length) {
      throw new IllegalArgumentException(
          String.format(
              "Parallel-list length mismatch: tableDirectoryPaths=%d operationIds=%d",
              paths.length, ops.length));
    }
    List<BatchEntry> entries = new ArrayList<>(paths.length);
    for (int i = 0; i < paths.length; i++) {
      String raw = paths[i].trim();
      if (raw.isEmpty()) {
        throw new IllegalArgumentException("tableDirectoryPaths entries must be non-empty");
      }
      Path path = new Path(raw);
      String basename = path.getName();
      String dbName = path.getParent() == null ? "" : path.getParent().getName();
      entries.add(
          BatchEntry.builder()
              .fqtn(raw)
              .operationId(ops == null ? null : ops[i].trim())
              .tableUuid(null)
              .databaseName(dbName)
              .tableName(basename)
              .build());
    }
    return entries;
  }

  /**
   * Shared parser for the optimizer's database-scoped dispatch: {@code --databaseNames} (with the
   * optional parallel {@code --operationIds}). Each database becomes one {@link BatchEntry} whose
   * {@code fqtn}/{@code databaseName} are the database name and whose {@code operationId} closes
   * the per-operation Optimizer-Service callback loop. Enforces {@code maxBatchSize} at parse time.
   */
  static List<BatchEntry> buildDatabaseEntries(
      String databaseNames, String operationIds, int maxBatchSize, String maxBatchSizeLabel) {
    if (databaseNames == null || databaseNames.isEmpty()) {
      throw new IllegalArgumentException("--databaseNames is required and must be non-empty");
    }
    String[] dbs = databaseNames.split(",");
    if (dbs.length > maxBatchSize) {
      throw new IllegalArgumentException(
          String.format(
              "Batch size %d exceeds %s=%d; reduce --max-databases-per-bin on the scheduler",
              dbs.length, maxBatchSizeLabel, maxBatchSize));
    }
    String[] ops =
        (operationIds == null || operationIds.trim().isEmpty()) ? null : operationIds.split(",");
    if (ops != null && ops.length != dbs.length) {
      throw new IllegalArgumentException(
          String.format(
              "Parallel-list length mismatch: databaseNames=%d operationIds=%d",
              dbs.length, ops.length));
    }
    List<BatchEntry> entries = new ArrayList<>(dbs.length);
    for (int i = 0; i < dbs.length; i++) {
      String db = dbs[i].trim();
      if (db.isEmpty()) {
        throw new IllegalArgumentException("databaseNames entries must be non-empty");
      }
      entries.add(
          BatchEntry.builder()
              .fqtn(db)
              .operationId(ops == null ? null : ops[i].trim())
              .tableUuid(null)
              .databaseName(db)
              .tableName(null)
              .build());
    }
    return entries;
  }
}
