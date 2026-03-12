package com.linkedin.openhouse.analyzer.entity;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** JPA entity mapping to the {@code table_operations} table in the optimizer DB. */
@Entity
@Table(name = "table_operations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableOperationEntity {

  @Id private String id;

  @Column(name = "table_uuid")
  private String tableUuid;

  @Column(name = "database_name")
  private String databaseName;

  @Column(name = "table_name")
  private String tableName;

  @Column(name = "operation_type")
  private String operationType;

  private String status;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "scheduled_at")
  private Instant scheduledAt;

  /** Plain version column — not managed by JPA optimistic locking. */
  private Long version;
}
