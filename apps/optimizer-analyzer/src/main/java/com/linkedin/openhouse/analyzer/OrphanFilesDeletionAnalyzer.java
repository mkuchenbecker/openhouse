package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.model.TableOperationView;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Analyzer for the {@code ORPHAN_FILES_DELETION} operation type. */
@Component
public class OrphanFilesDeletionAnalyzer implements OperationAnalyzer {

  static final String OPERATION_TYPE = "ORPHAN_FILES_DELETION";
  static final String OFD_ENABLED_PROPERTY = "maintenance.optimizer.ofd.enabled";
  static final Duration SUCCESS_RETRY_INTERVAL = Duration.ofHours(24);
  static final Duration FAILURE_RETRY_INTERVAL = Duration.ofHours(1);

  @Override
  public String getOperationType() {
    return OPERATION_TYPE;
  }

  @Override
  public boolean isEnabled(GetTableResponseBody table) {
    Map<String, String> props = table.getTableProperties();
    return props != null && "true".equals(props.get(OFD_ENABLED_PROPERTY));
  }

  @Override
  public boolean shouldSchedule(
      GetTableResponseBody table, Optional<TableOperationView> currentOp) {
    if (currentOp.isEmpty()) {
      return true;
    }
    TableOperationView op = currentOp.get();
    switch (op.getStatus()) {
      case "PENDING":
        return true;
      case "SCHEDULED":
        return false;
      case "SUCCESS":
        return op.getScheduledAt() == null
            || Duration.between(op.getScheduledAt(), Instant.now())
                    .compareTo(SUCCESS_RETRY_INTERVAL)
                > 0;
      case "FAILED":
        return op.getScheduledAt() == null
            || Duration.between(op.getScheduledAt(), Instant.now())
                    .compareTo(FAILURE_RETRY_INTERVAL)
                > 0;
      default:
        return true;
    }
  }
}
