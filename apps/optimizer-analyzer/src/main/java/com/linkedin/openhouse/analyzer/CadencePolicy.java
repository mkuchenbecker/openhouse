package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * Encapsulates the time-based scheduling logic shared across operation types. An analyzer delegates
 * to {@link CadencePolicy} to decide whether to re-issue a recommendation for a table that already
 * has an active operation record.
 */
@RequiredArgsConstructor
public class CadencePolicy {

  private final Duration successRetryInterval;
  private final Duration failureRetryInterval;

  /**
   * Returns {@code true} if a new or refreshed operation record should be upserted.
   *
   * @param currentOp the existing active operation record, or empty if none exists
   */
  public boolean shouldSchedule(Optional<TableOperationRecord> currentOp) {
    if (currentOp.isEmpty()) {
      return true;
    }
    TableOperationRecord op = currentOp.get();
    switch (op.getStatus()) {
      case "PENDING":
        return true;
      case "SCHEDULED":
        return false;
      case "SUCCESS":
        return pastInterval(op.getScheduledAt(), successRetryInterval);
      case "FAILED":
        return pastInterval(op.getScheduledAt(), failureRetryInterval);
      default:
        return true;
    }
  }

  private boolean pastInterval(Instant scheduledAt, Duration interval) {
    return scheduledAt == null
        || Duration.between(scheduledAt, Instant.now()).compareTo(interval) > 0;
  }
}
