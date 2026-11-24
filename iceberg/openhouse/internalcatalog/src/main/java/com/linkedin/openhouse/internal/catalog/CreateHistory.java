package com.linkedin.openhouse.internal.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.util.JsonUtil;

/**
 * The schema, partition spec and sort order history a create keeps (work item V14).
 *
 * <p>A create builds a table's first metadata from one schema, one spec and one sort order, which
 * {@code TableMetadata.newTableMetadata} numbers afresh: schema 0, spec 0, sort order 1 (0 when
 * unsorted), column ids from 1 and partition field ids from 1000. A create transaction through the
 * Iceberg REST routes can change the schema, the spec and the order before it commits, and its
 * snapshots name the schemas and specs it had then. Such a create hands the client's history to the
 * commit as {@link #KEY}, a transient property, and the commit keeps it in place of the renumbered
 * parts, once it has checked that the client's final schema, spec and order are, renumbered, the
 * ones the create built and validated. Nothing else of the metadata (location, properties, UUID,
 * format version, row lineage) comes from the client.
 */
public final class CreateHistory {
  /** The transient property: the history parts of the client's metadata, as metadata JSON. */
  public static final String KEY = "openhouse.internal.createHistory";

  /** The metadata JSON fields that make up the history; everything else is the create's own. */
  static final List<String> FIELDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "last-column-id",
              "schemas",
              "current-schema-id",
              "partition-specs",
              "default-spec-id",
              "last-partition-id",
              "sort-orders",
              "default-sort-order-id"));

  private CreateHistory() {}

  /**
   * The history parts of {@code candidate}, the metadata a client built for a new table, or {@code
   * null} when a create of its final schema, spec and order stores exactly those parts anyway (one
   * of each, numbered as a create numbers them): then there is nothing to keep.
   */
  public static String of(TableMetadata candidate) {
    ObjectNode parts = parts(candidate);
    return parts.equals(parts(created(candidate))) ? null : parts.toString();
  }

  /**
   * {@code metadata}, a new table's first metadata as the create built it, with the history the
   * create handed over as {@link #KEY} in place of its own schemas, specs and sort orders; {@code
   * metadata} without that property when there is none, or when {@code base} is a table that exists
   * (the property is honored on a create only).
   *
   * @throws IllegalStateException when the history's final schema, spec or order, renumbered as a
   *     create numbers them, is not what the create built: then the create validated something
   *     other than what would be stored
   */
  static TableMetadata apply(TableMetadata base, TableMetadata metadata) {
    String history = metadata.properties().get(KEY);
    if (history == null) {
      return metadata;
    }
    Map<String, String> properties = new HashMap<>(metadata.properties());
    properties.remove(KEY);
    TableMetadata built = metadata.replaceProperties(properties);
    if (base != null) {
      return built;
    }
    ObjectNode json = tree(TableMetadataParser.toJson(built));
    JsonNode kept = tree(history);
    for (String field : FIELDS) {
      json.set(field, kept.get(field));
    }
    TableMetadata withHistory = TableMetadataParser.fromJson(built.metadataFileLocation(), json);
    if (!parts(created(withHistory)).equals(parts(built))) {
      throw new IllegalStateException(
          String.format(
              "The create of %s built a schema, partition spec or sort order other than the "
                  + "final ones of the history it was handed",
              built.location()));
    }
    return withHistory;
  }

  /** What a create of {@code metadata}'s current schema, spec and order would build. */
  private static TableMetadata created(TableMetadata metadata) {
    return TableMetadata.newTableMetadata(
        metadata.schema(),
        metadata.spec(),
        metadata.sortOrder(),
        metadata.location() != null ? metadata.location() : "created",
        Collections.singletonMap(
            TableProperties.FORMAT_VERSION, Integer.toString(metadata.formatVersion())));
  }

  private static ObjectNode parts(TableMetadata metadata) {
    ObjectNode json = tree(TableMetadataParser.toJson(metadata));
    ObjectNode parts = JsonUtil.mapper().createObjectNode();
    for (String field : FIELDS) {
      parts.set(field, json.get(field));
    }
    return parts;
  }

  private static ObjectNode tree(String json) {
    try {
      return (ObjectNode) JsonUtil.mapper().readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
