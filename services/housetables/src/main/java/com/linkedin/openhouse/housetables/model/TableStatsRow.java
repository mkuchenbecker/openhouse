package com.linkedin.openhouse.housetables.model;

import com.linkedin.openhouse.housetables.config.TableStatsConverter;
import com.linkedin.openhouse.housetables.dto.model.TableStats;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity recording the latest known stats for a table.
 *
 * <p>Keyed by {@code table_uuid} — Iceberg's stable UUID. Stats survive table renames (same UUID,
 * new name) and are discarded when a table is re-created (new UUID assigned by Iceberg).
 *
 * <p>{@code snapshot} fields are overwritten on each upsert; {@code delta} fields are accumulated.
 */
@Entity
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class TableStatsRow {

  /** Iceberg-assigned stable UUID — primary key. */
  @Id String tableUuid;

  /** Database namespace — stored for name-based lookup; not the primary key. */
  @Column(nullable = false)
  String databaseId;

  /** Table name — mutable; stored for lookup but not the primary key. */
  @Column(name = "table_name", nullable = false)
  String tableName;

  @Version Long version;

  /**
   * Combined snapshot and delta stats, stored as a single JSON blob.
   *
   * <p>Snapshot fields are overwritten on each upsert; delta fields accumulate across commit
   * events.
   */
  @Convert(converter = TableStatsConverter.class)
  @Column(columnDefinition = "text")
  TableStats stats;
}
