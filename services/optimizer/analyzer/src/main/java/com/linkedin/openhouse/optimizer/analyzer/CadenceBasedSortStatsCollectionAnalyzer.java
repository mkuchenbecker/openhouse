package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a sort-stats-collection run for a table.
 *
 * <p>Sort-stats-collection samples a partition, rewrites it with the table's sort order, and
 * records the resulting compression rate as a table property so layout decisions can weigh the
 * benefit of sorting. It is cadence-driven — refresh the estimate periodically.
 *
 * <h2>When it fires for a table</h2>
 *
 * All of the following must be true:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code
 *       maintenance.optimizer.sortStatsCollection.enabled=true} in its table properties.
 *   <li><b>Primary table.</b> The table is a primary (non-replica) table. This ports the
 *       eligibility guard from {@code SortStatsCollectionTask.shouldRunTask()}, which returns
 *       {@code metadata.isPrimary()} — a table's primary/replica type is surfaced in table
 *       properties under {@code openhouse.tableType}. Primary is defined as <i>not {@code
 *       REPLICA_TABLE}</i>, so an absent property defaults to primary (matching the rest of the
 *       codebase). Replicas are skipped because the sort rewrite runs against the primary copy.
 *       Enforced via {@link #isEnabled}.
 *   <li><b>No active operation already in flight</b> (PENDING/SCHEDULING/SCHEDULED); a CANCELED row
 *       does not block.
 *   <li><b>Cadence elapsed since the last completed run.</b> No history → schedule immediately;
 *       SUCCESS → wait {@code sortStatsCollection.success-retry-hours} (default 20h, below 24h so a
 *       daily refresh is guaranteed within any rolling 24-hour window); FAILED → wait {@code
 *       sortStatsCollection.failure-retry-hours} (default 1h).
 * </ol>
 */
@Component
public class CadenceBasedSortStatsCollectionAnalyzer extends CadenceBasedAnalyzer {

  static final String SORT_STATS_COLLECTION_ENABLED_PROPERTY =
      "maintenance.optimizer.sortStatsCollection.enabled";

  /** Reserved table property carrying the primary/replica table type. */
  static final String TABLE_TYPE_PROPERTY = "openhouse.tableType";

  /** {@link #TABLE_TYPE_PROPERTY} value identifying a replica table. */
  static final String REPLICA_TABLE_TYPE = "REPLICA_TABLE";

  public CadenceBasedSortStatsCollectionAnalyzer(
      @Value("${sortStatsCollection.success-retry-hours:20}") long successRetryHours,
      @Value("${sortStatsCollection.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.SORT_STATS_COLLECTION,
        SORT_STATS_COLLECTION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedSortStatsCollectionAnalyzer(CadencePolicy cadencePolicy) {
    super(
        OperationTypeDto.SORT_STATS_COLLECTION,
        SORT_STATS_COLLECTION_ENABLED_PROPERTY,
        cadencePolicy);
  }

  /**
   * Opt-in <b>and</b> primary-table. Ports {@code SortStatsCollectionTask.shouldRunTask() ->
   * metadata.isPrimary()} on top of the base opt-in check: replicas are skipped because the sort
   * rewrite targets the primary copy.
   *
   * <p>Primary is defined as <i>not replica</i>, so an absent {@code openhouse.tableType} property
   * defaults to primary. This matches the rest of the codebase (e.g. {@code
   * BatchedOrphanFilesDeletionSparkApp.resolveTtlSeconds} defaults the type to primary) and the M1
   * snapshots-expiration analyzer, rather than requiring an explicit {@code PRIMARY_TABLE} value.
   */
  @Override
  public boolean isEnabled(TableDto table) {
    return super.isEnabled(table) && isPrimary(table);
  }

  private boolean isPrimary(TableDto table) {
    return !REPLICA_TABLE_TYPE.equals(table.getTableProperties().get(TABLE_TYPE_PROPERTY));
  }
}
