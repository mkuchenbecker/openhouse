package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.formatversion.FormatVersionRule;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeConfigResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Format v3 tables through the native API, on a cluster with {@code
 * cluster.iceberg.format-v3.enabled} on: a table is v3 only when its create (or a replace of it)
 * asks for format version 3 or an update asks to upgrade it; it is never lowered; what v3 adds to a
 * metadata file is stored as committed across create, commit, rename and recreate; what the service
 * does not handle on v3 yet is refused with the work item that will; and the column default rules
 * of work item W6 hold.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatV3NativeApiTest {
  private static final String CAPABILITIES = TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES;

  private static final String OPT_IN = ReadBridgeConfigResolver.COLUMN_DEFAULTS_CAPABILITY_PROP;

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired ClusterProperties clusterProperties;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      unlock(mvc, db, "t");
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(db, "t"))
              .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
      delete(mvc, db, "renamed");
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private String make(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    ResultActions result = post(mvc, body);
    assertEquals(201, status(result), json(result));
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private String makeV3(String db) throws Exception {
    String stored = make(asking(plain(db), "3"));
    assertEquals(3, metadata(db).formatVersion());
    return stored;
  }

  private TableMetadata metadata(String db) {
    return FormatV3.metadata(catalog, db, "t");
  }

  private String stored(String db) throws Exception {
    return json(get(mvc, db, "t"));
  }

  private String ok(ResultActions result) throws Exception {
    assertEquals(200, status(result), json(result));
    return json(result);
  }

  private String refused(ResultActions result, String token) throws Exception {
    assertEquals(400, status(result), json(result));
    String message = message(result);
    assertTrue(message.startsWith(token + ": "), message);
    return message;
  }

  /**
   * A snapshots PUT that appends one data file on the stored table, built as a client builds it.
   */
  private IcebergSnapshotsRequestBody append(String db, String file) throws Exception {
    String current = stored(db);
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

  private static List<String> snapshotJson(TableMetadata metadata) {
    return metadata.snapshots().stream().map(SnapshotParser::toJson).collect(Collectors.toList());
  }

  // ---------------------------------------------------------------- create and upgrade

  @Test
  void aCreateAskingForFormatVersion3IsAV3TableAndOneThatDoesNotIsV2() throws Exception {
    assumeTrue(IcebergV3.supported());
    String v3 = makeV3("fv3_create");
    TableMetadata stored = metadata("fv3_create");
    assertEquals(3, stored.formatVersion());
    assertEquals(0L, IcebergV3.nextRowId(stored));
    // The format version is not a stored property, as in any Iceberg table.
    Map<String, String> properties = read(v3, "$.tableProperties");
    assertFalse(properties.containsKey(FORMAT_VERSION), properties.toString());
    // What the native API reports is what it reports for a v2 table of the same request.
    String v2 = make(plain("fv3_create_v2"));
    assertEquals(2, metadata("fv3_create_v2").formatVersion());
    assertEquals(str(v2, "$.schema"), str(v3, "$.schema"));
    assertEquals(
        ((Map<String, String>) read(v2, "$.tableProperties")).keySet(), properties.keySet());
    // The stored file says v3 and holds next-row-id, read back as written.
    String file =
        new String(
            java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(stored.metadataFileLocation())),
            java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(3, StoredMetadata.MAPPER.readTree(file).get("format-version").asInt());
    assertEquals(0, StoredMetadata.MAPPER.readTree(file).get("next-row-id").asInt());
  }

  @Test
  void anUpdateAskingForFormatVersion3UpgradesAV2TableAndKeepsItsHistory() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_upgrade";
    make(plain(db));
    ok(putSnapshots(mvc, db, "t", append(db, "a")));
    TableMetadata before = metadata(db);
    assertEquals(2, before.formatVersion());

    String upgraded = ok(put(mvc, asking(bodyFromGet(stored(db)), "3")));

    TableMetadata after = metadata(db);
    assertEquals(3, after.formatVersion());
    assertEquals(before.uuid(), after.uuid());
    assertEquals(before.location(), after.location());
    assertEquals(snapshotJson(before), snapshotJson(after));
    assertEquals(before.refs(), after.refs());
    assertTrue(before.schema().sameSchema(after.schema()));
    assertEquals(0L, IcebergV3.nextRowId(after));
    assertEquals(before.metadataFileLocation(), str(upgraded, "$.tableVersion"));
    Map<String, String> properties = read(upgraded, "$.tableProperties");
    assertFalse(properties.containsKey(FORMAT_VERSION), properties.toString());
    // The first commit after the upgrade assigns row ids to the rows that had none (the one row
    // written at v2) and to its own: Iceberg counts both in the snapshot's added-rows.
    ok(putSnapshots(mvc, db, "t", append(db, "b")));
    TableMetadata appended = metadata(db);
    assertEquals(2L, IcebergV3.nextRowId(appended));
    String afterUpgrade = SnapshotParser.toJson(appended.currentSnapshot());
    assertEquals(0L, snapshotField(afterUpgrade, "first-row-id"));
    assertEquals(2L, snapshotField(afterUpgrade, "added-rows"));
  }

  @Test
  void aV3TableIsNeverLoweredAndNoOtherVersionCanBeAskedFor() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_down";
    String v3 = makeV3(db);
    for (String lower : new String[] {"2", "1"}) {
      String message = refused(put(mvc, asking(bodyFromGet(v3), lower)), FormatVersionRule.REFUSED);
      assertTrue(message.contains("the table is format version 3"), message);
      assertTrue(message.contains("never lowered"), message);
    }
    refused(put(mvc, asking(bodyFromGet(v3), "4")), FormatVersionRule.REFUSED);
    assertEquals(v3, stored(db));
    // A create or an upgrade may ask for 3 and nothing above.
    created.add("fv3_down_4");
    refused(post(mvc, asking(plain("fv3_down_4"), "4")), FormatVersionRule.REFUSED);
    assertEquals(404, status(get(mvc, "fv3_down_4", "t")));
    String v2 = make(plain("fv3_down_v2"));
    refused(put(mvc, asking(bodyFromGet(v2), "4")), FormatVersionRule.REFUSED);
    assertEquals(2, metadata("fv3_down_v2").formatVersion());
  }

  /** On the reference line the switch cannot make v3 tables: Iceberg 1.5.2 cannot build them. */
  @Test
  void onAnIcebergThatCannotBuildV3ARequestForV3IsRefusedAndSaysWhy() throws Exception {
    assumeFalse(IcebergV3.supported());
    created.add("fv3_refline");
    String message =
        refused(post(mvc, asking(plain("fv3_refline"), "3")), FormatVersionRule.REFUSED);
    assertTrue(message.contains("cannot read or write format version 3"), message);
    assertTrue(message.contains("Iceberg 1.12"), message);
    String v2 = make(plain("fv3_refline_v2"));
    message = refused(put(mvc, asking(bodyFromGet(v2), "3")), FormatVersionRule.REFUSED);
    assertTrue(message.contains("cannot read or write format version 3"), message);
    assertEquals(2, metadata("fv3_refline_v2").formatVersion());
  }

  // ---------------------------------------------------------------- survival of what v3 adds

  @Test
  void snapshotsOnAV3TableKeepTheirRowLineageAndNextRowIdAdvances() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_rows";
    makeV3(db);
    IcebergSnapshotsRequestBody first = append(db, "a");
    ok(putSnapshots(mvc, db, "t", first));
    IcebergSnapshotsRequestBody second = append(db, "b");
    ok(putSnapshots(mvc, db, "t", second));

    TableMetadata stored = metadata(db);
    // The snapshots are stored as the client sent them, first-row-id and added-rows included.
    assertEquals(second.getJsonSnapshots(), snapshotJson(stored));
    assertEquals(0L, snapshotField(second.getJsonSnapshots().get(0), "first-row-id"));
    assertEquals(1L, snapshotField(second.getJsonSnapshots().get(0), "added-rows"));
    assertEquals(1L, snapshotField(second.getJsonSnapshots().get(1), "first-row-id"));
    assertEquals(1L, snapshotField(second.getJsonSnapshots().get(1), "added-rows"));
    assertEquals(2L, IcebergV3.nextRowId(stored));
    // A property change keeps all of it; so does a rename.
    ok(put(mvc, withProp(stored(db), "user.note", "x")));
    assertEquals(204, status(rename(mvc, db, "t", db, "renamed")));
    TableMetadata renamed = FormatV3.metadata(catalog, db, "renamed");
    assertEquals(3, renamed.formatVersion());
    assertEquals(2L, IcebergV3.nextRowId(renamed));
    assertEquals(second.getJsonSnapshots(), snapshotJson(renamed));
    assertEquals(204, status(rename(mvc, db, "renamed", db, "t")));
    // A snapshot behind the table's next-row-id is refused, not stored.
    IcebergSnapshotsRequestBody third = append(db, "c");
    List<String> behind = new ArrayList<>(third.getJsonSnapshots());
    assertEquals(2L, snapshotField(behind.get(2), "first-row-id"));
    behind.set(2, behind.get(2).replace("\"first-row-id\" : 2", "\"first-row-id\" : 0"));
    assertEquals(0L, snapshotField(behind.get(2), "first-row-id"));
    ResultActions refused = putSnapshots(mvc, db, "t", copy(third).jsonSnapshots(behind).build());
    assertTrue(status(refused) == 400 || status(refused) == 409, json(refused));
    assertEquals(2L, IcebergV3.nextRowId(metadata(db)));
  }

  @Test
  void aCreateWithDataAtV3AndADroppedTablesNameStartClean() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_ctas";
    created.add(db);
    // Stage, write one file against the staged v3 metadata, and commit it as the create, sending
    // the format version the way the OpenHouse Java client does: as the property.
    String staged = NativeCtas.stage(mvc, asking(plain(db), "3"));
    TableMetadata stagedMetadata =
        TableMetadataParser.read(IrcWrite.IO, str(staged, "$.tableLocation"));
    assertEquals(3, stagedMetadata.formatVersion());
    String snapshot =
        SnapshotParser.toJson(
            NativeCtas.table(stagedMetadata, IrcWrite.IO)
                .newAppend()
                .appendFile(createDummyDataFile("/tmp/" + db + "_a.orc", stagedMetadata.spec()))
                .apply());
    ResultActions create =
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
                .build());
    assertEquals(201, status(create), json(create));
    TableMetadata stored = metadata(db);
    assertEquals(3, stored.formatVersion());
    assertEquals(Collections.singletonList(snapshot), snapshotJson(stored));
    assertEquals(1L, IcebergV3.nextRowId(stored));

    // Dropped and created again without asking: a v2 table, with nothing of the v3 one.
    assertEquals(204, status(delete(mvc, db, "t")));
    make(plain(db));
    assertEquals(2, metadata(db).formatVersion());
    assertTrue(metadata(db).snapshots().isEmpty());
    // Dropped and created again at v3: row ids start over.
    assertEquals(204, status(delete(mvc, db, "t")));
    makeV3(db);
    assertEquals(0L, IcebergV3.nextRowId(metadata(db)));
  }

  // ---------------------------------------------------------------- replace

  private String replaceable(CreateUpdateTableRequestBody body) throws Exception {
    return make(with(body, CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"));
  }

  /**
   * An RTAS as the OpenHouse Java client sends it: a staged replace, one snapshot written against
   * the staged metadata, and the replace commit, each asking for {@code askedVersion} if given.
   * Returns the staged replace's answer when it is refused.
   */
  private ResultActions replace(String db, String askedVersion) throws Exception {
    String current = stored(db);
    CreateUpdateTableRequestBody body = bodyFromGet(current);
    if (askedVersion != null) {
      body = asking(body, askedVersion);
    }
    ResultActions staged = post(mvc, body.toBuilder().stageReplace(true).build());
    if (status(staged) != 201) {
      return staged;
    }
    TableMetadata stagedMetadata =
        TableMetadataParser.read(IrcWrite.IO, str(json(staged), "$.tableLocation"));
    String snapshot =
        SnapshotParser.toJson(
            NativeCtas.table(stagedMetadata, IrcWrite.IO)
                .newAppend()
                .appendFile(createDummyDataFile("/tmp/" + db + "_r.orc", stagedMetadata.spec()))
                .apply());
    List<String> snapshots = new ArrayList<>(snapshotJson(stagedMetadata));
    snapshots.add(snapshot);
    return putSnapshots(
        mvc,
        db,
        "t",
        IcebergSnapshotsRequestBody.builder()
            .baseTableVersion(str(current, "$.tableLocation"))
            .jsonSnapshots(snapshots)
            .snapshotRefs(obtainSnapshotRefsFromSnapshot(snapshot))
            .createUpdateTableRequestBody(body.toBuilder().replaceCommit(true).build())
            .build());
  }

  @Test
  void aReplaceKeepsTheTablesFormatVersionAndUpgradesOnlyWhenItAsksFor3() throws Exception {
    assumeTrue(IcebergV3.supported());
    // A v3 table replaced without naming a version stays v3 (the replace used to force the
    // cluster's version, which Iceberg refuses as a downgrade).
    replaceable(asking(plain("fv3_replace_keep"), "3"));
    ok(putSnapshots(mvc, "fv3_replace_keep", "t", append("fv3_replace_keep", "a")));
    ok(replace("fv3_replace_keep", null));
    TableMetadata kept = metadata("fv3_replace_keep");
    assertEquals(3, kept.formatVersion());
    assertEquals(2, kept.snapshots().size());
    assertEquals(2L, IcebergV3.nextRowId(kept));
    // Staged replace too.
    ResultActions staged =
        post(mvc, bodyFromGet(stored("fv3_replace_keep")).toBuilder().stageReplace(true).build());
    assertEquals(201, status(staged), json(staged));
    assertEquals(
        3,
        TableMetadataParser.read(IrcWrite.IO, str(json(staged), "$.tableLocation"))
            .formatVersion());

    // A replace that names 2 on a v3 table is a downgrade.
    String before = stored("fv3_replace_keep");
    String message = refused(replace("fv3_replace_keep", "2"), FormatVersionRule.REFUSED);
    assertTrue(message.contains("Replace of table"), message);
    assertTrue(message.contains("never lowered"), message);
    assertEquals(before, stored("fv3_replace_keep"));

    // A v2 table replaced without naming a version stays v2; one that names 3 becomes v3.
    replaceable(plain("fv3_replace_v2"));
    ok(replace("fv3_replace_v2", null));
    assertEquals(2, metadata("fv3_replace_v2").formatVersion());
    ok(replace("fv3_replace_v2", "3"));
    TableMetadata upgraded = metadata("fv3_replace_v2");
    assertEquals(3, upgraded.formatVersion());
    assertEquals(2, upgraded.snapshots().size());
  }

  /**
   * Turning the switch off again stops new v3 tables, not the ones that exist: a v3 table still
   * takes property changes, snapshots and a replace, and stays v3.
   */
  @Test
  void aV3TableKeepsWorkingWhenTheSwitchIsTurnedOffAgain() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_switched_off";
    replaceable(asking(plain(db), "3"));
    ok(putSnapshots(mvc, db, "t", append(db, "a")));
    String v2 = make(plain("fv3_switched_off_v2"));
    ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatV3Enabled", false);
    try {
      ok(put(mvc, withProp(stored(db), "user.note", "x")));
      ok(putSnapshots(mvc, db, "t", append(db, "b")));
      ok(replace(db, null));
      assertEquals(204, status(rename(mvc, db, "t", db, "renamed")));
      assertEquals(204, status(rename(mvc, db, "renamed", db, "t")));
      TableMetadata kept = metadata(db);
      assertEquals(3, kept.formatVersion());
      assertEquals(3, kept.snapshots().size());
      assertEquals(3L, IcebergV3.nextRowId(kept));
      // No new v3 table, by create or by upgrade.
      created.add("fv3_switched_off_new");
      refused(post(mvc, asking(plain("fv3_switched_off_new"), "3")), FormatVersionRule.REFUSED);
      refused(put(mvc, asking(bodyFromGet(v2), "3")), FormatVersionRule.REFUSED);
      assertEquals(2, metadata("fv3_switched_off_v2").formatVersion());
    } finally {
      ReflectionTestUtils.setField(clusterProperties, "clusterIcebergFormatV3Enabled", true);
    }
  }

  // ---------------------------------------------------------------- refused on v3 for now

  @Test
  void aFormatV3TypeIsRefusedAndNamesItsWorkItem() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_types";
    created.add(db);
    for (String type : new String[] {"timestamp_ns", "timestamptz_ns"}) {
      CreateUpdateTableRequestBody body = asking(plain(db), "3");
      String message =
          refused(
              post(
                  mvc,
                  body.toBuilder()
                      .schema(addColumn(body.getSchema(), "v", type, null, null))
                      .build()),
              "FORMAT_V3_TYPE_NOT_SUPPORTED");
      assertTrue(message.contains("work item X5"), message);
      assertEquals(404, status(get(mvc, db, "t")));
    }
    String v3 = makeV3(db);
    refused(
        put(
            mvc,
            bodyFromGet(v3)
                .toBuilder()
                .schema(addColumn(str(v3, "$.schema"), "v", "timestamp_ns", null, null))
                .build()),
        "FORMAT_V3_TYPE_NOT_SUPPORTED");
    // A variant column is refused by the same policy on a table that writes ORC, the cluster's
    // default (FormatV3TypesTest has every type, and the tables that take one).
    for (ResultActions variant :
        new ResultActions[] {
          put(
              mvc,
              bodyFromGet(v3)
                  .toBuilder()
                  .schema(addColumn(str(v3, "$.schema"), "v", "variant", null, null))
                  .build()),
          post(
              mvc,
              asking(plain("fv3_types_variant"), "3")
                  .toBuilder()
                  .schema(addColumn(plain(db).getSchema(), "v", "variant", null, null))
                  .build())
        }) {
      assertEquals(400, status(variant), json(variant));
      assertTrue(
          message(variant).startsWith("FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table "),
          message(variant));
    }
    assertEquals(v3, stored(db));
  }

  @Test
  void aReplicaOrReplicatedTableAtV3IsRefusedAndNamesItsWorkItem() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_replica";
    created.add(db);
    String uuid = "cee3c6a3-a824-443a-832a-d4a1271e1e40";
    Map<String, String> properties = new HashMap<>(plain(db).getTableProperties());
    properties.put("openhouse.tableUUID", uuid);
    properties.put("openhouse.databaseId", db);
    properties.put("openhouse.tableId", "t");
    properties.put("openhouse.tableType", "REPLICA_TABLE");
    properties.put(
        "openhouse.tableLocation", "/tmp/unused/" + db + "/t-" + uuid + "/metadata.json");
    CreateUpdateTableRequestBody replica =
        plain(db)
            .toBuilder()
            .tableType(TableType.REPLICA_TABLE)
            .tableProperties(properties)
            .build();
    String message =
        refused(post(mvc, asking(replica, "3")), "FORMAT_V3_REPLICATION_NOT_SUPPORTED");
    assertTrue(message.contains("work item X7"), message);
    assertEquals(404, status(get(mvc, db, "t")));
    // A v2 replica is created as before and cannot be upgraded.
    assertEquals(201, status(post(mvc, replica)));
    refused(put(mvc, asking(bodyFromGet(stored(db)), "3")), "FORMAT_V3_REPLICATION_NOT_SUPPORTED");
    assertEquals(2, metadata(db).formatVersion());
    // A table marked replicated cannot be created at v3 either.
    created.add("fv3_replicated");
    refused(
        post(
            mvc,
            asking(
                with(
                    with(
                        plain("fv3_replicated"),
                        CatalogConstants.OPENHOUSE_IS_TABLE_REPLICATED_KEY,
                        "true"),
                    CatalogConstants.LAST_UPDATED_MS,
                    Long.toString(System.currentTimeMillis() - 1000)),
                "3")),
        "FORMAT_V3_REPLICATION_NOT_SUPPORTED");
  }

  @Test
  void tableEncryptionPropertiesAreRefusedOnV3() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_encryption";
    created.add(db);
    refused(
        post(mvc, with(asking(plain(db), "3"), "encryption.key-id", "k1")),
        "FORMAT_V3_ENCRYPTION_NOT_SUPPORTED");
    String v3 = makeV3(db);
    refused(
        put(mvc, withProp(v3, "encryption.key-id", "k1")), "FORMAT_V3_ENCRYPTION_NOT_SUPPORTED");
    assertEquals(v3, stored(db));
  }

  // ---------------------------------------------------------------- W6: column defaults

  private CreateUpdateTableRequestBody withSchema(String getJson, String schema) {
    return bodyFromGet(getJson).toBuilder().schema(schema).build();
  }

  @Test
  void columnDefaultsOnAV3TableAreStoredAsCommitted() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_defaults";
    created.add(db);
    // A create may carry defaults on any column: all of them are new.
    CreateUpdateTableRequestBody body = asking(plain(db), "3");
    String created =
        make(
            body.toBuilder()
                .schema(addColumn(body.getSchema(), "country", "string", "\"US\"", "\"CA\""))
                .build());
    assertEquals("US", column(str(created, "$.schema"), "country").get("initial-default").asText());
    assertEquals("CA", column(str(created, "$.schema"), "country").get("write-default").asText());
    // An update may add a column with defaults, and they are stored as sent, in the file too.
    String schema = addColumn(str(created, "$.schema"), "tier", "int", "1", "2");
    String updated = ok(put(mvc, withSchema(created, schema)));
    assertEquals(1, column(str(updated, "$.schema"), "tier").get("initial-default").asInt());
    assertEquals(2, column(str(updated, "$.schema"), "tier").get("write-default").asInt());
    TableMetadata stored = metadata(db);
    assertEquals(1, stored.schema().findField("tier").initialDefault());
    assertEquals(2, stored.schema().findField("tier").writeDefault());
    assertEquals("US", stored.schema().findField("country").initialDefault().toString());
    // A write-default may change; the initial-default stays.
    String changed =
        ok(put(mvc, withSchema(updated, withDefaults(str(updated, "$.schema"), "tier", "1", "9"))));
    assertEquals(9, column(str(changed, "$.schema"), "tier").get("write-default").asInt());
    assertEquals(1, column(str(changed, "$.schema"), "tier").get("initial-default").asInt());
    // And a write that sends the schema back as loaded, defaults included, keeps them: a
    // property change and an append.
    ok(put(mvc, withProp(changed, "user.note", "x")));
    ok(putSnapshots(mvc, db, "t", append(db, "a")));
    TableMetadata after = metadata(db);
    assertEquals(1, after.schema().findField("tier").initialDefault());
    assertEquals(9, after.schema().findField("tier").writeDefault());
    assertEquals("US", after.schema().findField("country").initialDefault().toString());
    assertEquals("CA", after.schema().findField("country").writeDefault().toString());
    // No read-bridge config is sent for the table.
    assertTrue(((Map<?, ?>) read(stored(db), "$.config")).isEmpty(), stored(db));
  }

  /**
   * A write that sends a column's name back in other casing, which the service has always accepted
   * and stored under the table's casing, keeps that column's defaults.
   */
  @Test
  void aColumnSentBackInOtherCasingKeepsItsDefaults() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_recased";
    created.add(db);
    CreateUpdateTableRequestBody body = asking(plain(db), "3");
    String v0 =
        make(
            body.toBuilder()
                .schema(addColumn(body.getSchema(), "country", "string", "\"US\"", "\"CA\""))
                .build());
    String recased = str(v0, "$.schema").replace("\"name\":\"country\"", "\"name\":\"COUNTRY\"");
    assertFalse(recased.equals(str(v0, "$.schema")));
    ok(
        put(
            mvc,
            withProp(json(get(mvc, db, "t")), "user.note", "x")
                .toBuilder()
                .schema(recased)
                .build()));
    TableMetadata stored = metadata(db);
    assertEquals("country", stored.schema().findField("country").name());
    assertEquals("US", stored.schema().findField("country").initialDefault().toString());
    assertEquals("CA", stored.schema().findField("country").writeDefault().toString());
  }

  @Test
  void anExistingColumnsInitialDefaultNeverChangesOnV3() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_initial";
    created.add(db);
    CreateUpdateTableRequestBody body = asking(plain(db), "3");
    String v0 =
        make(
            body.toBuilder()
                .schema(addColumn(body.getSchema(), "country", "string", "\"US\"", "\"US\""))
                .build());
    String schema = str(v0, "$.schema");
    // Changed, removed, and set on a column that had none: each refused, nothing stored.
    for (String attempt :
        new String[] {
          withDefaults(schema, "country", "\"CA\"", "\"US\""),
          withDefaults(schema, "country", null, "\"US\""),
          withDefaults(schema, "name", "\"anonymous\"", null)
        }) {
      String message =
          refused(put(mvc, withSchema(v0, attempt)), "COLUMN_INITIAL_DEFAULT_IMMUTABLE");
      assertTrue(message.contains("existing column"), message);
      assertEquals(v0, stored(db));
    }
    // In the commit that adds a column, with another existing column's default changed: refused.
    refused(
        put(
            mvc,
            withSchema(
                v0,
                addColumn(
                    withDefaults(schema, "country", "\"CA\"", "\"US\""), "tier", "int", "1", "1"))),
        "COLUMN_INITIAL_DEFAULT_IMMUTABLE");
    assertEquals(v0, stored(db));
  }

  /**
   * Widening the type of a defaulted column (int to long, float to double) carries its
   * initial-default over as Iceberg does, by converting the value to the new type: that is not a
   * change of the initial-default, so it is stored. Widening while also changing the value is.
   */
  @Test
  void aTypeWideningCarriesTheInitialDefaultOverOnV3() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_widen";
    created.add(db);
    CreateUpdateTableRequestBody body = asking(plain(db), "3");
    String v0 =
        make(
            body.toBuilder()
                .schema(
                    addColumn(
                        addColumn(body.getSchema(), "tier", "int", "7", "7"),
                        "score",
                        "float",
                        "1.5",
                        "1.5"))
                .build());
    String schema = str(v0, "$.schema");

    refused(
        put(mvc, withSchema(v0, withDefaults(withType(schema, "tier", "long"), "tier", "8", "7"))),
        "COLUMN_INITIAL_DEFAULT_IMMUTABLE");
    assertEquals(v0, stored(db));

    String widened = withType(withType(schema, "tier", "long"), "score", "double");
    ok(put(mvc, withSchema(v0, widened)));
    org.apache.iceberg.Schema stored = FormatV3.metadata(catalog, db, "t").schema();
    assertEquals(7L, stored.findField("tier").initialDefault());
    assertEquals(7L, stored.findField("tier").writeDefault());
    assertEquals(1.5d, stored.findField("score").initialDefault());
  }

  @Test
  void theReadBridgeOptInIsRefusedOnV3() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "fv3_optin";
    created.add(db);
    // On create.
    ResultActions create =
        mvc.perform(
            MockMvcRequestBuilders.post("/v1/databases/" + db + "/tables/")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
                .content(with(asking(plain(db), "3"), OPT_IN, "true").toJson())
                .accept(MediaType.APPLICATION_JSON));
    String message = refused(create, "COLUMN_DEFAULTS_OPT_IN_ON_V3");
    assertTrue(message.contains("work item W8"), message);
    assertEquals(404, status(get(mvc, db, "t")));
    // On an update of a v3 table.
    String v3 = makeV3(db);
    refused(putWithCapability(withProp(v3, OPT_IN, "true")), "COLUMN_DEFAULTS_OPT_IN_ON_V3");
    assertEquals(v3, stored(db));
    // On the upgrade of a v2 table that has it.
    String bridged = "fv3_optin_v2";
    created.add(bridged);
    ResultActions v2 =
        mvc.perform(
            MockMvcRequestBuilders.post("/v1/databases/" + bridged + "/tables/")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
                .content(with(plain(bridged), OPT_IN, "true").toJson())
                .accept(MediaType.APPLICATION_JSON));
    assertEquals(201, status(v2), json(v2));
    String before = json(getWithCapability(bridged));
    refused(putWithCapability(asking(bodyFromGet(before), "3")), "COLUMN_DEFAULTS_OPT_IN_ON_V3");
    assertEquals(2, FormatV3.metadata(catalog, bridged, "t").formatVersion());
  }

  private ResultActions putWithCapability(CreateUpdateTableRequestBody body) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.put(tableUrl(body.getDatabaseId(), body.getTableId()))
            .contentType(MediaType.APPLICATION_JSON)
            .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
            .content(body.toJson())
            .accept(MediaType.APPLICATION_JSON));
  }

  private ResultActions getWithCapability(String db) throws Exception {
    return mvc.perform(
        MockMvcRequestBuilders.get(tableUrl(db, "t"))
            .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
            .accept(MediaType.APPLICATION_JSON));
  }

  /**
   * Below v3 native defaults do not exist: a commit that sets a write-default is refused with the
   * remedy, on create and on update, and an initial-default is dropped as it always was.
   */
  @Test
  void aWriteDefaultBelowV3IsRefused() throws Exception {
    String db = "fv3_v2_write_default";
    created.add(db);
    CreateUpdateTableRequestBody body = plain(db);
    String message =
        refused(
            post(
                mvc,
                body.toBuilder()
                    .schema(addColumn(body.getSchema(), "country", "string", null, "\"US\""))
                    .build()),
            "COLUMN_DEFAULT_REQUIRES_V3");
    assertTrue(message.contains(db + ".t is format v2"), message);
    assertTrue(message.contains("native column defaults need format v3"), message);
    assertTrue(message.contains("upgrading is irreversible"), message);
    assertEquals(404, status(get(mvc, db, "t")));

    String v2 = make(plain(db));
    refused(
        put(
            mvc,
            withSchema(v2, addColumn(str(v2, "$.schema"), "country", "string", null, "\"US\""))),
        "COLUMN_DEFAULT_REQUIRES_V3");
    refused(
        put(mvc, withSchema(v2, withDefaults(str(v2, "$.schema"), "name", null, "\"anonymous\""))),
        "COLUMN_DEFAULT_REQUIRES_V3");
    assertEquals(v2, stored(db));
    // An initial-default on a v2 table is dropped, as before: the column is added without it.
    String added =
        ok(
            put(
                mvc,
                withSchema(
                    v2, addColumn(str(v2, "$.schema"), "country", "string", "\"US\"", null))));
    assertNotNull(column(str(added, "$.schema"), "country"));
    assertFalse(column(str(added, "$.schema"), "country").has("initial-default"));
    assertEquals(2, metadata(db).formatVersion());
  }
}
