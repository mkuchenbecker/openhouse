package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to (re)generate data-layout strategies for a table — the <b>generation</b> half of
 * the data-layout-strategy gen&rarr;exec pair.
 *
 * <p>Generation runs {@code OpenHouseDataLayoutStrategyGenerator} over the table's file/partition
 * stats and records a recommended compaction strategy in the table's own properties (property key
 * {@code write.data-layout.strategies}). That stored strategy is what the <b>execution</b> half
 * ({@link CadenceBasedDataLayoutStrategyExecutionAnalyzer}) later keys on. Generation is therefore
 * a pure cadence job: it periodically refreshes the recommendation so it tracks the table's current
 * shape, independent of whether a prior recommendation was ever executed.
 *
 * <h2>When generation fires for a table</h2>
 *
 * All of the following must be true (identical cadence contract to {@link
 * CadenceBasedOrphanFilesDeletionAnalyzer}):
 *
 * <ol>
 *   <li><b>Opt-in.</b> The table sets {@code
 *       maintenance.optimizer.dataLayoutStrategyGeneration.enabled=true} in its table properties.
 *   <li><b>No active operation already in flight</b> (PENDING/SCHEDULING/SCHEDULED). A CANCELED row
 *       does not block.
 *   <li><b>Cadence elapsed since the last completed run.</b> No history &rarr; schedule
 *       immediately; after SUCCESS wait {@code dls-generation.success-retry-hours} (default 24h);
 *       after FAILED wait {@code dls-generation.failure-retry-hours} (default 1h).
 * </ol>
 */
@Component
public class CadenceBasedDataLayoutStrategyGenerationAnalyzer extends CadenceBasedAnalyzer {

  static final String DLS_GENERATION_ENABLED_PROPERTY =
      "maintenance.optimizer.dataLayoutStrategyGeneration.enabled";

  public CadenceBasedDataLayoutStrategyGenerationAnalyzer(
      @Value("${dls-generation.success-retry-hours:24}") long successRetryHours,
      @Value("${dls-generation.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.DATA_LAYOUT_STRATEGY_GENERATION,
        DLS_GENERATION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedDataLayoutStrategyGenerationAnalyzer(CadencePolicy cadencePolicy) {
    super(
        OperationTypeDto.DATA_LAYOUT_STRATEGY_GENERATION,
        DLS_GENERATION_ENABLED_PROPERTY,
        cadencePolicy);
  }
}
