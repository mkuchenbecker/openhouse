package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a Data-Compaction run for a table.
 *
 * <p>Data compaction rewrites a table's many small data files into fewer, target-sized files,
 * improving read performance (fewer file opens, larger contiguous scans) and reducing metadata
 * overhead. Unlike the purely cadence-driven maintenance operations (e.g. orphan-files deletion),
 * compaction is only worth its (substantial) compute cost when the table's <i>file layout</i>
 * actually warrants it — so this analyzer is <b>stats-driven</b>: it inspects the table's current
 * snapshot metrics and fires only when the layout is fragmented. Because the decision depends on
 * per-table statistics rather than time alone, it implements {@link OperationAnalyzer} directly
 * rather than extending {@link CadenceBasedAnalyzer}; it still reuses {@link CadencePolicy} as a
 * time floor (see below).
 *
 * <h2>When compaction fires for a table</h2>
 *
 * All of the following must be true:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code maintenance.optimizer.dataCompaction.enabled=true} in
 *       its table properties. Without this flag, the analyzer ignores the table entirely.
 *   <li><b>No active operation already in flight, and cadence floor elapsed.</b> Delegated verbatim
 *       to {@link CadencePolicy}: a table with a non-CANCELED operation row (PENDING, SCHEDULING,
 *       SCHEDULED) belongs to the scheduler and is skipped; a table that <i>just</i> completed a
 *       compaction is held off for {@code dataCompaction.success-retry-hours} (a failure waits only
 *       {@code dataCompaction.failure-retry-hours}). This floor keeps a just-compacted — but still
 *       marginally fragmented — table from being re-picked on the very next analyzer pass.
 *   <li><b>File layout warrants compaction.</b> Evaluated from the table's latest snapshot metrics
 *       ({@link TableStatsDto.SnapshotMetrics}):
 *       <ul>
 *         <li>the table has at least {@code dataCompaction.min-files} current data files — a
 *             handful of files is never worth a rewrite, regardless of their size; and
 *         <li>the <i>average</i> file size ({@code tableSizeBytes / numCurrentFiles}) is below
 *             {@code dataCompaction.target-file-size-bytes} — i.e. the files are, on average,
 *             smaller than the target and there is real fragmentation to reclaim.
 *       </ul>
 *       A table with many but already-target-sized files is left alone (nothing to gain), and a
 *       table with only a few files is left alone (not worth the compute). If snapshot metrics are
 *       missing, the analyzer cannot assess the layout and skips the table.
 * </ol>
 *
 * <p>Stats are read straight off {@link TableDto#getStats()} — {@link
 * com.linkedin.openhouse.optimizer.analyzer.AnalyzerRunner} builds each {@link TableDto} from the
 * current-state {@code table_stats} row via {@link TableDto#fromRow}, which populates the snapshot
 * — so no extra stats-repository lookup is needed here. This mirrors how the scheduler's bin-packer
 * obtains the same snapshot metrics.
 *
 * <p>The thresholds and retry intervals are configurable via {@code application.properties} and can
 * be tuned per environment. The opt-in property is per-table and managed through the standard
 * table-properties API.
 */
@Component
public class StatsBasedDataCompactionAnalyzer implements OperationAnalyzer {

  static final String DATA_COMPACTION_ENABLED_PROPERTY =
      "maintenance.optimizer.dataCompaction.enabled";

  private final long minFiles;
  private final long targetFileSizeBytes;
  private final CadencePolicy cadencePolicy;

  /**
   * @param minFiles minimum current file count before compaction is even considered
   * @param targetFileSizeBytes target per-file size; a table whose average file size is below this
   *     is considered fragmented
   * @param successRetryHours time floor after a successful compaction before re-evaluating
   * @param failureRetryHours time floor after a failed compaction before retrying
   */
  public StatsBasedDataCompactionAnalyzer(
      @Value("${dataCompaction.min-files:100}") long minFiles,
      @Value("${dataCompaction.target-file-size-bytes:536870912}") long targetFileSizeBytes,
      @Value("${dataCompaction.success-retry-hours:24}") long successRetryHours,
      @Value("${dataCompaction.failure-retry-hours:1}") long failureRetryHours) {
    this(
        minFiles,
        targetFileSizeBytes,
        new CadencePolicy(
            Duration.ofHours(successRetryHours), Duration.ofHours(failureRetryHours)));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy} and thresholds. */
  StatsBasedDataCompactionAnalyzer(
      long minFiles, long targetFileSizeBytes, CadencePolicy cadencePolicy) {
    this.minFiles = minFiles;
    this.targetFileSizeBytes = targetFileSizeBytes;
    this.cadencePolicy = cadencePolicy;
  }

  @Override
  public OperationTypeDto getOperationType() {
    return OperationTypeDto.DATA_COMPACTION;
  }

  @Override
  public boolean isEnabled(TableDto table) {
    return "true".equals(table.getTableProperties().get(DATA_COMPACTION_ENABLED_PROPERTY));
  }

  @Override
  public boolean shouldSchedule(
      TableDto table,
      Optional<TableOperationDto> currentOp,
      Optional<TableOperationsHistoryDto> latestHistory) {
    // Cadence floor first: this also enforces the "no active non-CANCELED op" guard. Cheap, and it
    // short-circuits the stats read for tables the scheduler already owns or that were compacted
    // too recently.
    if (!cadencePolicy.shouldSchedule(currentOp, latestHistory)) {
      return false;
    }
    return layoutWarrantsCompaction(table);
  }

  /**
   * Returns {@code true} when the table has enough data files ({@code >= minFiles}) and its average
   * file size ({@code tableSizeBytes / numCurrentFiles}) is below {@code targetFileSizeBytes}. A
   * table missing snapshot metrics cannot be assessed and returns {@code false}.
   */
  private boolean layoutWarrantsCompaction(TableDto table) {
    TableStatsDto.SnapshotMetrics snapshot =
        Optional.ofNullable(table.getStats()).map(TableStatsDto::getSnapshot).orElse(null);
    if (snapshot == null) {
      return false;
    }
    long numFiles = snapshot.getNumCurrentFiles() == null ? 0L : snapshot.getNumCurrentFiles();
    if (numFiles < minFiles) {
      return false;
    }
    long sizeBytes = snapshot.getTableSizeBytes() == null ? 0L : snapshot.getTableSizeBytes();
    long averageFileSizeBytes = sizeBytes / numFiles;
    return averageFileSizeBytes < targetFileSizeBytes;
  }
}
