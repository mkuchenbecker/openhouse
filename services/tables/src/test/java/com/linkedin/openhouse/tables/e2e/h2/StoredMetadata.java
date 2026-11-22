package com.linkedin.openhouse.tables.e2e.h2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;

/**
 * Reads every metadata file a table's writes left, oldest first, and normalizes only what is time-
 * or random-based, so two runs of the same writes compare equal. Shared by the stored-metadata
 * golden test (native writes against recorded files) and the IRC write tests (an IRC write against
 * the equivalent native write).
 *
 * <p>Normalized: {@code last-updated-ms} and every {@code timestamp-ms} (to 0); {@code
 * openhouse.lastModifiedTime} and {@code openhouse.creationTime} (to "ts"); every UUID in any
 * string (to "uuid"), which covers {@code table-uuid}, metadata file names and manifest-list names;
 * snapshot ids renamed to their order of first appearance ("S1", "S2", ... in strings, 1, 2, ... in
 * numbers); inside the {@code policies} property, the replication {@code cronSchedule} (drawn at
 * random by the server) and the lock's {@code creationTime}. Also dropped from snapshot summaries:
 * {@link #CLIENT_LINE_SUMMARY_KEYS}, which the Iceberg that builds a test's snapshots on the client
 * side writes on the 1.12 line and not on 1.5.2; they say which Iceberg wrote the snapshot, not
 * what the service stored. Nothing else is touched.
 */
final class StoredMetadata {
  static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** The server draws the replication schedule at random; a lock records when it was taken. */
  private static final Pattern POLICY_CRON = Pattern.compile("\"cronSchedule\": \"[^\"]*\"");

  private static final Pattern POLICY_LOCK_TIME = Pattern.compile("\"creationTime\": [0-9]+");

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private StoredMetadata() {}

  /** The normalized stored versions of {@code db.table}, as indented JSON. */
  static String normalized(Catalog catalog, String db, String table) throws Exception {
    return MAPPER.writeValueAsString(normalize(storedVersions(catalog, db, table)));
  }

  /** Every metadata file of {@code db.table}, oldest first, parsed as written. */
  static ArrayNode storedVersions(Catalog catalog, String db, String table) throws Exception {
    Table loaded = catalog.loadTable(TableIdentifier.of(db, table));
    TableMetadata current = ((HasTableOperations) loaded).operations().current();
    FileIO io = loaded.io();
    List<String> files =
        current.previousFiles().stream()
            .map(TableMetadata.MetadataLogEntry::file)
            .collect(Collectors.toList());
    files.add(current.metadataFileLocation());
    ArrayNode versions = MAPPER.createArrayNode();
    for (String file : files) {
      try (InputStream in = io.newInputFile(file).newStream()) {
        ObjectNode version = MAPPER.createObjectNode();
        version.put("file", file.substring(file.lastIndexOf('/') + 1));
        version.set("metadata", MAPPER.readTree(in));
        versions.add(version);
      }
    }
    return versions;
  }

  static JsonNode normalize(ArrayNode versions) {
    Map<Long, Integer> snapshotIds = new LinkedHashMap<>();
    collectSnapshotIds(versions, snapshotIds);
    return rewrite(versions, null, snapshotIds);
  }

  private static void collectSnapshotIds(JsonNode node, Map<Long, Integer> ids) {
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (isSnapshotIdKey(field.getKey()) && field.getValue().isNumber()) {
          long id = field.getValue().asLong();
          if (id > 0) {
            ids.putIfAbsent(id, ids.size() + 1);
          }
        }
        collectSnapshotIds(field.getValue(), ids);
      }
    } else if (node.isArray()) {
      node.forEach(child -> collectSnapshotIds(child, ids));
    }
  }

  private static boolean isSnapshotIdKey(String key) {
    return key.equals("snapshot-id")
        || key.equals("parent-snapshot-id")
        || key.equals("current-snapshot-id");
  }

  /**
   * Snapshot summary keys that Iceberg 1.12's snapshot producer adds and 1.5.2's does not: the
   * manifest counts and the writer's version string, which also names the fork's pinned commit.
   * Absent on the default build, so dropping them changes nothing there.
   */
  private static final List<String> CLIENT_LINE_SUMMARY_KEYS =
      Arrays.asList("manifests-created", "manifests-kept", "manifests-replaced", "iceberg-version");

  private static JsonNode rewrite(JsonNode node, String key, Map<Long, Integer> ids) {
    if (node.isObject()) {
      ObjectNode out = MAPPER.createObjectNode();
      boolean summary = "summary".equals(key);
      node.fields()
          .forEachRemaining(
              field -> {
                if (!(summary && CLIENT_LINE_SUMMARY_KEYS.contains(field.getKey()))) {
                  out.set(field.getKey(), rewrite(field.getValue(), field.getKey(), ids));
                }
              });
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = MAPPER.createArrayNode();
      node.forEach(child -> out.add(rewrite(child, key, ids)));
      return out;
    }
    if ("last-updated-ms".equals(key) || "timestamp-ms".equals(key)) {
      return LongNode.valueOf(0);
    }
    if ("openhouse.lastModifiedTime".equals(key) || "openhouse.creationTime".equals(key)) {
      return TextNode.valueOf("ts");
    }
    if (key != null && isSnapshotIdKey(key) && node.isNumber() && ids.containsKey(node.asLong())) {
      return LongNode.valueOf(ids.get(node.asLong()));
    }
    if (node.isTextual()) {
      String text = UUID_PATTERN.matcher(node.asText()).replaceAll("uuid");
      if ("policies".equals(key)) {
        text = POLICY_CRON.matcher(text).replaceAll("\"cronSchedule\": \"cron\"");
        text = POLICY_LOCK_TIME.matcher(text).replaceAll("\"creationTime\": 0");
      }
      for (Map.Entry<Long, Integer> id : ids.entrySet()) {
        text = text.replace(String.valueOf(id.getKey()), "S" + id.getValue());
      }
      return TextNode.valueOf(text);
    }
    return node;
  }
}
