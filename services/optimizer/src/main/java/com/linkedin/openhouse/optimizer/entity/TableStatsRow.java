package com.linkedin.openhouse.optimizer.entity;

import java.time.Instant;
import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity recording the latest known stats for a table.
 *
 * <p>PK is {@code table_uuid} — the stable Iceberg-assigned UUID. One row per table; the {@code
 * idx_db_table} unique index on {@code (database_name, table_name)} prevents duplicates. Stats
 * fields that do not appear in {@code WHERE} or {@code ORDER BY} clauses are folded into the {@code
 * stats} JSON column so the schema stays stable as the payload evolves.
 */
@Entity
@Table(
    name = "table_stats",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "idx_db_table",
          columnNames = {"database_name", "table_name"})
    },
    indexes = {@Index(name = "idx_last_updated", columnList = "last_updated_at")})
@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class TableStatsRow {

  @Id
  @Column(name = "table_uuid", nullable = false, length = 100)
  private String tableUuid;

  @Column(name = "database_name", nullable = false, length = 255)
  private String databaseName;

  @Column(name = "table_name", nullable = false, length = 255)
  private String tableName;

  /** Auto-set to {@code NOW()} on every save. */
  @UpdateTimestamp
  @Column(name = "last_updated_at")
  private Instant lastUpdatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  /**
   * JSON payload carrying non-indexed stats fields: {@code cluster_id}, {@code table_version},
   * {@code num_snapshots}, {@code table_location}, {@code operation_type}, {@code num_files_added},
   * {@code num_files_deleted}, {@code table_size_bytes}.
   */
  @Type(type = "json")
  @Column(name = "stats", columnDefinition = "json")
  private Map<String, Object> stats;
}
