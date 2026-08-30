package com.linkedin.openhouse.internal.catalog.model;

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

  /**
   * The row version the caller read, carried end to end so that a namespace write is a
   * compare-and-set against the value it was based on. A {@code null} version means "there was no
   * row", which is what makes two concurrent creates a conflict rather than a silent overwrite.
   */
  @Version private Long version;

  /**
   * The column is sized for the JSON encoding of a property bag that has passed {@link
   * com.linkedin.openhouse.common.utils.NamespacePropertiesValidator}: 8 KiB of key and value bytes
   * plus headroom for the separators and quoting the encoding adds. This annotation is what the
   * in-memory stand-in used by tests is built from; the production width belongs to {@code
   * database_row.properties} in the House Tables {@code schema.sql}, which is wider still.
   */
  @Convert(converter = NamespacePropertiesConverter.class)
  @Column(length = 16384)
  private Map<String, String> properties;

  private Long creationTime;

  private Long lastModifiedTime;
}
