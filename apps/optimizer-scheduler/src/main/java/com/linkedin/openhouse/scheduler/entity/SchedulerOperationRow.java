package com.linkedin.openhouse.scheduler.entity;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Read-only mirror of the {@code table_operations} row in the optimizer DB.
 *
 * <p>The scheduler reads PENDING rows, bin-packs them, submits a Spark job per bin, then claims
 * each row (PENDING → SCHEDULED) optimistically using the {@code version} column.
 */
@Entity
@Table(name = "table_operations")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerOperationRow {

  @Id
  @Column(name = "id", nullable = false, length = 36)
  private String id;

  @Column(name = "table_uuid", nullable = false, length = 36)
  private String tableUuid;

  @Column(name = "database_name", nullable = false, length = 255)
  private String databaseName;

  @Column(name = "table_name", nullable = false, length = 255)
  private String tableName;

  @Column(name = "operation_type", nullable = false, length = 50)
  private String operationType;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Column(name = "scheduled_at")
  private Instant scheduledAt;

  /** Plain optimistic-lock column — NOT annotated @Version to avoid JPA managing it. */
  @Column(name = "version")
  private Long version;
}
