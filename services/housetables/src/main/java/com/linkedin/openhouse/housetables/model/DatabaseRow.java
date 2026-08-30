package com.linkedin.openhouse.housetables.model;

import java.util.Map;
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
 * Data Model for persisting a Database (the stored form of a namespace) in the House Table.
 *
 * <p>The primary key is the encoded namespace: for every database that exists today that is the
 * same bytes as {@code user_table_row.database_id}, which is what lets a namespace store sit
 * alongside the table store without a migration.
 *
 * <p>Deliberately carries no {@code entity_type}: a database is not an occupant of the {@code
 * (database_id, table_id)} key space and must not compete for it.
 */
@Entity
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseRow {

  @Id String databaseId;

  @Version Long version;

  @Convert(converter = PropertiesConverter.class)
  @Column(length = 8192)
  Map<String, String> properties;

  Long creationTime;

  Long lastModifiedTime;
}
