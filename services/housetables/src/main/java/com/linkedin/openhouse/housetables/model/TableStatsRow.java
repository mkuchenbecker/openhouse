package com.linkedin.openhouse.housetables.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
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
 * <p>PK is {@code (databaseId, tableId)}. One row per table. Delta fields ({@code numFilesAdded},
 * {@code numFilesDeleted}) are accumulated on each upsert; all other fields are overwritten.
 */
@Entity
@IdClass(TableStatsRowPrimaryKey.class)
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class TableStatsRow {

  @Id String databaseId;

  @Id String tableId;

  @Version Long version;

  /** Iceberg-assigned stable UUID for the table. */
  String tableUuid;

  // --- Snapshot fields (overwritten on every upsert) ---

  String clusterId;

  String tableVersion;

  String tableLocation;

  Integer numSnapshots;

  Long tableSizeBytes;

  // --- Delta fields (accumulated across commit events) ---

  /** Running total of data files added across all recorded commit events. */
  Long numFilesAdded;

  /** Running total of data files deleted across all recorded commit events. */
  Long numFilesDeleted;
}
