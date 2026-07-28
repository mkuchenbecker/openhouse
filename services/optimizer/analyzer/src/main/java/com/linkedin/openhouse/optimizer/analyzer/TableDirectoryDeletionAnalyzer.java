package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Database-scoped analyzer for {@code TABLE_DIRECTORY_DELETION}. Emits one PENDING database-scoped
 * operation per opted-in database on the configured cadence; the launched batched Spark job purges
 * the storage directories of dropped/purged tables within that database.
 *
 * <p>Opt-in and cadence are service-level (per operation), configured via {@code
 * application.properties}:
 *
 * <ul>
 *   <li>{@code optimizer.analyzer.table-directory-deletion.enabled} (default {@code false})
 *   <li>{@code optimizer.analyzer.table-directory-deletion.success-retry-hours} (default 24)
 *   <li>{@code optimizer.analyzer.table-directory-deletion.failure-retry-hours} (default 1)
 * </ul>
 */
@Component
public class TableDirectoryDeletionAnalyzer implements DirectoryOperationAnalyzer {

  private final boolean enabled;
  private final CadencePolicy cadencePolicy;

  public TableDirectoryDeletionAnalyzer(
      @Value("${optimizer.analyzer.table-directory-deletion.enabled:false}") boolean enabled,
      @Value("${optimizer.analyzer.table-directory-deletion.success-retry-hours:24}")
          long successRetryHours,
      @Value("${optimizer.analyzer.table-directory-deletion.failure-retry-hours:1}")
          long failureRetryHours) {
    this.enabled = enabled;
    this.cadencePolicy =
        new CadencePolicy(Duration.ofHours(successRetryHours), Duration.ofHours(failureRetryHours));
  }

  /** Package-private for tests that supply a pre-built {@link CadencePolicy} and opt-in flag. */
  TableDirectoryDeletionAnalyzer(boolean enabled, CadencePolicy cadencePolicy) {
    this.enabled = enabled;
    this.cadencePolicy = cadencePolicy;
  }

  @Override
  public OperationTypeDto getOperationType() {
    return OperationTypeDto.TABLE_DIRECTORY_DELETION;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public boolean shouldSchedule(
      String databaseName,
      Optional<TableOperationDto> currentOp,
      Optional<TableOperationsHistoryDto> latestHistory) {
    return cadencePolicy.shouldSchedule(currentOp, latestHistory);
  }
}
