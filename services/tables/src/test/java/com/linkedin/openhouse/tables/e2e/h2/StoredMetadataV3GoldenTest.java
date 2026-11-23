package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins what the native API stores for format v3 tables: every metadata file each scenario leaves,
 * in order, as written, after the normalization of {@link StoredMetadata} and nothing else. The
 * scenarios are the format v3 counterparts of {@link StoredMetadataGoldenTest}'s, plus the two
 * writes that make a table v3 (a create that asks for it and the upgrade of a v2 table) and a
 * schema change that adds a column with defaults. The files show, field by field, what a format v3
 * metadata file holds after the service's rebuild: {@code format-version}, {@code next-row-id},
 * each snapshot's {@code first-row-id} and {@code added-rows}, and each field's {@code
 * initial-default} and {@code write-default}.
 *
 * <p>The expected files are under {@code stored-metadata/v3/}. To rewrite them, run with the
 * environment variable {@code STORED_METADATA_V3_GOLDEN_DIR} set to {@code
 * services/tables/src/test/resources/stored-metadata/v3}. Format v3 needs the Iceberg 1.12 line.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class StoredMetadataV3GoldenTest {
  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;

  private final List<String> created = new ArrayList<>();

  @BeforeEach
  void needsFormatV3() {
    assumeTrue(IcebergV3.supported());
  }

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
      delete(mvc, db, "renamed");
    }
    created.clear();
  }

  private String make(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private IcebergSnapshotsRequestBody append(String db, String file) throws Exception {
    String current = json(get(mvc, db, "t"));
    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    Snapshot snapshot =
        table
            .newAppend()
            .appendFile(createDummyDataFile("/tmp/" + db + "_" + file + ".orc", table.spec()))
            .apply();
    List<String> snapshots = new ArrayList<>();
    table.snapshots().forEach(s -> snapshots.add(SnapshotParser.toJson(s)));
    snapshots.add(SnapshotParser.toJson(snapshot));
    return IcebergSnapshotsRequestBody.builder()
        .baseTableVersion(str(current, "$.tableLocation"))
        .jsonSnapshots(snapshots)
        .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(snapshot)))
        .createUpdateTableRequestBody(bodyFromGet(current))
        .build();
  }

  /** A create that asks for format version 3, with partitioning, clustering and policies. */
  @Test
  void create() throws Exception {
    make(asking(newTable("gold_v3_create", "t"), "3"));
    expectStored("gold_v3_create", "t");
  }

  /** The upgrade of a v2 table that has a snapshot, then the first append after it. */
  @Test
  void upgradeThenAppend() throws Exception {
    make(newTable("gold_v3_upgrade", "t"));
    putSnapshots(mvc, "gold_v3_upgrade", "t", append("gold_v3_upgrade", "a"))
        .andExpect(status().isOk());
    put(mvc, asking(bodyFromGet(json(get(mvc, "gold_v3_upgrade", "t"))), "3"))
        .andExpect(status().isOk());
    putSnapshots(mvc, "gold_v3_upgrade", "t", append("gold_v3_upgrade", "b"))
        .andExpect(status().isOk());
    expectStored("gold_v3_upgrade", "t");
  }

  @Test
  void twoAppendsAPropertyChangeAndARename() throws Exception {
    String db = "gold_v3_append";
    make(asking(newTable(db, "t"), "3"));
    putSnapshots(mvc, db, "t", append(db, "a")).andExpect(status().isOk());
    putSnapshots(mvc, db, "t", append(db, "b")).andExpect(status().isOk());
    put(mvc, withProp(json(get(mvc, db, "t")), "user.note", "x")).andExpect(status().isOk());
    rename(mvc, db, "t", db, "renamed").andExpect(status().isNoContent());
    expectStored(db, "renamed");
  }

  /** A column added with defaults, then its write-default changed, then an append. */
  @Test
  void columnDefaults() throws Exception {
    String db = "gold_v3_defaults";
    String v0 = make(asking(newTable(db, "t"), "3"));
    String v1 =
        json(
            put(
                    mvc,
                    bodyFromGet(v0)
                        .toBuilder()
                        .schema(
                            addColumn(str(v0, "$.schema"), "country", "string", "\"US\"", "\"US\""))
                        .build())
                .andExpect(status().isOk()));
    v1 = json(get(mvc, db, "t"));
    put(
            mvc,
            bodyFromGet(v1)
                .toBuilder()
                .schema(withDefaults(str(v1, "$.schema"), "country", "\"US\"", "\"CA\""))
                .build())
        .andExpect(status().isOk());
    putSnapshots(mvc, db, "t", append(db, "a")).andExpect(status().isOk());
    expectStored(db, "t");
  }

  /** A create with data (CTAS) at v3, as the OpenHouse Java client sends it. */
  @Test
  void createWithData() throws Exception {
    String db = "gold_v3_ctas";
    created.add(db);
    String staged = NativeCtas.stage(mvc, asking(newTable(db, "t"), "3"));
    TableMetadata metadata = TableMetadataParser.read(IrcWrite.IO, str(staged, "$.tableLocation"));
    String snapshot =
        SnapshotParser.toJson(
            NativeCtas.table(metadata, IrcWrite.IO)
                .newAppend()
                .appendFile(createDummyDataFile("/tmp/gold_v3_ctas_a.orc", metadata.spec()))
                .apply());
    putSnapshots(
            mvc,
            db,
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(INITIAL_TABLE_VERSION)
                .jsonSnapshots(Collections.singletonList(snapshot))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(snapshot))
                .createUpdateTableRequestBody(
                    asking(bodyFromGet(staged), "3")
                        .toBuilder()
                        .baseTableVersion(INITIAL_TABLE_VERSION)
                        .build())
                .build())
        .andExpect(status().isCreated());
    expectStored(db, "t");
  }

  /** A replace commit (RTAS) of a v3 table that names no version. */
  @Test
  void replaceCommit() throws Exception {
    String db = "gold_v3_replace";
    CreateUpdateTableRequestBody body = newTable(db, "t");
    make(
        with(
            asking(
                body.toBuilder()
                    .policies(body.getPolicies().toBuilder().replication(null).build())
                    .build(),
                "3"),
            CatalogConstants.RTAS_ENABLED_TABLE_PROP,
            "true"));
    putSnapshots(mvc, db, "t", append(db, "a")).andExpect(status().isOk());
    String current = json(get(mvc, db, "t"));
    Table table = catalog.loadTable(TableIdentifier.of(db, "t"));
    String replacement =
        SnapshotParser.toJson(
            table
                .newAppend()
                .appendFile(createDummyDataFile("/tmp/gold_v3_replace_r.orc", table.spec()))
                .apply());
    putSnapshots(
            mvc,
            db,
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(current, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(replacement))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(replacement))
                .createUpdateTableRequestBody(
                    bodyFromGet(current)
                        .toBuilder()
                        .schema(addColumn(str(current, "$.schema"), "r", "long", null, null))
                        .replaceCommit(true)
                        .build())
                .build())
        .andExpect(status().isOk());
    expectStored(db, "t");
  }

  private void expectStored(String db, String table) throws Exception {
    String actual = StoredMetadata.normalized(catalog, db, table);
    String name = db + ".json";
    String target = System.getenv("STORED_METADATA_V3_GOLDEN_DIR");
    if (target != null) {
      Path dir = Paths.get(target);
      Files.createDirectories(dir);
      Files.write(dir.resolve(name), (actual + "\n").getBytes(StandardCharsets.UTF_8));
      return;
    }
    try (InputStream in = getClass().getResourceAsStream("/stored-metadata/v3/" + name)) {
      Assertions.assertNotNull(in, "missing expected file stored-metadata/v3/" + name);
      String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      Assertions.assertEquals(expected, actual, "stored metadata differs for " + db);
    }
  }
}
