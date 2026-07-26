package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import java.time.Duration;
import java.util.Optional;

/**
 * Reusable {@link OperationAnalyzer} for cadence-driven operations. Any maintenance operation whose
 * eligibility is purely time-based — opt-in via a per-table property, then re-evaluate on a
 * success/failure retry cadence — collapses to a subclass that supplies its {@link
 * OperationTypeDto} and configuration to this base's constructor.
 *
 * <p>The three strategy methods are implemented once here:
 *
 * <ul>
 *   <li>{@link #getOperationType()} returns the configured operation type.
 *   <li>{@link #isEnabled(TableDto)} returns {@code true} iff the table sets {@code
 *       enabledProperty} to {@code "true"}.
 *   <li>{@link #shouldSchedule} delegates verbatim to {@link CadencePolicy}.
 * </ul>
 *
 * <p>The abstraction is proven by {@link CadenceBasedOrphanFilesDeletionAnalyzer}, which becomes a
 * thin {@code @Component} configuring its {@code ofd.*} cadence.
 */
public abstract class CadenceBasedAnalyzer implements OperationAnalyzer {

  private final OperationTypeDto operationType;
  private final String enabledProperty;
  private final CadencePolicy cadencePolicy;

  /**
   * @param operationType the operation type this analyzer handles
   * @param enabledProperty the per-table property whose {@code "true"} value opts the table in
   * @param successRetry how long to wait after a successful run before re-evaluating
   * @param failureRetry how long to wait after a failed run before retrying
   */
  protected CadenceBasedAnalyzer(
      OperationTypeDto operationType,
      String enabledProperty,
      Duration successRetry,
      Duration failureRetry) {
    this(operationType, enabledProperty, new CadencePolicy(successRetry, failureRetry));
  }

  /**
   * @param operationType the operation type this analyzer handles
   * @param enabledProperty the per-table property whose {@code "true"} value opts the table in
   * @param cadencePolicy a pre-built policy (used by tests to inject deterministic intervals)
   */
  protected CadenceBasedAnalyzer(
      OperationTypeDto operationType, String enabledProperty, CadencePolicy cadencePolicy) {
    this.operationType = operationType;
    this.enabledProperty = enabledProperty;
    this.cadencePolicy = cadencePolicy;
  }

  @Override
  public OperationTypeDto getOperationType() {
    return operationType;
  }

  @Override
  public boolean isEnabled(TableDto table) {
    return "true".equals(table.getTableProperties().get(enabledProperty));
  }

  @Override
  public boolean shouldSchedule(
      TableDto table,
      Optional<TableOperationDto> currentOp,
      Optional<TableOperationsHistoryDto> latestHistory) {
    return cadencePolicy.shouldSchedule(currentOp, latestHistory);
  }
}
