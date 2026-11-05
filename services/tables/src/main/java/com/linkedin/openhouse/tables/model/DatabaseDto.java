package com.linkedin.openhouse.tables.model;

import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Database model. {@code databaseId} is the logical name and {@code clusterId} the hosting cluster.
 * {@code databaseUUID} and {@code properties} are populated only when the DTO comes from the
 * persisted database entity (see {@link
 * com.linkedin.openhouse.tables.repository.DatabaseEntityRepository}); they are null for databases
 * derived from table listings.
 */
@Entity
@IdClass(DatabaseDtoPrimaryKey.class)
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseDto {
  @Id private String databaseId;

  private String clusterId;

  /** Server-generated canonical identity of the persisted database entity. */
  private String databaseUUID;

  /** Key-value properties of the persisted database entity. */
  @Column(length = 65535)
  @Convert(converter = DatabasePropertiesConverter.class)
  private Map<String, String> properties;
}
