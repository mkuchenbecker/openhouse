package com.linkedin.openhouse.internal.catalog.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Data Model for persisting Table Object in the HTS-Repository. */
@Entity
@IdClass(HouseTablePrimaryKey.class)
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class HouseTable {

  @Id private String tableId;

  @Id private String databaseId;

  private String clusterId;

  private String tableUri;

  private String tableUUID;

  private String tableLocation;

  private String tableVersion;

  private String tableCreator;

  private long lastModifiedTime;

  private long creationTime;

  private long deletedAtMs;

  private long purgeAfterMs;

  /**
   * This column indicates the storage type used by this table. See {@link
   * com.linkedin.openhouse.cluster.storage.StorageType}. A storage type indicates the {@link
   * com.linkedin.openhouse.cluster.storage.StorageClient} implementation that is used to interact
   * with this table.
   */
  private String storageType;

  /**
   * Which kind of catalog object holds this {@code (databaseId, tableId)} key: {@code "VIEW"} for a
   * view, and {@code null} for a table.
   *
   * <p>Null rather than {@code "TABLE"} for tables, deliberately. House Tables treats a null
   * discriminator as a table so that rows written before the column existed keep working, and the
   * table write path here has never set the field; leaving it null keeps every table write
   * byte-identical to what it produced before views existed, so adopting this column cannot change
   * how a table round-trips. Only the view path sets a value.
   *
   * <p>Kept as a {@code String} rather than the {@code EntityType} enum because this entity is
   * mapped straight onto the generated House Tables client model, whose field is also a String —
   * the enum lives on the House Tables side of the wire, and pulling it across would make this
   * module depend on the service it talks to.
   */
  private String entityType;
}
