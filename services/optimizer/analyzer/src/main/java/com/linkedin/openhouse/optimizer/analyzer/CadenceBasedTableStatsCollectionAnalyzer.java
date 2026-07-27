package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a table-stats-collection run for a table.
 *
 * <p>Table-stats-collection <i>produces</i> the {@code table_stats} rows every other analyzer and
 * the scheduler's bin-packer consume; it does not itself read stats to decide. Its eligibility is
 * therefore purely cadence-driven — refresh the snapshot periodically so downstream decisions run
 * on fresh data.
 *
 * <h2>When it fires for a table</h2>
 *
 * <p>Eligibility is <b>opt-in only</b> — this mirrors {@code
 * TableStatsCollectionTask.shouldRunTask()}, which returns {@code true} unconditionally. The
 * remaining gates are handled by the inherited cadence machinery:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code
 *       maintenance.optimizer.tableStatsCollection.enabled=true} in its table properties. Without
 *       this flag, the analyzer ignores the table entirely.
 *   <li><b>No active operation already in flight</b> (PENDING/SCHEDULING/SCHEDULED); a CANCELED row
 *       does not block.
 *   <li><b>Cadence elapsed since the last completed run.</b> No history → schedule immediately;
 *       SUCCESS → wait {@code tableStatsCollection.success-retry-hours} (default 20h, below 24h so
 *       a daily refresh is guaranteed within any rolling 24-hour window); FAILED → wait {@code
 *       tableStatsCollection.failure-retry-hours} (default 1h).
 * </ol>
 */
@Component
public class CadenceBasedTableStatsCollectionAnalyzer extends CadenceBasedAnalyzer {

  static final String TABLE_STATS_COLLECTION_ENABLED_PROPERTY =
      "maintenance.optimizer.tableStatsCollection.enabled";

  public CadenceBasedTableStatsCollectionAnalyzer(
      @Value("${tableStatsCollection.success-retry-hours:20}") long successRetryHours,
      @Value("${tableStatsCollection.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.TABLE_STATS_COLLECTION,
        TABLE_STATS_COLLECTION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedTableStatsCollectionAnalyzer(CadencePolicy cadencePolicy) {
    super(
        OperationTypeDto.TABLE_STATS_COLLECTION,
        TABLE_STATS_COLLECTION_ENABLED_PROPERTY,
        cadencePolicy);
  }
}
