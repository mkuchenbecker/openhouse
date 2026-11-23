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
import com.linkedin.openhouse.tables.formatversion.FormatVersionRule;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
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
 * <p>Requests that do not ask for a version above the cluster's keep the answers they had before
 * format v3 existed in the service, which this class pins too: a native create that asks for 1, 2
 * or something that is no number is created at the cluster's version, and a native update that asks
 * for the table's own version writes a metadata version that changes nothing.
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

  private void expectRefused(ResultActions actions) throws Exception {
    expectRefused(actions, 3);
  }

  private void expectRefused(ResultActions actions, int asked) throws Exception {
    assertEquals(400, status(actions), json(actions));
    String message = message(actions);
    assertTrue(message.startsWith(FormatVersionRule.REFUSED + ": "), message);
    assertTrue(message.contains("asks for format version " + asked), message);
    if (asked == 3) {
      assertTrue(message.contains("format version 3 is not enabled on this cluster"), message);
      assertTrue(message.contains(FormatVersionRule.SWITCH), message);
    }
  }

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

  // ---------------------------------------------------------------- the defect this closes

  /**
   * Before this rule a native table PUT whose properties asked for {@code format-version} 3
   * upgraded the table to format v3 on the Iceberg 1.12 line, with no switch: the property reaches
   * Iceberg's property update, which upgrades. Upgrading a table cannot be undone, so the request
   * is refused, and it changes nothing.
   */
  @Test
  void aNativePutCannotUpgradeATableToFormatV3WithTheSwitchOff() throws Exception {
    assertFalse(clusterProperties.isClusterIcebergFormatV3Enabled());
    String v0 = make(newTable("fvoff_put3", "t"));
    expectRefused(put(mvc, asking(bodyFromGet(v0), "3")), 3);
    assertEquals(2, version("fvoff_put3"));
    assertEquals(v0, json(get(mvc, "fvoff_put3", "t")));
    assertEquals(1, storedFormatVersions(created).size());
  }

  /** A higher version than 3 is refused alike; before, Iceberg's own failure answered 500. */
  @Test
  void aNativePutAskingForAVersionAboveTheTablesIsRefused() throws Exception {
    String v0 = make(newTable("fvoff_put4", "t"));
    for (int asked : new int[] {4, 5}) {
      expectRefused(put(mvc, asking(bodyFromGet(v0), Integer.toString(asked))), asked);
    }
    assertEquals(v0, json(get(mvc, "fvoff_put4", "t")));
  }

  /**
   * A native create that asks for a version above the cluster's is refused and creates nothing.
   * Before, it was created at the cluster's version (201), silently.
   */
  @Test
  void aNativeCreateAskingForAVersionAboveTheClustersIsRefused() throws Exception {
    created.add("fvoff_create");
    for (int asked : new int[] {3, 4, 5}) {
      for (boolean staged : new boolean[] {false, true}) {
        CreateUpdateTableRequestBody body =
            asking(newTable("fvoff_create", "t"), Integer.toString(asked));
        expectRefused(post(mvc, body.toBuilder().stageCreate(staged).build()), asked);
        assertEquals(404, status(get(mvc, "fvoff_create", "t")));
        assertTrue(storedFormatVersions(created).isEmpty());
      }
    }
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
   * the cluster's version. The paths: native create, staged create and its completion, update,
   * snapshots, replace (staged and committed), rename, lock, replica create and update; Iceberg
   * REST create, staged create and its completion, commit, replace, transaction and register.
   */
  @Test
  void noWritePathLeavesATableAboveTheClustersFormatVersion() throws Exception {
    int cluster = clusterProperties.getClusterIcebergFormatVersion();
    assertEquals(2, cluster);

    // Native create and staged create.
    created.add("fvsw_create");
    expectRefused((post(mvc, asking(newTable("fvsw_create", "t"), "3"))));
    expectRefused(
        (post(
            mvc, asking(newTable("fvsw_create", "t"), "3").toBuilder().stageCreate(true).build())));

    // Native completion of a staged create (create with data) that asks for 3.
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
    expectRefused(
        (putSnapshots(
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
                .build())));

    // Native update, snapshots, rename and lock on a table.
    CreateUpdateTableRequestBody rtas = newTable("fvsw_table", "t");
    String v0 =
        make(
            with(
                rtas.toBuilder()
                    .policies(rtas.getPolicies().toBuilder().replication(null).build())
                    .build(),
                CatalogConstants.RTAS_ENABLED_TABLE_PROP,
                "true"));
    expectRefused((put(mvc, asking(bodyFromGet(v0), "3"))));
    Snapshot appended =
        catalog
            .loadTable(TableIdentifier.of("fvsw_table", "t"))
            .newAppend()
            .appendFile(
                createDummyDataFile("/tmp/fvsw_table_a.orc", metadata("fvsw_table", "t").spec()))
            .apply();
    String appendedJson = SnapshotParser.toJson(appended);
    expectRefused(
        (putSnapshots(
            mvc,
            "fvsw_table",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(v0, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(appendedJson))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(appendedJson))
                .createUpdateTableRequestBody(asking(bodyFromGet(v0), "3"))
                .build())));
    // Native replace, staged and committed.
    expectRefused((post(mvc, asking(bodyFromGet(v0), "3").toBuilder().stageReplace(true).build())));
    expectRefused(
        (putSnapshots(
            mvc,
            "fvsw_table",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(v0, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(appendedJson))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(appendedJson))
                .createUpdateTableRequestBody(
                    asking(bodyFromGet(v0), "3").toBuilder().replaceCommit(true).build())
                .build())));
    assertEquals(v0, json(get(mvc, "fvsw_table", "t")));
    // Lock and rename carry no properties of the caller's; they run, and the table stays.
    assertEquals(201, status(lock(mvc, "fvsw_table", "t")));
    assertEquals(204, status(unlock(mvc, "fvsw_table", "t")));
    assertEquals(204, status(rename(mvc, "fvsw_table", "t", "fvsw_table", "renamed")));
    assertEquals(cluster, metadata("fvsw_table", "renamed").formatVersion());
    assertEquals(204, status(rename(mvc, "fvsw_table", "renamed", "fvsw_table", "t")));

    // Native replica create and update (the replication paths).
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
    expectRefused((post(mvc, asking(replica, "3"))));
    assertEquals(201, status(post(mvc, replica)));
    String replicaStored = json(get(mvc, "fvsw_replica", "t"));
    expectRefused((put(mvc, asking(bodyFromGet(replicaStored), "3"))));
    // An update from another cluster, which skips the repository's eligibility checks.
    expectRefused(
        (put(
            mvc,
            with(
                with(
                    asking(bodyFromGet(replicaStored), "3"),
                    "openhouse.clusterId",
                    "another-cluster"),
                "openhouse.tableType",
                "PRIMARY_TABLE"))));

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
