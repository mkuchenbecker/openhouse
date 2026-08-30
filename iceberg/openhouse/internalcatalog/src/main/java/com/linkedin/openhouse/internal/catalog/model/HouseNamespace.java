package com.linkedin.openhouse.internal.catalog.model;

import java.util.Map;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Data model for a stored namespace in the HTS repository.
 *
 * <p>{@code namespaceId} is the encoded namespace: the levels dot-joined, which for a single-level
 * namespace is the database name byte for byte. It is the same string that appears in {@code
 * house_table.database_id}, which is what makes "does namespace n contain tables" a question over
 * one key space rather than a join across two encodings.
 */
@Entity
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class HouseNamespace {

  @Id private String namespaceId;

  @Convert(converter = NamespacePropertiesConverter.class)
  @Column(length = 8192)
  private Map<String, String> properties;

  private Long creationTime;

  private Long lastModifiedTime;
}
