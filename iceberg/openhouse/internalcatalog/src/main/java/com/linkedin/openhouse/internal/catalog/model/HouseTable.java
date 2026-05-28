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

  /*
   * Optimistic-lock version — commented out for now. Adding @Version flipped Spring Data JPA's
   * save() to em.persist() (because isNew() defaults to "version == null") which throws
   * NonUniqueObjectException when the EntityManager already has a managed entity at the same PK
   * from upstream doRefresh(). All saves fail, no data lands. The right fix needs save() to go
   * through em.merge() so @Version CAS actually fires — that requires populating version from
   * findById before save (or overriding Persistable.isNew()). Reverting to expose the original
   * silent-snapshot-drop bug clearly until that fix lands.
   */
  // @Version private Long version;

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
}
