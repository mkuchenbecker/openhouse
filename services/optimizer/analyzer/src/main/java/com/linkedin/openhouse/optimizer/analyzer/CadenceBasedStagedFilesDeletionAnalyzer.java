package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to schedule a Staged-Files-Deletion (SFD) run for a table.
 *
 * <p>SFD removes files left behind under the table's staging/trash directory by staged or aborted
 * writes that were never committed. Running it too often wastes compute; running it too rarely lets
 * abandoned staged files accumulate and bloats storage cost. This analyzer balances the two on a
 * per-table cadence.
 *
 * <h2>When SFD fires for a table</h2>
 *
 * All of the following must be true:
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code
 *       maintenance.optimizer.stagedFilesDeletion.enabled=true} in its table properties. Without
 *       this flag, the analyzer ignores the table entirely.
 *   <li><b>No active operation already in flight.</b> If the table has a non-CANCELED operation row
 *       (PENDING, SCHEDULING, or SCHEDULED), the scheduler already owns it and the analyzer stays
 *       out. A CANCELED row does not block — it is treated as if no operation exists.
 *   <li><b>Cadence elapsed since the last completed run.</b>
 *       <ul>
 *         <li>If the table has <i>no</i> prior history, schedule immediately.
 *         <li>If the most recent history entry is {@code SUCCESS}, wait {@code
 *             stagedFilesDeletion.success-retry-hours} (default 16h) after its {@code completedAt}
 *             before scheduling again. Set below 24h so that even when a run lands at an unlucky
 *             time of day, at least one re-evaluation is guaranteed within any rolling 24-hour
 *             window.
 *         <li>If the most recent history entry is {@code FAILED}, wait {@code
 *             stagedFilesDeletion.failure-retry-hours} (default 1h) before retrying — shorter than
 *             the success interval so transient failures recover quickly.
 *       </ul>
 * </ol>
 *
 * <p>The two retry intervals are configurable via {@code application.properties} and can be tuned
 * per environment. The opt-in property is per-table and managed through the standard table-
 * properties API.
 */
@Component
public class CadenceBasedStagedFilesDeletionAnalyzer extends CadenceBasedAnalyzer {

  static final String SFD_ENABLED_PROPERTY = "maintenance.optimizer.stagedFilesDeletion.enabled";

  public CadenceBasedStagedFilesDeletionAnalyzer(
      @Value("${stagedFilesDeletion.success-retry-hours:16}") long successRetryHours,
      @Value("${stagedFilesDeletion.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.STAGED_FILES_DELETION,
        SFD_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedStagedFilesDeletionAnalyzer(CadencePolicy cadencePolicy) {
    super(OperationTypeDto.STAGED_FILES_DELETION, SFD_ENABLED_PROPERTY, cadencePolicy);
  }
}
