package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.bodyFromGet;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Fixtures shared by the format v3 tests. Schemas with column defaults or format v3 types are
 * written as schema JSON, the way a client sends them, so the tests compile on both Iceberg lines.
 */
final class FormatV3 {
  static final String FORMAT_VERSION = "format-version";

  /** The property that turns the cluster switch on for a test's Spring context. */
  static final String SWITCH_ON = "cluster.iceberg.format-v3.enabled=true";

  /** The property that allows variant columns on v3 tables, on in a test's Spring context. */
  static final String VARIANT_ON = "cluster.iceberg.format-v3.variant.enabled=true";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FormatV3() {}

  /** The native test table without policies, partitioning or clustering. */
  static CreateUpdateTableRequestBody plain(String db) {
    return newTable(db, "t")
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  static CreateUpdateTableRequestBody with(
      CreateUpdateTableRequestBody body, String key, String value) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(key, value);
    return body.toBuilder().tableProperties(properties).build();
  }

  /** {@code body} asking for format version {@code version}. */
  static CreateUpdateTableRequestBody asking(CreateUpdateTableRequestBody body, String version) {
    return with(body, FORMAT_VERSION, version);
  }

  /** The get-modify-put body of a stored table, with one property set. */
  static CreateUpdateTableRequestBody withProp(String getJson, String key, String value) {
    return with(bodyFromGet(getJson), key, value);
  }

  static TableMetadata metadata(Catalog catalog, String db, String table) {
    return ((BaseTable) catalog.loadTable(TableIdentifier.of(db, table))).operations().current();
  }

  /**
   * {@code schemaJson} with an optional column appended, of {@code type} (Iceberg's type string),
   * carrying the given defaults ({@code null} for none), each written as its JSON value ({@code 1},
   * {@code "US"}).
   */
  static String addColumn(
      String schemaJson, String name, String type, String initialDefault, String writeDefault)
      throws Exception {
    ObjectNode schema = (ObjectNode) MAPPER.readTree(schemaJson);
    ArrayNode fields = (ArrayNode) schema.get("fields");
    int nextId = 0;
    for (JsonNode field : fields) {
      nextId = Math.max(nextId, field.get("id").asInt());
    }
    ObjectNode added = fields.addObject();
    added.put("id", nextId + 1);
    added.put("name", name);
    added.put("required", false);
    added.put("type", type);
    setDefaults(added, initialDefault, writeDefault);
    return MAPPER.writeValueAsString(schema);
  }

  /** {@code schemaJson} with the defaults of column {@code name} set; {@code null} removes one. */
  static String withDefaults(
      String schemaJson, String name, String initialDefault, String writeDefault) throws Exception {
    ObjectNode schema = (ObjectNode) MAPPER.readTree(schemaJson);
    for (JsonNode field : schema.get("fields")) {
      if (name.equals(field.get("name").asText())) {
        setDefaults((ObjectNode) field, initialDefault, writeDefault);
        return MAPPER.writeValueAsString(schema);
      }
    }
    throw new IllegalArgumentException("no column " + name + " in " + schemaJson);
  }

  /** {@code schemaJson} with column {@code name} of {@code type} (Iceberg's type string). */
  static String withType(String schemaJson, String name, String type) throws Exception {
    ObjectNode schema = (ObjectNode) MAPPER.readTree(schemaJson);
    for (JsonNode field : schema.get("fields")) {
      if (name.equals(field.get("name").asText())) {
        ((ObjectNode) field).put("type", type);
        return MAPPER.writeValueAsString(schema);
      }
    }
    throw new IllegalArgumentException("no column " + name + " in " + schemaJson);
  }

  private static void setDefaults(ObjectNode field, String initialDefault, String writeDefault) {
    field.remove("initial-default");
    field.remove("write-default");
    try {
      if (initialDefault != null) {
        field.set("initial-default", MAPPER.readTree(initialDefault));
      }
      if (writeDefault != null) {
        field.set("write-default", MAPPER.readTree(writeDefault));
      }
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * {@code text} without what differs from one table to the next: UUIDs, snapshot ids and
   * timestamps (any number of 13 digits or more), and the replication schedule the server draws.
   */
  static String withoutUuids(String text) {
    return text.replaceAll(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "UUID")
        .replaceAll("[0-9]{13,}", "N")
        .replaceAll("cronSchedule[^,}]*", "cronSchedule");
  }

  /** A numeric field of a snapshot's JSON, such as {@code first-row-id}; -1 when absent. */
  static long snapshotField(String snapshotJson, String field) throws Exception {
    JsonNode value = MAPPER.readTree(snapshotJson).get(field);
    return value == null ? -1L : value.asLong();
  }

  /** The field object of column {@code name} in {@code schemaJson}. */
  static JsonNode column(String schemaJson, String name) throws Exception {
    for (JsonNode field : MAPPER.readTree(schemaJson).get("fields")) {
      if (name.equals(field.get("name").asText())) {
        return field;
      }
    }
    return null;
  }
}
