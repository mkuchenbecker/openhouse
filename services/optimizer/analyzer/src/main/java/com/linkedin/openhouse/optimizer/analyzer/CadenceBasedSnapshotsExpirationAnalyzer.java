package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a Snapshots-Expiration run for a table.
 *
 * <p>Snapshot expiration drops Iceberg snapshots older than the table's retention window (and,
 * optionally, beyond a version count), freeing the metadata and data files those stale snapshots
 * pinned. The current snapshot is always preserved. Running it too often churns metadata; running
 * it too rarely lets snapshot history — and the storage it pins — accumulate. This analyzer
 * balances the two on a per-table cadence, reusing the shared {@link CadenceBasedAnalyzer}
 * machinery.
 *
 * <h2>When snapshots expiration fires for a table</h2>
 *
 * All of the following must be true:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code
 *       maintenance.optimizer.snapshotsExpiration.enabled=true} in its table properties. Without
 *       this flag, the analyzer ignores the table entirely.
 *   <li><b>Primary table.</b> Replicas track the primary's snapshot lineage and must not have
 *       snapshots expired independently — this mirrors {@code
 *       TableSnapshotsExpirationTask.shouldRunTask()} (which gates on {@code
 *       metadata.isPrimary()}).
 *   <li><b>No active operation already in flight,</b> and <b>cadence elapsed since the last
 *       completed run</b> — both delegated verbatim to {@link CadencePolicy} via the base class.
 *       Success runs wait {@code snapshotsExpiration.success-retry-hours} (default 16h); failures
 *       retry after {@code snapshotsExpiration.failure-retry-hours} (default 1h). The success
 *       interval is set below 24h so at least one re-evaluation lands within any rolling 24-hour
 *       window.
 * </ol>
 *
 * <p>The two retry intervals are configurable via {@code application.properties}. The opt-in
 * property is per-table and managed through the standard table-properties API.
 */
@Component
public class CadenceBasedSnapshotsExpirationAnalyzer extends CadenceBasedAnalyzer {

  static final String SNAPSHOTS_EXPIRATION_ENABLED_PROPERTY =
      "maintenance.optimizer.snapshotsExpiration.enabled";

  // Mirrors AppConstants.OPENHOUSE_TABLE_TYPE_KEY / TABLE_TYPE_REPLICA in the spark-apps module.
  // Duplicated as string literals here to avoid the analyzer taking a dependency on that module.
  static final String OPENHOUSE_TABLE_TYPE_KEY = "openhouse.tableType";
  static final String TABLE_TYPE_REPLICA = "REPLICA_TABLE";

  public CadenceBasedSnapshotsExpirationAnalyzer(
      @Value("${snapshotsExpiration.success-retry-hours:16}") long successRetryHours,
      @Value("${snapshotsExpiration.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.SNAPSHOTS_EXPIRATION,
        SNAPSHOTS_EXPIRATION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedSnapshotsExpirationAnalyzer(CadencePolicy cadencePolicy) {
    super(
        OperationTypeDto.SNAPSHOTS_EXPIRATION,
        SNAPSHOTS_EXPIRATION_ENABLED_PROPERTY,
        cadencePolicy);
  }

  /**
   * A table is eligible only when it is both opted in (base check) and primary. Replicas are
   * skipped — see class javadoc and {@code TableSnapshotsExpirationTask.shouldRunTask()}.
   */
  @Override
  public boolean isEnabled(TableDto table) {
    return super.isEnabled(table) && isPrimary(table);
  }

  private static boolean isPrimary(TableDto table) {
    // Absent property defaults to primary; only an explicit REPLICA_TABLE marks a table as a
    // replica.
    return !TABLE_TYPE_REPLICA.equals(
        table.getTableProperties().getOrDefault(OPENHOUSE_TABLE_TYPE_KEY, ""));
  }
}
