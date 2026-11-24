package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Format v3 tables through the Iceberg REST routes, on a cluster with {@code
 * cluster.iceberg.format-v3.enabled} on, with requests built the way Iceberg's {@code RESTCatalog}
 * builds them (see {@link IrcWrite}): a create that asks for {@code format-version} 3, a staged
 * create and its completion, and the commit {@code ALTER TABLE ... SET TBLPROPERTIES
 * ('format-version'='3')} sends, which is a single {@code upgrade-format-version} update. The same
 * writes through the native API store the same metadata, and what v3 adds to a metadata file is
 * returned exactly as committed.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcFormatV3RoutesTest {
  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(plain("x").getSchema());

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired HouseTableRepository houseTableRepository;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      delete(mvc, db, "t");
      delete(mvc, db, "renamed");
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private static CreateTableRequest.Builder ircCreate(String version) {
    Map<String, String> properties = new HashMap<>(plain("x").getTableProperties());
    if (version != null) {
      properties.put(FORMAT_VERSION, version);
    }
    return CreateTableRequest.builder().withName("t").withSchema(SCHEMA).setProperties(properties);
  }

  private TableMetadata createV3(String db) throws Exception {
    created.add(db);
    TableMetadata response = IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate("3").build()));
    assertEquals(3, response.formatVersion());
    return response;
  }

  private String createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    assertEquals(201, status(post(mvc, body)));
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private TableMetadata stored(String db) {
    return FormatV3.metadata(catalog, db, "t");
  }

  private void assertSameStored(String nativeDb, String ircDb) throws Exception {
    String expected = StoredMetadata.normalized(catalog, nativeDb, "t").replace(nativeDb, "DB");
    String actual = StoredMetadata.normalized(catalog, ircDb, "t").replace(ircDb, "DB");
    assertEquals(expected, actual);
  }

  private static List<String> snapshotJson(TableMetadata metadata) {
    return metadata.snapshots().stream().map(SnapshotParser::toJson).collect(Collectors.toList());
  }

  /** What the routes answered is what is stored: the fields v3 adds, and the snapshots. */
  private void assertAnsweredIsStored(String db, TableMetadata answered) {
    TableMetadata stored = stored(db);
    assertEquals(stored.formatVersion(), answered.formatVersion());
    assertEquals(IcebergV3.nextRowId(stored), IcebergV3.nextRowId(answered));
    assertEquals(snapshotJson(stored), snapshotJson(answered));
    assertEquals(SchemaParser.toJson(stored.schema()), SchemaParser.toJson(answered.schema()));
  }

  // ---------------------------------------------------------------- create

  @Test
  void aCreateAskingForFormatVersion3StoresWhatTheNativeCreateStores() throws Exception {
    assumeTrue(IcebergV3.supported());
    TableMetadata response = createV3("irc_v3_create");
    createNative(asking(plain("irc_v3_create_native"), "3"));
    assertSameStored("irc_v3_create_native", "irc_v3_create");
    assertEquals(0L, IcebergV3.nextRowId(response));
    assertAnsweredIsStored("irc_v3_create", response);
    // A load answers the stored v3 metadata, with no read-bridge config.
    MvcResult load = IrcHttp.get(mvc, IrcWrite.table("irc_v3_create", "t"));
    assertAnsweredIsStored("irc_v3_create", IrcWrite.parsed(load));
    assertEquals(3, (int) JsonPath.read(IrcHttp.body(load), "$.metadata.format-version"));
    assertEquals(0, (int) JsonPath.read(IrcHttp.body(load), "$.metadata.next-row-id"));
    assertFalse(IrcHttp.body(load).contains("openhouse.read-bridge"), IrcHttp.body(load));
    // A create that asks for nothing, or for 2, is still v2; other versions are still refused.
    created.add("irc_v3_create_v2");
    assertEquals(
        2,
        IrcWrite.parsed(IrcWrite.create(mvc, "irc_v3_create_v2", ircCreate(null).build()))
            .formatVersion());
    created.add("irc_v3_create_other");
    for (String asked : new String[] {"1", "4", "x"}) {
      assertIrcError(
          IrcWrite.create(mvc, "irc_v3_create_other", ircCreate(asked).build()),
          400,
          "BadRequestException");
    }
    assertEquals(404, status(get(mvc, "irc_v3_create_other", "t")));
  }

  /** CTAS: a staged create at v3, one snapshot written against it, and the completing commit. */
  @Test
  void aStagedCreateAtV3AndItsCompletionWithDataStoreAV3TableWithRowLineage() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_ctas";
    created.add(db);
    TableMetadata staged =
        IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate("3").stageCreate().build()));
    assertEquals(3, staged.formatVersion());
    assertEquals(404, status(get(mvc, db, "t")));
    Snapshot first = IrcWrite.append(staged, "/tmp/" + db + "_a.orc");
    TableMetadata withData = IrcWrite.withMain(staged, first);
    List<MetadataUpdate> then =
        withData.changes().stream()
            .filter(
                update ->
                    update instanceof MetadataUpdate.AddSnapshot
                        || update instanceof MetadataUpdate.SetSnapshotRef)
            .collect(Collectors.toList());
    UpdateTableRequest completion = IrcWrite.createCommit(staged, then);
    assertTrue(
        completion.updates().stream()
            .anyMatch(
                update ->
                    update instanceof MetadataUpdate.UpgradeFormatVersion
                        && ((MetadataUpdate.UpgradeFormatVersion) update).formatVersion() == 3));
    TableMetadata created = IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", completion));
    assertEquals(3, created.formatVersion());
    assertEquals(1L, IcebergV3.nextRowId(created));
    assertEquals(Collections.singletonList(SnapshotParser.toJson(first)), snapshotJson(stored(db)));
    assertAnsweredIsStored(db, created);
  }

  // ---------------------------------------------------------------- upgrade and commits

  /**
   * {@code ALTER TABLE ... SET TBLPROPERTIES ('format-version'='3')} through {@code RESTCatalog}:
   * Iceberg's property update turns the reserved property into an upgrade, so the commit is one
   * {@code upgrade-format-version} update, required only to be on the same table.
   */
  @Test
  void theCommitAnAlterTableSendsUpgradesAV2TableAsTheNativeUpdateDoes() throws Exception {
    assumeTrue(IcebergV3.supported());
    createNative(plain("irc_v3_upgrade"));
    String nativeV2 = createNative(plain("irc_v3_upgrade_native"));
    TableMetadata base = IrcWrite.load(mvc, "irc_v3_upgrade", "t");
    assertEquals(2, base.formatVersion());
    Map<String, String> asked = new HashMap<>(base.properties());
    asked.put(FORMAT_VERSION, "3");
    TableMetadata upgraded = base.replaceProperties(asked);
    assertEquals(1, upgraded.changes().size());
    assertTrue(upgraded.changes().get(0) instanceof MetadataUpdate.UpgradeFormatVersion);
    UpdateTableRequest request = IrcWrite.simple(base, upgraded.changes());
    assertEquals(1, request.requirements().size());
    assertTrue(request.requirements().get(0) instanceof UpdateRequirement.AssertTableUUID);

    TableMetadata answered = IrcWrite.parsed(IrcWrite.commit(mvc, "irc_v3_upgrade", "t", request));

    assertEquals(3, answered.formatVersion());
    assertAnsweredIsStored("irc_v3_upgrade", answered);
    assertFalse(answered.properties().containsKey(FORMAT_VERSION));
    assertEquals(200, status(put(mvc, asking(bodyFromGet(nativeV2), "3"))));
    assertSameStored("irc_v3_upgrade_native", "irc_v3_upgrade");
    // Asking again changes nothing and writes nothing; lowering is refused by Iceberg's own rule.
    String location = stored("irc_v3_upgrade").metadataFileLocation();
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_v3_upgrade",
            "t",
            IrcWrite.simple(
                answered, Collections.singletonList(new MetadataUpdate.UpgradeFormatVersion(3)))));
    assertEquals(location, stored("irc_v3_upgrade").metadataFileLocation());
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    "irc_v3_upgrade",
                    "t",
                    IrcWrite.simple(
                        answered,
                        Collections.singletonList(new MetadataUpdate.UpgradeFormatVersion(2)))),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.contains("Cannot downgrade v3 table to v2"), message);
    assertEquals(3, stored("irc_v3_upgrade").formatVersion());
  }

  @Test
  void commitsOnAV3TableAreStoredAndAnsweredExactlyAsCommitted() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_commit";
    TableMetadata base = createV3(db);
    // Two appends: each snapshot carries first-row-id and added-rows, next-row-id advances.
    Snapshot first = IrcWrite.append(base, "/tmp/" + db + "_a.orc");
    TableMetadata one = IrcWrite.withMain(base, first);
    TableMetadata answeredOne = IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", base, one));
    assertEquals(IcebergV3.nextRowId(one), IcebergV3.nextRowId(answeredOne));
    assertEquals(snapshotJson(one), snapshotJson(answeredOne));
    assertAnsweredIsStored(db, answeredOne);
    Snapshot second = IrcWrite.append(answeredOne, "/tmp/" + db + "_b.orc");
    TableMetadata two = IrcWrite.withMain(answeredOne, second);
    TableMetadata answeredTwo = IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", answeredOne, two));
    assertEquals(2L, IcebergV3.nextRowId(answeredTwo));
    assertEquals(snapshotJson(two), snapshotJson(answeredTwo));
    assertEquals(1L, snapshotField(snapshotJson(answeredTwo).get(1), "first-row-id"));
    assertAnsweredIsStored(db, answeredTwo);

    // A schema change that adds a column with defaults: stored and answered as sent.
    Schema withDefault =
        SchemaParser.fromJson(
            addColumn(SchemaParser.toJson(answeredTwo.schema()), "tier", "int", "1", "2"));
    TableMetadata evolved =
        TableMetadata.buildFrom(answeredTwo)
            .setCurrentSchema(withDefault, withDefault.highestFieldId())
            .build();
    TableMetadata answeredEvolved =
        IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", answeredTwo, evolved));
    assertEquals(1, answeredEvolved.schema().findField("tier").initialDefault());
    assertEquals(2, answeredEvolved.schema().findField("tier").writeDefault());
    assertEquals(
        SchemaParser.toJson(evolved.schema()), SchemaParser.toJson(answeredEvolved.schema()));
    assertEquals(2L, IcebergV3.nextRowId(answeredEvolved));
    assertAnsweredIsStored(db, answeredEvolved);

    // Changing that column's initial-default later is refused; its write-default may change.
    Schema changedInitial =
        SchemaParser.fromJson(
            withDefaults(SchemaParser.toJson(answeredEvolved.schema()), "tier", "5", "2"));
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    db,
                    "t",
                    answeredEvolved,
                    TableMetadata.buildFrom(answeredEvolved)
                        .setCurrentSchema(changedInitial, changedInitial.highestFieldId())
                        .build()),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.startsWith("COLUMN_INITIAL_DEFAULT_IMMUTABLE: "), message);
    Schema changedWrite =
        SchemaParser.fromJson(
            withDefaults(SchemaParser.toJson(answeredEvolved.schema()), "tier", "1", "7"));
    TableMetadata answeredWrite =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                db,
                "t",
                answeredEvolved,
                TableMetadata.buildFrom(answeredEvolved)
                    .setCurrentSchema(changedWrite, changedWrite.highestFieldId())
                    .build()));
    assertEquals(7, answeredWrite.schema().findField("tier").writeDefault());
    assertEquals(1, answeredWrite.schema().findField("tier").initialDefault());

    // A rename keeps all of it; a drop and a create of the same name start clean.
    assertEquals(
        204,
        IrcWrite.rename(mvc, TableIdentifier.of(db, "t"), TableIdentifier.of(db, "renamed"))
            .getResponse()
            .getStatus());
    TableMetadata renamed = IrcWrite.load(mvc, db, "renamed");
    assertEquals(3, renamed.formatVersion());
    assertEquals(2L, IcebergV3.nextRowId(renamed));
    assertEquals(snapshotJson(two), snapshotJson(renamed));
    assertEquals(7, renamed.schema().findField("tier").writeDefault());
    assertEquals(204, IrcWrite.drop(mvc, db, "renamed").getResponse().getStatus());
    TableMetadata recreated = IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(null).build()));
    assertEquals(2, recreated.formatVersion());
    assertTrue(recreated.snapshots().isEmpty());
    assertEquals(204, IrcWrite.drop(mvc, db, "t").getResponse().getStatus());
    assertEquals(0L, IcebergV3.nextRowId(createV3(db)));
  }

  /** A replace (RTAS) over the routes keeps a v3 table v3, and upgrades a v2 table that asks. */
  @Test
  void aReplaceKeepsAV3TableV3AndUpgradesAV2TableThatAsksFor3() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_replace";
    createNative(with(asking(plain(db), "3"), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, base.schema(), base.spec(), base.properties());
    Snapshot snapshot = IrcWrite.append(replacement, "/tmp/" + db + "_r.orc");
    TableMetadata replaced =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                db,
                "t",
                IrcWrite.replace(
                    base, replacement, IrcWrite.withMain(replacement, snapshot).changes())));
    assertEquals(3, replaced.formatVersion());
    assertEquals(1L, IcebergV3.nextRowId(replaced));
    assertAnsweredIsStored(db, replaced);

    String v2 = "irc_v3_replace_v2";
    createNative(with(plain(v2), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"));
    TableMetadata baseV2 = IrcWrite.load(mvc, v2, "t");
    Map<String, String> asked = new HashMap<>(baseV2.properties());
    asked.put(FORMAT_VERSION, "3");
    TableMetadata upgrading = IrcWrite.replacement(baseV2, baseV2.schema(), baseV2.spec(), asked);
    assertEquals(3, upgrading.formatVersion());
    TableMetadata upgraded =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc, v2, "t", IrcWrite.replace(baseV2, upgrading, Collections.emptyList())));
    assertEquals(3, upgraded.formatVersion());
    assertAnsweredIsStored(v2, upgraded);
  }

  // ---------------------------------------------------------------- refused, not lost

  private MvcResult commitRaw(String db, String json) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, IrcWrite.table(db, "t"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /** The routes do not store encryption keys, so a commit that carries one is refused. */
  @Test
  void encryptionKeysAndEncryptedManifestListsAreRefused() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_keys";
    TableMetadata base = createV3(db);
    String addKey =
        "{\"requirements\":[],\"updates\":[{\"action\":\"add-encryption-key\",\"encryption-key\":"
            + "{\"key-id\":\"k1\",\"encrypted-key-metadata\":\"AAAA\"}}]}";
    String message = assertIrcError(commitRaw(db, addKey), 400, "BadRequestException").message();
    assertTrue(message.contains("does not store: encryption keys"), message);
    // A snapshot whose manifest list is encrypted with a key (key-id).
    Snapshot snapshot = IrcWrite.append(base, "/tmp/" + db + "_a.orc");
    String snapshotJson = SnapshotParser.toJson(snapshot);
    String withKey =
        snapshotJson.substring(0, snapshotJson.lastIndexOf('}')) + ", \"key-id\" : \"k1\"}";
    String addSnapshot =
        "{\"requirements\":[],\"updates\":[{\"action\":\"add-snapshot\",\"snapshot\":"
            + withKey
            + "}]}";
    message = assertIrcError(commitRaw(db, addSnapshot), 400, "BadRequestException").message();
    assertTrue(message.contains("a snapshot's key-id"), message);
    // Table encryption turned on by property: refused by the table's own rule.
    message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    db,
                    "t",
                    IrcWrite.simple(
                        base,
                        Collections.singletonList(
                            new MetadataUpdate.SetProperties(
                                Collections.singletonMap("encryption.key-id", "k1"))))),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.startsWith("FORMAT_V3_ENCRYPTION_NOT_SUPPORTED: "), message);
    assertTrue(stored(db).snapshots().isEmpty());
    assertFalse(stored(db).properties().containsKey("encryption.key-id"));
  }

  /**
   * A nanosecond timestamp is refused by the create and by a commit, and a variant column on a
   * table that writes ORC (the cluster's default) by the create; FormatV3TypesTest has every type.
   */
  @Test
  void formatV3TypesAreRefusedOverTheRoutes() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_types";
    created.add(db);
    Schema nanos =
        SchemaParser.fromJson(
            addColumn(SchemaParser.toJson(SCHEMA), "at", "timestamptz_ns", null, null));
    String message =
        assertIrcError(
                IrcWrite.create(mvc, db, ircCreate("3").withSchema(nanos).build()),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.startsWith("FORMAT_V3_TYPE_NOT_SUPPORTED: "), message);
    assertTrue(message.contains("work item X5"), message);
    message =
        assertIrcError(
                IrcWrite.create(mvc, db, ircCreate("3").withSchema(nanos).stageCreate().build()),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.startsWith("FORMAT_V3_TYPE_NOT_SUPPORTED: "), message);
    Schema variant =
        SchemaParser.fromJson(addColumn(SchemaParser.toJson(SCHEMA), "v", "variant", null, null));
    message =
        assertIrcError(
                IrcWrite.create(mvc, db, ircCreate("3").withSchema(variant).build()),
                400,
                "BadRequestException")
            .message();
    assertTrue(
        message.startsWith("FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table "), message);
    assertTrue(message.contains("cannot write a variant column to ORC"), message);
    assertEquals(404, status(get(mvc, db, "t")));

    TableMetadata base = createV3(db);
    Schema evolved =
        SchemaParser.fromJson(
            addColumn(SchemaParser.toJson(base.schema()), "at", "timestamp_ns", null, null));
    message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    db,
                    "t",
                    base,
                    TableMetadata.buildFrom(base)
                        .setCurrentSchema(evolved, evolved.highestFieldId())
                        .build()),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.startsWith("FORMAT_V3_TYPE_NOT_SUPPORTED: "), message);
    assertTrue(stored(db).schema().sameSchema(base.schema()));
  }

  /**
   * Register rebuilds a table from the metadata file it is given, and the rebuild computes
   * next-row-id from the snapshots the file still holds, which is too low once a snapshot was
   * expired. So a format v3 metadata file is not registered, here as with the switch off.
   */
  @Test
  void registerOfAFormatV3MetadataFileIsRefused() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = "irc_v3_register";
    TableMetadata base = createV3(db);
    TableMetadata withData =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                db,
                "t",
                base,
                IrcWrite.withMain(base, IrcWrite.append(base, "/tmp/" + db + "_a.orc"))));
    String file = stored(db).metadataFileLocation();
    houseTableRepository.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId("t").build());
    String message =
        assertIrcError(
                IrcHttp.perform(
                    mvc,
                    IrcHttp.request(HttpMethod.POST, IrcRegisterRouteTest.registerPath(db))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IrcRegisterRouteTest.registerBody("t", file))),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.contains("asks for format version 3"), message);
    assertEquals(404, status(get(mvc, db, "t")));
    assertEquals(1L, IcebergV3.nextRowId(withData));
  }
}
