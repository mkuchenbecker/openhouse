package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * The cluster switch for Iceberg format version 3 ({@code cluster.iceberg.format-v3.enabled}) is
 * off by default, and with it off no request, on the native API or the Iceberg REST routes, can
 * leave a table at a format version above the cluster's.
 *
 * <p>With it off, the native API answers as it did before this service knew format v3, which this
 * class pins: a create (and a replace) is stored at the cluster's version whatever it asks for; an
 * update that asks for a version above 2 gets the refusal LinkedIn's Iceberg 1.5.2 gives it, on
 * both Iceberg lines; an update that asks for the table's own version writes a metadata version
 * that changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class FormatVersionSwitchOffTest {
  private static final String FORMAT_VERSION = "format-version";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired ClusterProperties clusterProperties;
  @Autowired HouseTableRepository houseTableRepository;
  @Autowired MeterRegistry meterRegistry;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String db : created) {
      unlock(mvc, db, "t");
      delete(mvc, db, "t");
      delete(mvc, db, "renamed");
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private static CreateUpdateTableRequestBody plain(String db) {
    return newTable(db, "t")
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private static CreateUpdateTableRequestBody asking(
      CreateUpdateTableRequestBody body, String version) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(FORMAT_VERSION, version);
    return body.toBuilder().tableProperties(properties).build();
  }

  private static CreateUpdateTableRequestBody with(
      CreateUpdateTableRequestBody body, String key, String value) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(key, value);
    return body.toBuilder().tableProperties(properties).build();
  }

  private String make(CreateUpdateTableRequestBody body) throws Exception {
    created.add(body.getDatabaseId());
    assertEquals(201, status(post(mvc, body)));
    return json(get(mvc, body.getDatabaseId(), "t"));
  }

  private TableMetadata metadata(String db, String table) {
    return ((BaseTable) catalog.loadTable(TableIdentifier.of(db, table))).operations().current();
  }

  private int version(String db) {
    return metadata(db, "t").formatVersion();
  }

  /**
   * The refusal of an update that asks for a version above 2, as LinkedIn's Iceberg 1.5.2 gives it
   * ({@code TableMetadata.Builder.upgradeFormatVersion}).
   */
  static void expectIcebergRefusal(ResultActions actions, int asked) throws Exception {
    assertEquals(UPGRADE_REFUSAL_STATUS, status(actions), json(actions));
    String refusal =
        "Cannot upgrade table to unsupported format version: v" + asked + " (supported: v2)";
    assertEquals(
        "org.springframework.dao.InvalidDataAccessApiUsageException: "
            + refusal
            + "; nested exception is java.lang.IllegalArgumentException: "
            + refusal,
        message(actions));
  }

  /**
   * The status of Iceberg's refusal of an upgrade it cannot build, on the native API: the refusal
   * is thrown inside the repository, whose exception translation wraps it, so it reaches the
   * generic handler.
   */
  static final int UPGRADE_REFUSAL_STATUS = 500;

  /** Every metadata file under the test's directories, with the format version it holds. */
  private static Map<String, Integer> storedFormatVersions(List<String> dbs) throws Exception {
    Map<String, Integer> versions = new HashMap<>();
    for (String db : dbs) {
      collect(new File("/tmp/" + db), versions);
    }
    return versions;
  }

  private static void collect(File file, Map<String, Integer> out) throws Exception {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          collect(child, out);
        }
      }
    } else if (file.getName().endsWith(".metadata.json")) {
      String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      out.put(file.getPath(), StoredMetadata.MAPPER.readTree(json).get("format-version").asInt());
    }
  }

  // ---------------------------------------------------------------- answers as before format v3

  /**
   * A native table PUT whose properties ask for {@code format-version} 3, 4 or 5 gets the refusal
   * LinkedIn's Iceberg 1.5.2 gives, on both Iceberg lines (the Iceberg 1.12 line could build the
   * version, and would upgrade the table, which cannot be undone), and changes nothing.
   */
  @Test
  void aNativePutAskingForAVersionAboveTwoGetsIcebergsRefusalAsBefore() throws Exception {
    assertFalse(clusterProperties.isClusterIcebergFormatV3Enabled());
    String v0 = make(newTable("fvoff_put3", "t"));
    for (int asked : new int[] {3, 4, 5}) {
      expectIcebergRefusal(put(mvc, asking(bodyFromGet(v0), Integer.toString(asked))), asked);
    }
    assertEquals(2, version("fvoff_put3"));
    assertEquals(v0, json(get(mvc, "fvoff_put3", "t")));
    assertEquals(1, storedFormatVersions(created).size());
  }

  /**
   * A native create, staged or not, that asks for a version above the cluster's is created at the
   * cluster's version, and the property is not kept, as before.
   */
  @Test
  void aNativeCreateAskingForAVersionAboveTheClustersIsCreatedAtTheClustersVersion()
      throws Exception {
    for (int asked : new int[] {3, 4, 5}) {
      String db = "fvoff_create" + asked;
      String stored = make(asking(newTable(db, "t"), Integer.toString(asked)));
      assertEquals(2, version(db));
      Map<String, String> properties = read(stored, "$.tableProperties");
      assertFalse(properties.containsKey(FORMAT_VERSION), properties.toString());

      String staged = NativeCtas.stage(mvc, asking(newTable(db, "s"), Integer.toString(asked)));
      assertEquals(
          2, TableMetadataParser.read(IrcWrite.IO, str(staged, "$.tableLocation")).formatVersion());
    }
  }

  /**
   * A v2 table's commit that sets a column's {@code write-default} is stored with it, as before: no
   * refusal below format v3 runs with the switch off, on create and on update. An initial-default
   * is dropped as it always was.
   */
  @Test
  void aWriteDefaultOnAV2TableIsStoredAsBefore() throws Exception {
    String db = "fvoff_write_default";
    CreateUpdateTableRequestBody body = plain(db);
    String stored =
        make(
            body.toBuilder()
                .schema(FormatV3.addColumn(body.getSchema(), "country", "string", null, "\"US\""))
                .build());
    assertEquals(2, version(db));
    assertEquals(
        "US", String.valueOf(metadata(db, "t").schema().findField("country").writeDefault()));

    ResultActions updated =
        put(
            mvc,
            bodyFromGet(stored)
                .toBuilder()
                .schema(
                    FormatV3.addColumn(
                        str(stored, "$.schema"), "tier", "string", "\"gold\"", "\"silver\""))
                .build());
    assertEquals(200, status(updated), json(updated));
    Schema schema = metadata(db, "t").schema();
    assertEquals("silver", String.valueOf(schema.findField("tier").writeDefault()));
    assertEquals(null, schema.findField("tier").initialDefault());
    assertEquals(2, version(db));
  }

  /**
   * With the switch off the format version check neither runs nor counts for a commit that leaves a
   * v2 table at v2: its pre-commit metric is not emitted for a create or an update.
   */
  @Test
  void theFormatVersionCheckEmitsNoMetricForAV2Commit() throws Exception {
    String db = "fvoff_metric";
    double before = formatVersionChecks();
    String v0 = make(plain(db));
    assertEquals(200, status(put(mvc, with(bodyFromGet(v0), "user.note", "x"))));
    assertEquals(before, formatVersionChecks());
  }

  /** Every count of the format version pre-commit check, whatever its result. */
  private double formatVersionChecks() {
    return meterRegistry.find("openhouse_precommit_check").tag("name", "format-version").counters()
        .stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  /**
   * An Iceberg REST commit, or a transaction, whose changes set the {@code format-version} property
   * above the table's is refused with 400 {@code FORMAT_VERSION_REFUSED}, as on the branch before:
   * these routes have no answer of LinkedIn main to keep, and a server failure would tell an
   * Iceberg client that the commit's state is unknown. The native PUT keeps main's answer.
   */
  @Test
  void anIcebergRestCommitSettingTheFormatVersionPropertyIsRefusedWith400() throws Exception {
    String db = "fvoff_irc_property";
    make(plain(db));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    UpdateTableRequest commit =
        IrcWrite.simple(
            base,
            Collections.singletonList(
                new MetadataUpdate.SetProperties(Collections.singletonMap(FORMAT_VERSION, "3"))));
    for (org.springframework.test.web.servlet.MvcResult answer :
        Arrays.asList(
            IrcWrite.commit(mvc, db, "t", commit),
            IrcWrite.transaction(mvc, IrcWrite.change(db, "t", commit)))) {
      String refusal = assertIrcError(answer, 400, "BadRequestException").message();
      assertTrue(refusal.startsWith("FORMAT_VERSION_REFUSED: "), refusal);
      assertTrue(refusal.contains("asks for format version 3"), refusal);
    }
    assertEquals(2, version(db));
  }

  // ---------------------------------------------------------------- unchanged answers

  /** A create that asks for the cluster's version, a lower one or no number: as before. */
  @Test
  void aNativeCreateAskingForNoHigherVersionIsCreatedAtTheClustersVersion() throws Exception {
    for (String asked : new String[] {"1", "2", "x"}) {
      String db = "fvoff_low_" + asked;
      String stored = make(asking(newTable(db, "t"), asked));
      assertEquals(2, version(db));
      Map<String, String> properties = read(stored, "$.tableProperties");
      assertFalse(properties.containsKey(FORMAT_VERSION), properties.toString());
    }
  }

  /**
   * An update that asks for the table's own version writes a metadata version that changes nothing;
   * one that asks for a lower version, or for no number, fails in Iceberg and answers 500. All as
   * before.
   */
  @Test
  void aNativePutAskingForNoHigherVersionAnswersAsBefore() throws Exception {
    String v0 = make(newTable("fvoff_same", "t"));
    ResultActions same = put(mvc, asking(bodyFromGet(v0), "2"));
    assertEquals(200, status(same), json(same));
    String v1 = json(get(mvc, "fvoff_same", "t"));
    assertEquals(2, version("fvoff_same"));
    assertEquals(str(v0, "$.tableLocation").replace("file:", ""), str(v1, "$.tableVersion"));
    ResultActions lower = put(mvc, asking(bodyFromGet(v1), "1"));
    assertEquals(500, status(lower), json(lower));
    assertTrue(message(lower).contains("Cannot downgrade v2 table to v1"), message(lower));
    ResultActions noNumber = put(mvc, asking(bodyFromGet(v1), "x"));
    assertEquals(500, status(noNumber), json(noNumber));
    assertEquals(v1, json(get(mvc, "fvoff_same", "t")));
  }

  // ---------------------------------------------------------------- the sweep

  /**
   * Every write path of both APIs is asked for format version 3, each in the way that path could
   * carry it, and afterwards no table and no metadata file under the tables' directories is above
   * the cluster's version. The native paths answer as before format v3: native create, staged
   * create and its completion, replace (staged and committed) and replica create are stored at the
   * cluster's version; update, snapshots, and replica update get Iceberg's refusal; rename and lock
   * carry no format version. The Iceberg REST paths: create, staged create and its completion,
   * commit, replace, transaction and register.
   */
  @Test
  void noWritePathLeavesATableAboveTheClustersFormatVersion() throws Exception {
    int cluster = clusterProperties.getClusterIcebergFormatVersion();
    assertEquals(2, cluster);

    // Native create and staged create: stored at the cluster's version.
    created.add("fvsw_create");
    assertEquals(201, status(post(mvc, asking(newTable("fvsw_create", "t"), "3"))));
    assertEquals(cluster, version("fvsw_create"));
    NativeCtas.stage(mvc, asking(newTable("fvsw_create", "s"), "3"));

    // Native completion of a staged create (create with data) that asks for 3: created at the
    // cluster's version.
    created.add("fvsw_ctas");
    String staged = NativeCtas.stage(mvc, newTable("fvsw_ctas", "t"));
    TableMetadata stagedMetadata =
        TableMetadataParser.read(IrcWrite.IO, str(staged, "$.tableLocation"));
    String first =
        SnapshotParser.toJson(
            NativeCtas.table(stagedMetadata, IrcWrite.IO)
                .newAppend()
                .appendFile(createDummyDataFile("/tmp/fvsw_ctas_a.orc", stagedMetadata.spec()))
                .apply());
    ResultActions completion =
        putSnapshots(
            mvc,
            "fvsw_ctas",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(INITIAL_TABLE_VERSION)
                .jsonSnapshots(Collections.singletonList(first))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(first))
                .createUpdateTableRequestBody(
                    asking(bodyFromGet(staged), "3")
                        .toBuilder()
                        .baseTableVersion(INITIAL_TABLE_VERSION)
                        .build())
                .build());
    assertEquals(201, status(completion), json(completion));
    assertEquals(cluster, version("fvsw_ctas"));

    // Native update and snapshots on a table: Iceberg's refusal.
    CreateUpdateTableRequestBody rtas = newTable("fvsw_table", "t");
    String v0 =
        make(
            with(
                rtas.toBuilder()
                    .policies(rtas.getPolicies().toBuilder().replication(null).build())
                    .build(),
                CatalogConstants.RTAS_ENABLED_TABLE_PROP,
                "true"));
    expectIcebergRefusal(put(mvc, asking(bodyFromGet(v0), "3")), 3);
    Snapshot appended =
        catalog
            .loadTable(TableIdentifier.of("fvsw_table", "t"))
            .newAppend()
            .appendFile(
                createDummyDataFile("/tmp/fvsw_table_a.orc", metadata("fvsw_table", "t").spec()))
            .apply();
    String appendedJson = SnapshotParser.toJson(appended);
    expectIcebergRefusal(
        putSnapshots(
            mvc,
            "fvsw_table",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(v0, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(appendedJson))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(appendedJson))
                .createUpdateTableRequestBody(asking(bodyFromGet(v0), "3"))
                .build()),
        3);
    assertEquals(v0, json(get(mvc, "fvsw_table", "t")));
    // Native replace, staged and committed: the table is replaced at the cluster's version.
    ResultActions stagedReplace =
        post(mvc, asking(bodyFromGet(v0), "3").toBuilder().stageReplace(true).build());
    assertTrue(status(stagedReplace) < 300, json(stagedReplace));
    ResultActions replaced =
        putSnapshots(
            mvc,
            "fvsw_table",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(v0, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(appendedJson))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(appendedJson))
                .createUpdateTableRequestBody(
                    asking(bodyFromGet(v0), "3").toBuilder().replaceCommit(true).build())
                .build());
    assertTrue(status(replaced) < 300, json(replaced));
    assertEquals(cluster, version("fvsw_table"));
    // Lock and rename carry no properties of the caller's; they run, and the table stays.
    assertEquals(201, status(lock(mvc, "fvsw_table", "t")));
    assertEquals(204, status(unlock(mvc, "fvsw_table", "t")));
    assertEquals(204, status(rename(mvc, "fvsw_table", "t", "fvsw_table", "renamed")));
    assertEquals(cluster, metadata("fvsw_table", "renamed").formatVersion());
    assertEquals(204, status(rename(mvc, "fvsw_table", "renamed", "fvsw_table", "t")));

    // Native replica create (stored at the cluster's version) and update (Iceberg's refusal).
    created.add("fvsw_replica");
    String uuid = "cee3c6a3-a824-443a-832a-d4a1271e1e3f";
    Map<String, String> replicaProperties =
        new HashMap<>(newTable("fvsw_replica", "t").getTableProperties());
    replicaProperties.put("openhouse.tableUUID", uuid);
    replicaProperties.put("openhouse.databaseId", "fvsw_replica");
    replicaProperties.put("openhouse.tableId", "t");
    replicaProperties.put("openhouse.tableType", "REPLICA_TABLE");
    replicaProperties.put(
        "openhouse.tableLocation", "/tmp/unused/fvsw_replica/t-" + uuid + "/metadata.json");
    CreateUpdateTableRequestBody replica =
        newTable("fvsw_replica", "t")
            .toBuilder()
            .tableType(TableType.REPLICA_TABLE)
            .tableProperties(replicaProperties)
            .build();
    assertEquals(201, status(post(mvc, asking(replica, "3"))));
    assertEquals(cluster, version("fvsw_replica"));
    String replicaStored = json(get(mvc, "fvsw_replica", "t"));
    expectIcebergRefusal(put(mvc, asking(bodyFromGet(replicaStored), "3")), 3);
    // An update from another cluster, which skips the repository's eligibility checks.
    expectIcebergRefusal(
        put(
            mvc,
            with(
                with(
                    asking(bodyFromGet(replicaStored), "3"),
                    "openhouse.clusterId",
                    "another-cluster"),
                "openhouse.tableType",
                "PRIMARY_TABLE")),
        3);

    // Iceberg REST create and staged create.
    created.add("fvsw_irc");
    Schema schema = metadata("fvsw_table", "t").schema();
    for (boolean stage : new boolean[] {false, true}) {
      CreateTableRequest.Builder builder =
          CreateTableRequest.builder()
              .withName("t")
              .withSchema(schema)
              .setProperties(Collections.singletonMap(FORMAT_VERSION, "3"));
      assertIrcError(
          IrcWrite.create(mvc, "fvsw_irc", stage ? builder.stageCreate().build() : builder.build()),
          400,
          "BadRequestException");
    }
    if (IcebergV3.supported()) {
      // The completion of a staged create whose changes upgrade to 3, as RESTSessionCatalog
      // spells a create of v3 metadata.
      TableMetadata stagedIrc =
          IrcWrite.parsed(
              IrcWrite.create(
                  mvc,
                  "fvsw_irc",
                  CreateTableRequest.builder()
                      .withName("t")
                      .withSchema(schema)
                      .stageCreate()
                      .build()));
      assertIrcError(
          IrcWrite.commit(
              mvc,
              "fvsw_irc",
              "t",
              IrcWrite.createCommit(stagedIrc.upgradeToFormatVersion(3), Collections.emptyList())),
          400,
          "BadRequestException");
      assertEquals(404, status(get(mvc, "fvsw_irc", "t")));

      // Iceberg REST commit, replace and transaction that upgrade.
      TableMetadata base = IrcWrite.load(mvc, "fvsw_table", "t");
      List<MetadataUpdate> upgrade =
          Collections.singletonList(new MetadataUpdate.UpgradeFormatVersion(3));
      UpdateTableRequest commit = IrcWrite.simple(base, upgrade);
      String refusal =
          assertIrcError(
                  IrcWrite.commit(mvc, "fvsw_table", "t", commit), 400, "BadRequestException")
              .message();
      assertTrue(
          refusal.contains("changes what an OpenHouse table commit does not store: format version"),
          refusal);
      assertIrcError(
          IrcWrite.transaction(mvc, IrcWrite.change("fvsw_table", "t", commit)),
          400,
          "BadRequestException");
      Map<String, String> replacementProperties = new HashMap<>(base.properties());
      replacementProperties.put(FORMAT_VERSION, "3");
      assertIrcError(
          IrcWrite.commit(
              mvc,
              "fvsw_table",
              "t",
              IrcWrite.replace(
                  base,
                  IrcWrite.replacement(base, base.schema(), base.spec(), replacementProperties),
                  Collections.emptyList())),
          400,
          "BadRequestException");

      // Iceberg REST register of a format v3 metadata file in a table's own directory.
      String db = "fvsw_register";
      make(plain(db));
      TableMetadata toRegister = metadata(db, "t");
      String v3File = toRegister.location() + "/00009-registered-v3.metadata.json";
      TableMetadataParser.write(
          toRegister.upgradeToFormatVersion(3), IrcWrite.IO.newOutputFile(v3File));
      houseTableRepository.deleteById(
          HouseTablePrimaryKey.builder().databaseId(db).tableId("t").build());
      String registration =
          assertIrcError(
                  IrcHttp.perform(
                      mvc,
                      IrcHttp.request(HttpMethod.POST, IrcRegisterRouteTest.registerPath(db))
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(IrcRegisterRouteTest.registerBody("t", v3File))),
                  400,
                  "BadRequestException")
              .message();
      assertTrue(registration.contains("asks for format version 3"), registration);
      assertEquals(404, status(get(mvc, db, "t")));
      // The file this test wrote by hand is the only v3 file anywhere; the server wrote none.
      assertTrue(new File(v3File).delete());
    }

    // No table is above the cluster's version, and neither is any metadata file left behind.
    for (String[] table :
        Arrays.asList(new String[] {"fvsw_table", "t"}, new String[] {"fvsw_replica", "t"})) {
      assertEquals(cluster, metadata(table[0], table[1]).formatVersion(), table[0]);
    }
    Map<String, Integer> stored = storedFormatVersions(created);
    assertFalse(stored.isEmpty());
    stored.forEach((file, version) -> assertTrue(version <= cluster, file + " is v" + version));
  }
}
