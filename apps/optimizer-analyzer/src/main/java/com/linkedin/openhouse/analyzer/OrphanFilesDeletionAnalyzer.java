package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Analyzer for the {@code ORPHAN_FILES_DELETION} operation type. */
@Component
public class OrphanFilesDeletionAnalyzer implements OperationAnalyzer {

  static final String OPERATION_TYPE = "ORPHAN_FILES_DELETION";
  static final String OFD_ENABLED_PROPERTY = "maintenance.optimizer.ofd.enabled";

  private final CadencePolicy cadencePolicy;

  public OrphanFilesDeletionAnalyzer(
      @Value("${ofd.success-retry-hours:24}") long successRetryHours,
      @Value("${ofd.failure-retry-hours:1}") long failureRetryHours) {
    this.cadencePolicy =
        new CadencePolicy(Duration.ofHours(successRetryHours), Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy}. */
  OrphanFilesDeletionAnalyzer(CadencePolicy cadencePolicy) {
    this.cadencePolicy = cadencePolicy;
  }

  @Override
  public String getOperationType() {
    return OPERATION_TYPE;
  }

  @Override
  public boolean isEnabled(TableSummary table) {
    return "true".equals(table.getTableProperties().get(OFD_ENABLED_PROPERTY));
  }

  @Override
  public boolean shouldSchedule(TableSummary table, Optional<TableOperationRecord> currentOp) {
    return cadencePolicy.shouldSchedule(currentOp);
  }
}
