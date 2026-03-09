package com.linkedin.openhouse.optimizer.entity;

import com.linkedin.openhouse.optimizer.api.model.TableStats;
import com.linkedin.openhouse.optimizer.config.TableStatsConverter;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Convert;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity recording the latest known stats for a table.
 *
 * <p>PK is {@code table_uuid} — the stable Iceberg-assigned UUID. One row per table; the {@code
 * idx_db_table} unique index on {@code (database_name, table_name)} prevents duplicates.
 *
 * <p>The {@code stats} column holds a mix of delta and snapshot fields; see {@link TableStats} for
 * the accumulation contract.
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
   * Stats payload. Delta fields ({@code numFilesAdded}, {@code numFilesDeleted}) are accumulated
   * across commit events; all other fields are overwritten on each upsert.
   */
  @Convert(converter = TableStatsConverter.class)
  @Column(name = "stats")
  private TableStats stats;
}
