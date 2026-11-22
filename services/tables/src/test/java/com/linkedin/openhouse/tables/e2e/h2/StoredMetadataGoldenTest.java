package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.SORT_ORDER;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pins what the native API stores for a representative set of writes: every metadata file the
 * writes leave, in order, as written, after normalizing only what is time- or random-based. The
 * expected files under {@code stored-metadata/} were produced by this test on the commit path as it
 * was before work item V1 restructured it, so a change in stored content or in the number of
 * metadata versions a write produces fails here.
 *
 * <p>Normalized: {@code last-updated-ms} and every {@code timestamp-ms} (to 0); {@code
 * openhouse.lastModifiedTime} and {@code openhouse.creationTime} (to "ts"); every UUID in any
 * string (to "uuid"), which covers {@code table-uuid}, metadata file names and manifest-list names;
 * snapshot ids, which the test client draws at random, renamed to their order of first appearance
 * in the scenario ("S1", "S2", ... in strings, 1, 2, ... in numbers); inside the {@code policies}
 * property, the replication {@code cronSchedule} (drawn at random by the server) and the lock's
 * {@code creationTime}. Nothing else is touched.
 *
 * <p>To rewrite the expected files, run with the environment variable {@code
 * STORED_METADATA_GOLDEN_DIR} set to {@code services/tables/src/test/resources/stored-metadata}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class StoredMetadataGoldenTest {
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  /** The server draws the replication schedule at random; a lock records when it was taken. */
  private static final Pattern POLICY_CRON = Pattern.compile("\"cronSchedule\": \"[^\"]*\"");

  private static final Pattern POLICY_LOCK_TIME = Pattern.compile("\"creationTime\": [0-9]+");

  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
    }
    created.clear();
  }

  private String make(String db) throws Exception {
    return make(newTable(db, "t"));
  }

  private String make(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private IcebergSnapshotsRequestBody append(String db, String file) throws Exception {
    MvcResult current = get(mvc, db, "t").andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    return preparePutSnapshotsWithAppendRequest(
        current,
        body,
        body.getTableLocation(),
        catalog,
        Collections.singletonList(
            createDummyDataFile("/tmp/" + db + "_" + file + ".orc", getPartitionSpec(body))));
  }

  private static CreateUpdateTableRequestBody withProp(String getJson, String key, String value) {
    CreateUpdateTableRequestBody b = bodyFromGet(getJson);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.put(key, value);
    return b.toBuilder().tableProperties(props).build();
  }

  @Test
  void create() throws Exception {
    make("gold_create");
    expectStored("gold_create");
  }

  @Test
  void propertyChange() throws Exception {
    String v0 = make("gold_prop");
    put(mvc, withProp(v0, "user.key", "v1")).andExpect(status().isOk());
    String v1 = json(get(mvc, "gold_prop", "t"));
    CreateUpdateTableRequestBody b = bodyFromGet(v1);
    Map<String, String> props = new HashMap<>(b.getTableProperties());
    props.remove("user.key");
    props.put("user.other", "v2");
    put(mvc, b.toBuilder().tableProperties(props).build()).andExpect(status().isOk());
    expectStored("gold_prop");
  }

  @Test
  void schemaChange() throws Exception {
    String v0 = make("gold_schema");
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(
            mvc,
            b.toBuilder().schema(addColumn(b.getSchema(), "added", Types.StringType.get())).build())
        .andExpect(status().isOk());
    expectStored("gold_schema");
  }

  @Test
  void policyChange() throws Exception {
    String v0 = make("gold_policy");
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(
            mvc,
            b.toBuilder()
                .policies(b.getPolicies().toBuilder().sharingEnabled(true).build())
                .build())
        .andExpect(status().isOk());
    expectStored("gold_policy");
  }

  @Test
  void sortOrderChange() throws Exception {
    String v0 = make("gold_sort");
    put(mvc, bodyFromGet(v0).toBuilder().sortOrder(SORT_ORDER).build()).andExpect(status().isOk());
    expectStored("gold_sort");
  }

  @Test
  void noOpPut() throws Exception {
    String v0 = make("gold_noop");
    put(mvc, bodyFromGet(v0)).andExpect(status().isOk());
    expectStored("gold_noop");
  }

  @Test
  void appendThenAppend() throws Exception {
    make("gold_append");
    putSnapshots(mvc, "gold_append", "t", append("gold_append", "a")).andExpect(status().isOk());
    putSnapshots(mvc, "gold_append", "t", append("gold_append", "b")).andExpect(status().isOk());
    expectStored("gold_append");
  }

  @Test
  void appendThenDelete() throws Exception {
    make("gold_delete");
    putSnapshots(mvc, "gold_delete", "t", append("gold_delete", "a")).andExpect(status().isOk());
    MvcResult current = get(mvc, "gold_delete", "t").andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    Table table = catalog.loadTable(TableIdentifier.of("gold_delete", "t"));
    DataFile written = table.currentSnapshot().addedDataFiles(table.io()).iterator().next();
    Snapshot deleted = table.newDelete().deleteFile(written).apply();
    List<String> snapshots = new ArrayList<>();
    table.snapshots().forEach(s -> snapshots.add(SnapshotParser.toJson(s)));
    snapshots.add(SnapshotParser.toJson(deleted));
    IcebergSnapshotsRequestBody request =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(body.getTableLocation())
            .jsonSnapshots(snapshots)
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(deleted)))
            .createUpdateTableRequestBody(
                bodyFromGet(json(get(mvc, "gold_delete", "t"))).toBuilder().build())
            .build();
    putSnapshots(mvc, "gold_delete", "t", request).andExpect(status().isOk());
    expectStored("gold_delete");
  }

  @Test
  void refsOnlyBranch() throws Exception {
    make("gold_refs");
    IcebergSnapshotsRequestBody first = append("gold_refs", "a");
    putSnapshots(mvc, "gold_refs", "t", first).andExpect(status().isOk());
    String v1 = json(get(mvc, "gold_refs", "t"));
    Table table = catalog.loadTable(TableIdentifier.of("gold_refs", "t"));
    Map<String, String> refs =
        new HashMap<>(
            obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(table.currentSnapshot())));
    refs.put("branch1", refs.get("main"));
    IcebergSnapshotsRequestBody request =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(str(v1, "$.tableLocation"))
            .jsonSnapshots(
                table.currentSnapshot() == null
                    ? null
                    : Collections.singletonList(SnapshotParser.toJson(table.currentSnapshot())))
            .snapshotRefs(refs)
            .createUpdateTableRequestBody(bodyFromGet(v1))
            .build();
    putSnapshots(mvc, "gold_refs", "t", request).andExpect(status().isOk());
    expectStored("gold_refs");
  }

  @Test
  void replaceCommit() throws Exception {
    // RTAS is refused while replication is configured, so this table has none.
    CreateUpdateTableRequestBody body = newTable("gold_replace", "t");
    String v0 =
        make(
            body.toBuilder()
                .policies(body.getPolicies().toBuilder().replication(null).build())
                .build());
    put(mvc, withProp(v0, CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"))
        .andExpect(status().isOk());
    putSnapshots(mvc, "gold_replace", "t", append("gold_replace", "a")).andExpect(status().isOk());
    String v1 = json(get(mvc, "gold_replace", "t"));
    Table table = catalog.loadTable(TableIdentifier.of("gold_replace", "t"));
    GetTableResponseBody response =
        buildGetTableResponseBody(get(mvc, "gold_replace", "t").andReturn());
    Snapshot replacement =
        table
            .newAppend()
            .appendFile(createDummyDataFile("/tmp/gold_replace_r.orc", getPartitionSpec(response)))
            .apply();
    CreateUpdateTableRequestBody replace =
        bodyFromGet(v1)
            .toBuilder()
            .schema(addColumn(bodyFromGet(v1).getSchema(), "r", Types.LongType.get()))
            .replaceCommit(true)
            .build();
    IcebergSnapshotsRequestBody request =
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(str(v1, "$.tableLocation"))
            .jsonSnapshots(Collections.singletonList(SnapshotParser.toJson(replacement)))
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(replacement)))
            .createUpdateTableRequestBody(replace)
            .build();
    putSnapshots(mvc, "gold_replace", "t", request).andExpect(status().isOk());
    expectStored("gold_replace");
  }

  @Test
  void lockThenUnlock() throws Exception {
    make("gold_lock");
    lock(mvc, "gold_lock", "t").andExpect(status().isCreated());
    unlock(mvc, "gold_lock", "t").andExpect(status().isNoContent());
    expectStored("gold_lock");
  }

  // ---- stored state ----

  private void expectStored(String db) throws Exception {
    String actual = MAPPER.writeValueAsString(normalize(storedVersions(db)));
    String name = db + ".json";
    String target = System.getenv("STORED_METADATA_GOLDEN_DIR");
    if (target != null) {
      Path dir = Paths.get(target);
      Files.createDirectories(dir);
      Files.write(dir.resolve(name), (actual + "\n").getBytes(StandardCharsets.UTF_8));
      return;
    }
    try (InputStream in = getClass().getResourceAsStream("/stored-metadata/" + name)) {
      Assertions.assertNotNull(in, "missing expected file stored-metadata/" + name);
      String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      Assertions.assertEquals(expected, actual, "stored metadata differs for " + db);
    }
  }

  /** Every metadata file of {@code db.t}, oldest first, parsed as written. */
  private ArrayNode storedVersions(String db) throws Exception {
    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    TableMetadata current = ((HasTableOperations) table).operations().current();
    FileIO io = table.io();
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

  private static JsonNode normalize(ArrayNode versions) {
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

  private static JsonNode rewrite(JsonNode node, String key, Map<Long, Integer> ids) {
    if (node.isObject()) {
      ObjectNode out = MAPPER.createObjectNode();
      node.fields()
          .forEachRemaining(
              field -> out.set(field.getKey(), rewrite(field.getValue(), field.getKey(), ids)));
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
