package com.linkedin.openhouse.housetables.model;

import java.util.Map;
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
 * Data Model for persisting a Database entity in the HouseTable.
 *
 * <p>The database entity is independent of {@link UserTableRow}: it is never created or loaded as a
 * side effect of a table operation, and deleting it leaves table rows untouched. The {@code
 * databaseUuid} is the canonical identity; {@code databaseId} is the logical name and carries a
 * unique constraint in the store.
 */
@Entity
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseRow {

  /** Server-generated UUID, the canonical identity of the database. */
  @Id String databaseUuid;

  /** Logical database name. Looked up ignoring case; unique in the store. */
  String databaseId;

  /** Optimistic lock; concurrent writers to the same row receive a conflict. */
  @Version Long version;

  Long creationTime;

  /** Key-value properties persisted as a JSON text column. */
  @Convert(converter = PropertiesMapConverter.class)
  Map<String, String> properties;
}
