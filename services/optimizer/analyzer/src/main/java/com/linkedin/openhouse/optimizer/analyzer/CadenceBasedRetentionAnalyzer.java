package com.linkedin.openhouse.optimizer.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a RETENTION run for a table.
 *
 * <p>Retention drops rows that fall outside the table's configured data-retention window (keep the
 * last {@code count} {@code granularity}s of the retention time column). Like OFD, it re-evaluates
 * on a per-table success/failure cadence — but unlike OFD it is <b>not</b> universally applicable:
 * only tables that actually carry a retention <i>time column</i> can run it. This analyzer
 * therefore layers a retention-eligibility guard on top of the shared {@link CadenceBasedAnalyzer}
 * cadence.
 *
 * <h2>When RETENTION fires for a table</h2>
 *
 * All of the following must be true:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code maintenance.optimizer.retention.enabled=true} in its
 *       table properties.
 *   <li><b>Retention-eligible</b> (see below).
 *   <li><b>No active operation already in flight</b> and <b>cadence elapsed</b> — identical to OFD,
 *       delegated verbatim to {@link CadencePolicy}. Success re-evaluates after {@code
 *       retention.success-retry-hours} (default 16h); failure retries after {@code
 *       retention.failure-retry-hours} (default 1h).
 * </ol>
 *
 * <h2>Retention-eligibility guard — ported from {@code TableRetentionTask}</h2>
 *
 * <p>The authoritative jobs-side rule is {@code TableRetentionTask#shouldRunTask() = isPrimary() &&
 * retentionConfig != null}, where {@code retentionConfig} is non-null only when the table has a
 * configured retention policy <b>and</b> a resolvable time column — either a time-partitioning spec
 * or an explicit string {@code retention.columnPattern} (see {@code
 * TablesClient#getTableRetention}). A non-partitioned, retention-unset, or replica table is
 * skipped.
 *
 * <p><b>What signal this analyzer uses.</b> The only table metadata the optimizer's {@link
 * TableDto} carries is {@code tableProperties} (plus size/file-count stats). OpenHouse serializes
 * the table's policies into the preserved {@code "policies"} table property, so the retention
 * <i>policy</i> and its optional {@code columnPattern} <b>are</b> visible here. {@link
 * #isEnabled(TableDto)} therefore requires opt-in <b>and</b> a {@code policies.retention} block
 * that carries a resolvable string time column ({@code retention.columnPattern.columnName}). This
 * is a strict subset of the jobs-side rule: every table it admits is genuinely retention-eligible,
 * so it never schedules a wasted/failing retention job.
 *
 * <p><b>Documented gap (deferred pending a stats field).</b> Two jobs-side signals are <i>not</i>
 * modeled on {@link TableDto} and cannot be evaluated here:
 *
 * <ul>
 *   <li><b>Time-partitioning.</b> A time-partitioned table qualifies even without a {@code
 *       columnPattern} (the retention column is its partition column), but the Iceberg partition
 *       spec is not a table property and is absent from the DTO. Such tables are conservatively
 *       skipped here — a false negative. Closing this gap needs a partitioning /
 *       retention-time-column signal plumbed onto {@code TableDto}/{@code TableStatsDto}; until
 *       then the jobs-side {@code TableRetentionTask#shouldRunTask} remains the authoritative gate
 *       and {@link com.linkedin.openhouse.jobs.spark.BatchedRetentionSparkApp} re-resolves each
 *       table's column at runtime (no-oping a table whose column cannot be resolved).
 *   <li><b>Primary vs. replica table type.</b> {@code openhouse.tableType} is not guaranteed on the
 *       stats DTO; the primary-only guard is likewise deferred to the jobs-side rule above.
 * </ul>
 */
@Component
public class CadenceBasedRetentionAnalyzer extends CadenceBasedAnalyzer {

  static final String RETENTION_ENABLED_PROPERTY = "maintenance.optimizer.retention.enabled";

  /** OpenHouse-preserved table property holding the serialized policies JSON blob. */
  static final String POLICIES_PROPERTY = "policies";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public CadenceBasedRetentionAnalyzer(
      @Value("${retention.success-retry-hours:16}") long successRetryHours,
      @Value("${retention.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.RETENTION,
        RETENTION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedRetentionAnalyzer(CadencePolicy cadencePolicy) {
    super(OperationTypeDto.RETENTION, RETENTION_ENABLED_PROPERTY, cadencePolicy);
  }

  /**
   * A table is eligible only if it is opted in (via the shared cadence base) <b>and</b> is
   * retention-eligible — i.e. it carries a resolvable retention time column that this DTO can see.
   * See the class javadoc for exactly which signal is used and the deferred gap.
   */
  @Override
  public boolean isEnabled(TableDto table) {
    return super.isEnabled(table) && isRetentionEligible(table);
  }

  /**
   * Returns {@code true} iff the table's {@code "policies"} property carries a {@code retention}
   * block with a resolvable string time column ({@code retention.columnPattern.columnName}). Any
   * absent/blank/malformed policies, missing retention block, or missing column pattern yields
   * {@code false} — the conservative, no-false-positive choice.
   */
  private boolean isRetentionEligible(TableDto table) {
    String policies =
        table.getTableProperties() == null
            ? null
            : table.getTableProperties().get(POLICIES_PROPERTY);
    if (policies == null || policies.trim().isEmpty()) {
      return false;
    }
    try {
      JsonNode retention = OBJECT_MAPPER.readTree(policies).path("retention");
      if (retention.isMissingNode() || retention.isNull()) {
        return false;
      }
      JsonNode columnName = retention.path("columnPattern").path("columnName");
      return columnName.isTextual() && !columnName.asText().trim().isEmpty();
    } catch (Exception e) {
      // Unparseable policies blob — treat as ineligible rather than risk scheduling a bad job.
      return false;
    }
  }
}
