package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.SORT_ORDER;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
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
 * was before it was restructured into one commit core, so a change in stored content or in the
 * number of metadata versions a write produces fails here.
 *
 * <p>What is normalized, and nothing else, is listed on {@link StoredMetadata}.
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

  /** A create without partitioning, clustering, policies or sort order. */
  @Test
  void createUnpartitioned() throws Exception {
    make(unpartitioned("gold_create_plain"));
    expectStored("gold_create_plain");
  }

  /** A create with data (CTAS), as the OpenHouse Java client sends it. */
  @Test
  void createWithData() throws Exception {
    created.add("gold_ctas");
    NativeCtas.createWithData(mvc, newTable("gold_ctas", "t"), "/tmp/gold_ctas_a.orc")
        .andExpect(status().isCreated());
    expectStored("gold_ctas");
  }

  static CreateUpdateTableRequestBody unpartitioned(String db) {
    return newTable(db, "t")
        .toBuilder()
        .timePartitioning(null)
        .clustering(null)
        .policies(null)
        .build();
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
    String actual = StoredMetadata.normalized(catalog, db, "t");
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
}
