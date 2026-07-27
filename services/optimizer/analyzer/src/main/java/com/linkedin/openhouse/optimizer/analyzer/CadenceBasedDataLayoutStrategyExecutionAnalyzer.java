package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when to execute a previously generated data-layout strategy for a table — the
 * <b>execution</b> half of the data-layout-strategy gen&rarr;exec pair.
 *
 * <p><b>How execution knows there is work.</b> The generation half ({@link
 * CadenceBasedDataLayoutStrategyGenerationAnalyzer} &rarr; {@code
 * BatchedDataLayoutStrategyGenerationSparkApp}) records its recommendation by writing the strategy
 * JSON into the table's own property {@code write.data-layout.strategies}. That property is the
 * gen&rarr;exec signal: execution should fire only when it is present and non-empty. In the jobs
 * service, {@code TablesClient#getDataLayoutStrategies} reads exactly this property to build one
 * execution task per stored strategy — this analyzer mirrors that signal on the optimizer side.
 *
 * <p><b>Reachability of the signal on {@link TableDto}.</b> The optimizer's {@code
 * TableDto.tableProperties} is the same per-table property map the OFD analyzer already reads its
 * opt-in flag from, so structurally the strategy property is reachable here. This analyzer
 * therefore gates {@link #isEnabled(TableDto)} on <b>both</b> the opt-in flag <b>and</b> the
 * strategy property being present and non-empty, so no execution operation is ever upserted for a
 * table that has no pending strategy.
 *
 * <p><b>Fallback / defense-in-depth.</b> Whether the strategy blob is propagated all the way into
 * the optimizer's ingested {@code tableProperties} depends on the upstream stats-ingestion pipeline
 * (outside this milestone). If a deployment does not propagate it, this gate degrades to a pure
 * opt-in cadence trigger, and correctness is preserved at runtime by {@code
 * BatchedDataLayoutStrategyExecutionSparkApp}, which re-reads {@code write.data-layout.strategies}
 * from the live table and <b>no-ops</b> any table with no strategy. Presence-gating here is the
 * optimization (don't schedule empty work); the runtime no-op is the guarantee.
 *
 * <h2>When execution fires for a table</h2>
 *
 * <ol>
 *   <li><b>Opt-in.</b> {@code maintenance.optimizer.dataLayoutStrategyExecution.enabled=true}.
 *   <li><b>A generated strategy is present.</b> The {@code write.data-layout.strategies} property
 *       is set to a non-empty strategy list.
 *   <li><b>No active operation in flight</b> and <b>cadence elapsed</b> — same contract as the OFD
 *       analyzer, via {@link CadencePolicy}. After SUCCESS wait {@code
 *       dls-execution.success-retry-hours} (default 24h); after FAILED wait {@code
 *       dls-execution.failure-retry-hours} (default 1h).
 * </ol>
 */
@Component
public class CadenceBasedDataLayoutStrategyExecutionAnalyzer extends CadenceBasedAnalyzer {

  static final String DLS_EXECUTION_ENABLED_PROPERTY =
      "maintenance.optimizer.dataLayoutStrategyExecution.enabled";

  /**
   * Table property key under which the generation half stores its recommended strategies. Mirrors
   * {@code StrategiesDaoTableProps.DATA_LAYOUT_STRATEGIES_PROPERTY_KEY} in the datalayout lib; kept
   * as a local constant to avoid coupling the optimizer analyzer module to the spark-side lib.
   */
  static final String DATA_LAYOUT_STRATEGIES_PROPERTY_KEY = "write.data-layout.strategies";

  public CadenceBasedDataLayoutStrategyExecutionAnalyzer(
      @Value("${dls-execution.success-retry-hours:24}") long successRetryHours,
      @Value("${dls-execution.failure-retry-hours:1}") long failureRetryHours) {
    super(
        OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION,
        DLS_EXECUTION_ENABLED_PROPERTY,
        Duration.ofHours(successRetryHours),
        Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  CadenceBasedDataLayoutStrategyExecutionAnalyzer(CadencePolicy cadencePolicy) {
    super(
        OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION,
        DLS_EXECUTION_ENABLED_PROPERTY,
        cadencePolicy);
  }

  /**
   * Enabled iff the table is opted in <b>and</b> the generation half has left a pending strategy in
   * {@code write.data-layout.strategies}. Requiring the strategy here is what couples execution to
   * generation: with no generated strategy there is nothing to apply, so the table is skipped
   * entirely (no operation upserted).
   */
  @Override
  public boolean isEnabled(TableDto table) {
    return super.isEnabled(table) && hasPendingStrategy(table);
  }

  private boolean hasPendingStrategy(TableDto table) {
    String value = table.getTableProperties().get(DATA_LAYOUT_STRATEGIES_PROPERTY_KEY);
    if (value == null) {
      return false;
    }
    String trimmed = value.trim();
    // The generation half always writes a JSON array; an empty run serializes to "[]". Treat that,
    // and blank, as "no pending strategy".
    return !trimmed.isEmpty() && !"[]".equals(trimmed);
  }
}
