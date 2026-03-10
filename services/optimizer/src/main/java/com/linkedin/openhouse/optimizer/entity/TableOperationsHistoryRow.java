package com.linkedin.openhouse.optimizer.entity;

import com.linkedin.openhouse.optimizer.api.model.JobResult;
import com.linkedin.openhouse.optimizer.api.model.OperationHistoryStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.config.JobResultConverter;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only record of a completed Spark maintenance job.
 *
 * <p>Written by the SparkJob after each run. No unique constraint — multiple runs of the same
 * operation on the same table produce multiple rows. Auto-increment PK avoids coordination overhead
 * on high-volume writes.
 */
@Entity
@Table(
    name = "table_operations_history",
    indexes = {
      @Index(name = "idx_table_uuid_hist", columnList = "table_uuid"),
      @Index(name = "idx_op_type_hist", columnList = "operation_type"),
      @Index(name = "idx_submitted_at", columnList = "submitted_at"),
      @Index(name = "idx_status_hist", columnList = "status"),
      @Index(name = "idx_job_id", columnList = "job_id")
    })
@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class TableOperationsHistoryRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "table_uuid", nullable = false, length = 36)
  private String tableUuid;

  @Column(name = "database_name", nullable = false, length = 255)
  private String databaseName;

  @Column(name = "table_name", nullable = false, length = 255)
  private String tableName;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_type", nullable = false, length = 50)
  private OperationType operationType;

  /** When the Spark job was submitted / ran, as reported by the job itself. */
  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  /** {@code SUCCESS} or {@code FAILED}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OperationHistoryStatus status;

  /** Spark job ID; indexed for job → result lookups. */
  @Column(name = "job_id", length = 255)
  private String jobId;

  /** Job result: error details on failure, both fields null on success. */
  @Convert(converter = JobResultConverter.class)
  @Column(name = "result")
  private JobResult result;
}
