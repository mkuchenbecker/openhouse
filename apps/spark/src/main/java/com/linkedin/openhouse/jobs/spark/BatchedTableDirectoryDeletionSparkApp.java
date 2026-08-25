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
 * Batched dropped-table-directory deletion Spark app — a <b>minimal, forward-looking scaffold</b>.
 * One Spark job purges a list of storage directories belonging to dropped/purged tables. It is the
 * multi-directory counterpart of the legacy database-scoped {@code TableDirectoryDeletionTask}.
 *
 * <h2>Wiring</h2>
 *
 * <p>{@code TABLE_DIRECTORY_DELETION} has <b>no</b> single-table reference Spark app in this tree —
 * the legacy path runs it database-scoped ({@code --databaseName}) and lets the job self-discover
 * the directories to remove. The Optimizer now drives it database-scoped too: its
 * analyzer/scheduler (backed by the M6 nullable-{@code table_uuid} + {@code operation_scope} schema
 * change) launch this app with {@code --databaseNames}. This app supplies the batched
 * <em>execution</em> half; the per-database <em>discovery</em> of which directories belong to
 * dropped tables is the one remaining execution-side piece (see {@link #maintainTable}). See {@code
 * services/optimizer/DIRECTORY-DELETION-DESIGN.md}.
 *
 * <p>When invoked with explicit {@code --tableDirectoryPaths}, each {@link BatchEntry} carries the
 * directory path in {@code fqtn} and the app is fully functional.
 *
 * <p>Example invocation:
 *
 * <pre>{@code
 * com.linkedin.openhouse.jobs.spark.BatchedTableDirectoryDeletionSparkApp \
 *   --tableDirectoryPaths /data/openhouse/db/dropped-table-1,/data/openhouse/db/dropped-table-2 \
 *   --trashDir .trash \
 *   --driverParallelism 4
 * }</pre>
 */
@Slf4j
public class BatchedTableDirectoryDeletionSparkApp extends BatchedMaintenanceSparkApp {

  private final String trashDir;

  /**
   * When true, each {@link BatchEntry} names a database (optimizer database-scoped dispatch via
   * {@code --databaseNames}) rather than a concrete dropped-table directory path. Per-database
   * discovery of dropped-table directories inside this app is the one remaining execution-side
   * piece (see {@link #maintainTable}).
   */
  private final boolean databaseScoped;

  public BatchedTableDirectoryDeletionSparkApp(
      String jobId,
      StateManager stateManager,
      OtelEmitter otelEmitter,
      List<BatchEntry> entries,
      String resultsEndpoint,
      int driverParallelism,
      String trashDir,
      boolean databaseScoped) {
    super(jobId, stateManager, otelEmitter, entries, resultsEndpoint, driverParallelism);
    this.trashDir = trashDir;
    this.databaseScoped = databaseScoped;
  }

  @Override
  protected UpdateOperationRequest.OperationTypeEnum operationType() {
    return UpdateOperationRequest.OperationTypeEnum.TABLE_DIRECTORY_DELETION;
  }

  @Override
  protected String operationLabel() {
    return "TABLE_DIRECTORY_DELETION";
  }

  /**
   * Purges a single dropped-table directory. Because the owning table is already gone, the
   * directory is removed outright: {@link Operations#deleteStagedOrphanDirectory} clears any staged
   * trash and then deletes the directory tree. Passing {@code System.currentTimeMillis()} as the
   * threshold makes every staged file eligible.
   */
  @Override
  protected void maintainTable(Operations ops, BatchEntry entry) {
    if (databaseScoped) {
      // The optimizer dispatched at DATABASE granularity. Enumerating a database's dropped-table
      // directories requires the Tables-Service list of live/dropped tables — the TablesClient-
      // backed scan that is the one remaining execution-side piece. Fail loudly rather than guess.
      // The analyzer opt-in is OFF by default. See DIRECTORY-DELETION-DESIGN.md.
      throw new UnsupportedOperationException(
          "Per-database dropped-table-directory discovery is not yet implemented in the batched "
              + "app; database="
              + entry.getDatabaseName()
              + ". Invoke with --tableDirectoryPaths for explicit paths, or complete the "
              + "TablesClient-backed scan. See services/optimizer/DIRECTORY-DELETION-DESIGN.md.");
    }
    Path tableDirectoryPath = new Path(entry.getFqtn());
    ops.deleteStagedOrphanDirectory(tableDirectoryPath, trashDir, System.currentTimeMillis());
    log.info("Purged dropped-table directory path {}", tableDirectoryPath);
    otelEmitter.count(
        METRICS_SCOPE,
        AppConstants.STAGED_DIRECTORY_COUNT,
        1,
        Attributes.of(
            AttributeKey.stringKey(AppConstants.TABLE_DIRECTORY_PATH),
            tableDirectoryPath.toString()));
  }

  public static void main(String[] args) {
    OtelEmitter otelEmitter =
        new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));
    createApp(args, otelEmitter).run();
  }

  public static BatchedTableDirectoryDeletionSparkApp createApp(
      String[] args, OtelEmitter otelEmitter) {
    List<Option> extraOptions =
        Arrays.asList(
            valueOpt(
                "tableDirectoryPaths", "Comma-separated list of dropped-table directory paths"),
            valueOpt(
                "databaseNames",
                "Comma-separated databases to scan (optimizer database-scoped dispatch)"),
            valueOpt(
                "operationIds", "Comma-separated operation UUIDs, parallel to the work targets"),
            valueOpt("resultsEndpoint", "Base URL of the Optimizer Service (optional)"),
            valueOpt("driverParallelism", "Worker threads in this batch (default 1)"),
            valueOpt(
                "trashDir", "b", "Trash subdirectory cleared before the directory is deleted"));

    CommandLine cmdLine = createCommandLine(args, extraOptions);

    boolean databaseScoped = cmdLine.hasOption("databaseNames");
    List<BatchEntry> entries =
        databaseScoped
            ? BatchedOrphanTableDirectoryDeletionSparkApp.buildDatabaseEntries(
                cmdLine.getOptionValue("databaseNames"),
                cmdLine.getOptionValue("operationIds"),
                AppConstants.TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE,
                "TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE")
            : buildEntries(
                cmdLine.getOptionValue("tableDirectoryPaths"),
                cmdLine.getOptionValue("operationIds"));

    return new BatchedTableDirectoryDeletionSparkApp(
        getJobId(cmdLine),
        createStateManager(cmdLine, otelEmitter),
        otelEmitter,
        entries,
        cmdLine.getOptionValue("resultsEndpoint"),
        Integer.parseInt(cmdLine.getOptionValue("driverParallelism", "1")),
        cmdLine.getOptionValue("trashDir", ".trash"),
        databaseScoped);
  }

  /**
   * Parses {@code --tableDirectoryPaths} (with optional parallel {@code --operationIds}) into
   * {@link BatchEntry} rows, enforcing {@link AppConstants#TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE}
   * at parse time. Delegates to the shared directory-entry parser in {@link
   * BatchedOrphanTableDirectoryDeletionSparkApp}.
   */
  static List<BatchEntry> buildEntries(String tableDirectoryPaths, String operationIds) {
    return BatchedOrphanTableDirectoryDeletionSparkApp.buildDirectoryEntries(
        tableDirectoryPaths,
        operationIds,
        AppConstants.TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE,
        "TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE");
  }
}
