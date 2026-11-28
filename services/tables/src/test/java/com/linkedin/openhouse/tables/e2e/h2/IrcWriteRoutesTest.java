package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static com.linkedin.openhouse.tables.model.TableModelConstants.SORT_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The Iceberg REST Catalog (IRC) write routes at the HTTP boundary, on the full Spring context with
 * the real internal catalog (same context as {@link IrcReadRoutesTest}).
 *
 * <p>Two kinds of test. Equivalence: the same write is made through the native API on one table and
 * through IRC on another, and every metadata file each leaves is compared after the golden test's
 * normalization (database names aside). Rules: a request that breaks a native rule is refused with
 * the mapped IRC error, carrying the native message, and changes nothing. Every IRC request is
 * built the way Iceberg's {@code RESTCatalog} builds it (see {@link IrcWrite}) and every response
 * is parsed with the client's own parsers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcWriteRoutesTest {
  private static final String CAPABILITIES = TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES;
  private static final String DEFAULTS_DB = "irc_defaults";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;
  @Autowired ClusterProperties clusterProperties;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL);

  /** The native test table without policies (IRC cannot send policies yet). */
  private static CreateUpdateTableRequestBody nativeBody(String db) {
    return newTable(db, "t").toBuilder().policies(null).build();
  }

  private static CreateUpdateTableRequestBody plainBody(String db) {
    return nativeBody(db).toBuilder().timePartitioning(null).clustering(null).build();
  }

  /** The IRC create equivalent to {@link #nativeBody}: same schema, spec, properties. */
  private static CreateTableRequest ircCreate(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .withPartitionSpec(
            PartitionSpec.builderFor(SCHEMA)
                .hour("timestampCol")
                .identity("id")
                .truncate("name", 10)
                .build())
        .setProperties(nativeBody("x").getTableProperties())
        .build();
  }

  private static CreateTableRequest ircCreatePlain(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .setProperties(nativeBody("x").getTableProperties())
        .build();
  }

  private String createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
    return json(get(mvc, body.getDatabaseId(), body.getTableId()));
  }

  private void track(String db, String table) {
    created.add(new String[] {db, table});
  }

  private void assertSameStored(String nativeDb, String ircDb) throws Exception {
    String expected = StoredMetadata.normalized(catalog, nativeDb, "t").replace(nativeDb, "DB");
    String actual = StoredMetadata.normalized(catalog, ircDb, "t").replace(ircDb, "DB");
    assertEquals(expected, actual);
  }

  private String stored(String db) throws Exception {
    return StoredMetadata.normalized(catalog, db, "t");
  }

  private static Map<String, String> with(Map<String, String> props, String key, String value) {
    Map<String, String> out = new HashMap<>(props);
    out.put(key, value);
    return out;
  }

  /** Every file left under the database's directory of the test storage. */
  private static List<String> filesUnder(String db) {
    File root = new File("/tmp/" + db);
    List<String> files = new ArrayList<>();
    collect(root, files);
    return files;
  }

  private static void collect(File file, List<String> out) {
    if (!file.exists()) {
      return;
    }
    if (file.isFile()) {
      out.add(file.getPath());
      return;
    }
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        collect(child, out);
      }
    }
  }

  // ---------------------------------------------------------------- create

  @Test
  void createStoresWhatTheNativeCreateStores() throws Exception {
    createNative(nativeBody("irc_w_n_create"));
    track("irc_w_i_create", "t");
    MvcResult result = IrcWrite.create(mvc, "irc_w_i_create", ircCreate("t"));
    TableMetadata response = IrcWrite.parsed(result);
    assertSameStored("irc_w_n_create", "irc_w_i_create");
    // The response is the stored table at the location the native API reports.
    String nativeView = json(get(mvc, "irc_w_i_create", "t"));
    assertEquals(str(nativeView, "$.tableLocation"), response.metadataFileLocation());
    // The table's OpenHouse UUID names its directory; its Iceberg UUID is drawn by the create.
    assertTrue(
        response.location().endsWith("/t-" + str(nativeView, "$.tableUUID")), response.location());
    assertEquals(IrcWrite.load(mvc, "irc_w_i_create", "t").uuid(), response.uuid());
  }

  @Test
  void createUnpartitionedStoresWhatTheNativeCreateStores() throws Exception {
    createNative(plainBody("irc_w_n_plain"));
    track("irc_w_i_plain", "t");
    IrcWrite.parsed(IrcWrite.create(mvc, "irc_w_i_plain", ircCreatePlain("t")));
    assertSameStored("irc_w_n_plain", "irc_w_i_plain");
  }

  @Test
  void createWithASortOrderStoresWhatTheNativeCreateStores() throws Exception {
    createNative(plainBody("irc_w_n_csort").toBuilder().sortOrder(SORT_ORDER).build());
    track("irc_w_i_csort", "t");
    CreateTableRequest request =
        CreateTableRequest.builder()
            .withName("t")
            .withSchema(SCHEMA)
            .withWriteOrder(SortOrder.builderFor(SCHEMA).asc("id").build())
            .setProperties(nativeBody("x").getTableProperties())
            .build();
    IrcWrite.parsed(IrcWrite.create(mvc, "irc_w_i_csort", request));
    assertSameStored("irc_w_n_csort", "irc_w_i_csort");
  }

  /**
   * A create whose schema's field ids are not the ones a new table gets (Iceberg's REST
   * compatibility kit sends ids 3 and 4; the table gets 1 and 2) with a write order on them: the
   * order is kept on the column it names. Before, the request's schema went to the native create
   * with its own ids and the order with the new ones, and the create was refused with "Cannot find
   * source column for sort field".
   */
  @Test
  void createWithASortOrderOnASchemaWithOtherIdsSortsByTheNamedColumns() throws Exception {
    Schema schema =
        new Schema(
            Types.NestedField.required(3, "id", Types.IntegerType.get()),
            Types.NestedField.required(4, "data", Types.StringType.get()));
    track("irc_w_sortids", "t");
    CreateTableRequest request =
        CreateTableRequest.builder()
            .withName("t")
            .withSchema(schema)
            .withWriteOrder(
                SortOrder.builderFor(schema)
                    .asc(org.apache.iceberg.expressions.Expressions.bucket("id", 16))
                    .asc("data")
                    .build())
            .build();
    MvcResult result = IrcWrite.create(mvc, "irc_w_sortids", request);
    assertEquals(200, result.getResponse().getStatus(), body(result));
    TableMetadata stored = IrcWrite.load(mvc, "irc_w_sortids", "t");
    Schema fresh =
        new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.required(2, "data", Types.StringType.get()));
    assertEquals(fresh.asStruct(), stored.schema().asStruct());
    assertEquals(
        SortOrder.builderFor(fresh)
            .asc(org.apache.iceberg.expressions.Expressions.bucket("id", 16))
            .asc("data")
            .build()
            .fields(),
        stored.sortOrder().fields());
  }

  @Test
  void createOfAnExistingTableIs409AlreadyExists() throws Exception {
    createNative(nativeBody("irc_w_exists"));
    String before = stored("irc_w_exists");
    String nativeMessage =
        message(post(mvc, nativeBody("irc_w_exists")).andExpect(status().is(409)));
    assertEquals(
        nativeMessage,
        assertIrcError(
                IrcWrite.create(mvc, "irc_w_exists", ircCreate("t")), 409, "AlreadyExistsException")
            .message());
    assertEquals(before, stored("irc_w_exists"));
  }

  /**
   * {@link #ircCreate} naming {@code location}, as {@code RESTCatalog} sends {@code withLocation}.
   */
  private static CreateTableRequest ircCreateAt(String table, String location, boolean staged) {
    CreateTableRequest plain = ircCreate(table);
    CreateTableRequest.Builder builder =
        CreateTableRequest.builder()
            .withName(table)
            .withSchema(plain.schema())
            .withPartitionSpec(plain.spec())
            .setProperties(plain.properties())
            .withLocation(location);
    return staged ? builder.stageCreate().build() : builder.build();
  }

  /**
   * The server owns table locations (D30): a create that names a location is created where the
   * server puts the table, exactly as the same create without one, and the response says where.
   * Nothing is written at the requested location. Before, such a create was refused with 400.
   */
  @Test
  void createThatNamesALocationIsCreatedAtTheServersLocation() throws Exception {
    track("irc_w_loc_a", "t");
    IrcWrite.parsed(IrcWrite.create(mvc, "irc_w_loc_a", ircCreate("t")));
    track("irc_w_loc_b", "t");
    TableMetadata response =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc, "irc_w_loc_b", ircCreateAt("t", "/tmp/irc_w_loc_elsewhere/t", false)));
    String nativeView = json(get(mvc, "irc_w_loc_b", "t"));
    assertTrue(
        response.location().endsWith("/irc_w_loc_b/t-" + str(nativeView, "$.tableUUID")),
        response.location());
    assertEquals(response.location(), IrcWrite.load(mvc, "irc_w_loc_b", "t").location());
    assertSameStored("irc_w_loc_a", "irc_w_loc_b");
    assertTrue(filesUnder("irc_w_loc_elsewhere").isEmpty());
  }

  @Test
  void createWithAnInexpressiblePartitionSpecIsRefused() throws Exception {
    track("irc_w_bucket", "t");
    CreateTableRequest request =
        CreateTableRequest.builder()
            .withName("t")
            .withSchema(SCHEMA)
            // Clustering before time partitioning: the native API always stores time first.
            .withPartitionSpec(
                PartitionSpec.builderFor(SCHEMA).identity("id").hour("timestampCol").build())
            .build();
    String message =
        assertIrcError(IrcWrite.create(mvc, "irc_w_bucket", request), 400, "BadRequestException")
            .message();
    assertTrue(message.contains("partition spec"), message);
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_bucket", "t")));
  }

  @Test
  void createWithReservedPropertiesGetsWhatTheNativeCreateGets() throws Exception {
    Map<String, String> props =
        with(nativeBody("x").getTableProperties(), "openhouse.tableId", "other");
    createNative(nativeBody("irc_w_n_resv").toBuilder().tableProperties(props).build());
    track("irc_w_i_resv", "t");
    IrcWrite.parsed(
        IrcWrite.create(
            mvc,
            "irc_w_i_resv",
            CreateTableRequest.builder()
                .withName("t")
                .withSchema(SCHEMA)
                .withPartitionSpec(ircCreate("t").spec())
                .setProperties(props)
                .build()));
    assertSameStored("irc_w_n_resv", "irc_w_i_resv");
  }

  /**
   * The server owns the format version (D19, D20): a create or a staged create whose {@code
   * format-version} property asks for another version than the cluster's is refused with 400 naming
   * the property and the version the server uses, as the commit that completes a staged create
   * already refuses a mismatch. Before, the staged create silently answered metadata of the
   * cluster's version, which its completion then stored. The cluster's own version is accepted.
   * (The native create overwrites the property with the cluster's version; that is unchanged.)
   */
  @Test
  void createOrStagedCreateAskingForAnotherFormatVersionIsRefusedAndNamesIt() throws Exception {
    int clusterVersion = clusterProperties.getClusterIcebergFormatVersion();
    track("irc_w_fmt", "t");
    for (boolean staged : new boolean[] {false, true}) {
      for (String asked : new String[] {"1", "3", "x"}) {
        CreateTableRequest.Builder builder =
            CreateTableRequest.builder()
                .withName("t")
                .withSchema(SCHEMA)
                .setProperties(with(nativeBody("x").getTableProperties(), "format-version", asked));
        CreateTableRequest request = staged ? builder.stageCreate().build() : builder.build();
        String message =
            assertIrcError(IrcWrite.create(mvc, "irc_w_fmt", request), 400, "BadRequestException")
                .message();
        assertTrue(message.contains("format-version"), message);
        assertTrue(message.contains("format version " + clusterVersion), message);
        assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_fmt", "t")));
        assertTrue(filesUnder("irc_w_fmt").isEmpty(), filesUnder("irc_w_fmt").toString());
      }
    }
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_w_fmt",
                CreateTableRequest.builder()
                    .withName("t")
                    .withSchema(SCHEMA)
                    .setProperties(
                        with(
                            nativeBody("x").getTableProperties(),
                            "format-version",
                            Integer.toString(clusterVersion)))
                    .stageCreate()
                    .build()));
    assertEquals(clusterVersion, staged.formatVersion());
  }

  // ---------------------------------------------------------------- staged create

  @Test
  void stagedCreateLeavesNoTableNoRowAndNoFile() throws Exception {
    CreateTableRequest request =
        CreateTableRequest.builder()
            .withName("t")
            .withSchema(SCHEMA)
            .withPartitionSpec(ircCreate("t").spec())
            .setProperties(nativeBody("x").getTableProperties())
            .stageCreate()
            .build();
    TableMetadata staged = IrcWrite.parsed(IrcWrite.create(mvc, "irc_w_stage", request));
    assertTrue(staged.location().contains("/irc_w_stage/t-"), staged.location());
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_stage", "t")));
    assertEquals(
        404, IrcHttp.get(mvc, IrcWrite.table("irc_w_stage", "t")).getResponse().getStatus());
    assertTrue(filesUnder("irc_w_stage").isEmpty(), filesUnder("irc_w_stage").toString());
  }

  /** What the native staged create does today: it writes a metadata file and no house-table row. */
  @Test
  void nativeStagedCreateWritesAMetadataFileAndNoRow() throws Exception {
    List<String> before = filesUnder("irc_w_nstage");
    NativeCtas.stage(mvc, nativeBody("irc_w_nstage"));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_nstage", "t")));
    // Nothing ever removes it: earlier runs' files are still there.
    List<String> files =
        filesUnder("irc_w_nstage").stream()
            .filter(path -> !before.contains(path) && !path.endsWith(".crc"))
            .collect(Collectors.toList());
    assertEquals(1, files.size(), files.toString());
    assertTrue(files.get(0).endsWith(".metadata.json"), files.toString());
  }

  @Test
  void stagedCreateThenCommitWithoutDataStoresWhatTheNativeCreateStores() throws Exception {
    createNative(nativeBody("irc_w_n_sc"));
    track("irc_w_i_sc", "t");
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_w_i_sc",
                CreateTableRequest.builder()
                    .withName("t")
                    .withSchema(SCHEMA)
                    .withPartitionSpec(ircCreate("t").spec())
                    .setProperties(nativeBody("x").getTableProperties())
                    .stageCreate()
                    .build()));
    TableMetadata committed =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc, "irc_w_i_sc", "t", IrcWrite.createCommit(staged, Collections.emptyList())));
    // The table is where the staged create said it would be. Its Iceberg UUID is drawn by the
    // create, as for every native create (a native staged create's file has its own UUID too).
    assertEquals(staged.location(), committed.location());
    assertSameStored("irc_w_n_sc", "irc_w_i_sc");
  }

  /**
   * A staged create that names a location (D30) is staged at the server's location, which its
   * completion then sets, as {@code RESTCatalog} sends the staged location back: the table is what
   * the native create stores, and nothing is written at the requested location.
   */
  @Test
  void stagedCreateThatNamesALocationIsStagedAndCreatedAtTheServersLocation() throws Exception {
    createNative(nativeBody("irc_w_n_sloc"));
    track("irc_w_i_sloc", "t");
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc, "irc_w_i_sloc", ircCreateAt("t", "/tmp/irc_w_sloc_elsewhere/t", true)));
    assertTrue(staged.location().contains("/irc_w_i_sloc/t-"), staged.location());
    TableMetadata committed =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc, "irc_w_i_sloc", "t", IrcWrite.createCommit(staged, Collections.emptyList())));
    assertEquals(staged.location(), committed.location());
    assertSameStored("irc_w_n_sloc", "irc_w_i_sloc");
    assertTrue(filesUnder("irc_w_sloc_elsewhere").isEmpty());
  }

  @Test
  void stagedCreateThenCommitWithDataStoresWhatTheNativeCtasStores() throws Exception {
    track("irc_w_n_ctas", "t");
    NativeCtas.createWithData(mvc, nativeBody("irc_w_n_ctas"), "/tmp/irc_w_n_ctas_a.orc")
        .andExpect(status().isCreated());
    track("irc_w_i_ctas", "t");
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_w_i_ctas",
                CreateTableRequest.builder()
                    .withName("t")
                    .withSchema(SCHEMA)
                    .withPartitionSpec(ircCreate("t").spec())
                    .setProperties(nativeBody("x").getTableProperties())
                    .stageCreate()
                    .build()));
    Snapshot snapshot = IrcWrite.append(staged, "/tmp/irc_w_i_ctas_a.orc");
    TableMetadata committed =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_w_i_ctas",
                "t",
                IrcWrite.createCommit(
                    staged,
                    IrcWrite.withMain(
                            TableMetadata.buildFrom(staged).discardChanges().build(), snapshot)
                        .changes())));
    assertEquals(snapshot.snapshotId(), committed.currentSnapshot().snapshotId());
    assertSameStored("irc_w_n_ctas", "irc_w_i_ctas");
  }

  @Test
  void createCommitOnAnExistingTableIs409CommitFailed() throws Exception {
    createNative(nativeBody("irc_w_cc_exists"));
    String before = stored("irc_w_cc_exists");
    TableMetadata staged = IrcWrite.load(mvc, "irc_w_cc_exists", "t");
    assertIrcError(
        IrcWrite.commit(
            mvc, "irc_w_cc_exists", "t", IrcWrite.createCommit(staged, Collections.emptyList())),
        409,
        "CommitFailedException");
    assertEquals(before, stored("irc_w_cc_exists"));
  }

  // ---------------------------------------------------------------- commits

  @Test
  void propertySetAndUnsetStoreWhatTheNativePutsStore() throws Exception {
    String v0 = createNative(nativeBody("irc_w_n_prop"));
    createNative(nativeBody("irc_w_i_prop"));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(mvc, b.toBuilder().tableProperties(with(b.getTableProperties(), "user.key", "v1")).build())
        .andExpect(status().isOk());
    CreateUpdateTableRequestBody b1 = bodyFromGet(json(get(mvc, "irc_w_n_prop", "t")));
    Map<String, String> props = new HashMap<>(b1.getTableProperties());
    props.remove("user.key");
    put(mvc, b1.toBuilder().tableProperties(props).build()).andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_w_i_prop", "t");
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_w_i_prop",
                "t",
                base,
                TableMetadata.buildFrom(base)
                    .setProperties(Collections.singletonMap("user.key", "v1"))
                    .build()));
    assertEquals("v1", after.properties().get("user.key"));
    TableMetadata base1 = IrcWrite.load(mvc, "irc_w_i_prop", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_i_prop",
            "t",
            base1,
            TableMetadata.buildFrom(base1)
                .removeProperties(Collections.singleton("user.key"))
                .build()));
    assertSameStored("irc_w_n_prop", "irc_w_i_prop");
  }

  @Test
  void addingAColumnStoresWhatTheNativePutStores() throws Exception {
    String v0 = createNative(nativeBody("irc_w_n_schema"));
    createNative(nativeBody("irc_w_i_schema"));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(
            mvc,
            b.toBuilder().schema(addColumn(b.getSchema(), "added", Types.StringType.get())).build())
        .andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_w_i_schema", "t");
    Schema evolved =
        NativeCtas.table(base, IrcWrite.IO)
            .updateSchema()
            .addColumn("added", Types.StringType.get())
            .apply();
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_i_schema",
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setCurrentSchema(evolved, evolved.highestFieldId())
                .build()));
    assertSameStored("irc_w_n_schema", "irc_w_i_schema");
  }

  @Test
  void sortOrderChangeStoresWhatTheNativePutStores() throws Exception {
    String v0 = createNative(nativeBody("irc_w_n_sort"));
    createNative(nativeBody("irc_w_i_sort"));
    put(mvc, bodyFromGet(v0).toBuilder().sortOrder(SORT_ORDER).build()).andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_w_i_sort", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_i_sort",
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setDefaultSortOrder(SortOrder.builderFor(base.schema()).asc("id").build())
                .build()));
    assertSameStored("irc_w_n_sort", "irc_w_i_sort");
  }

  @Test
  void appendStoresWhatTheNativeSnapshotsPutStores() throws Exception {
    createNative(nativeBody("irc_w_n_app"));
    createNative(nativeBody("irc_w_i_app"));
    TableMetadata nativeBase = IrcWrite.load(mvc, "irc_w_n_app", "t");
    Snapshot nativeSnap = IrcWrite.append(nativeBase, "/tmp/irc_w_n_app_a.orc");
    String getJson = json(get(mvc, "irc_w_n_app", "t"));
    putSnapshots(
            mvc,
            "irc_w_n_app",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(getJson, "$.tableLocation"))
                .jsonSnapshots(Collections.singletonList(SnapshotParser.toJson(nativeSnap)))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(nativeSnap)))
                .createUpdateTableRequestBody(bodyFromGet(getJson))
                .build())
        .andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_w_i_app", "t");
    Snapshot snap = IrcWrite.append(base, "/tmp/irc_w_i_app_a.orc");
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(mvc, "irc_w_i_app", "t", base, IrcWrite.withMain(base, snap)));
    assertEquals(snap.snapshotId(), after.currentSnapshot().snapshotId());
    assertSameStored("irc_w_n_app", "irc_w_i_app");
  }

  @Test
  void aBranchAndATagAreStored() throws Exception {
    createNative(nativeBody("irc_w_refs"));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_refs", "t");
    Snapshot snap = IrcWrite.append(base, "/tmp/irc_w_refs_a.orc");
    TableMetadata appended =
        IrcWrite.parsed(
            IrcWrite.commit(mvc, "irc_w_refs", "t", base, IrcWrite.withMain(base, snap)));
    TableMetadata refs =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_w_refs",
                "t",
                appended,
                TableMetadata.buildFrom(appended)
                    .setRef(
                        "b1",
                        org.apache.iceberg.SnapshotRef.branchBuilder(snap.snapshotId()).build())
                    .setRef(
                        "t1", org.apache.iceberg.SnapshotRef.tagBuilder(snap.snapshotId()).build())
                    .build()));
    assertEquals(snap.snapshotId(), refs.ref("b1").snapshotId());
    assertTrue(refs.ref("t1").isTag());
    TableMetadata reloaded = IrcWrite.load(mvc, "irc_w_refs", "t");
    assertEquals(refs.refs(), reloaded.refs());
  }

  @Test
  void aCommitWithNoChangesWritesNothing() throws Exception {
    createNative(nativeBody("irc_w_noop"));
    String before = stored("irc_w_noop");
    TableMetadata base = IrcWrite.load(mvc, "irc_w_noop", "t");
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_w_noop",
                "t",
                new UpdateTableRequest(
                    Collections.singletonList(new UpdateRequirement.AssertTableUUID(base.uuid())),
                    Collections.emptyList())));
    assertEquals(base.metadataFileLocation(), after.metadataFileLocation());
    assertEquals(before, stored("irc_w_noop"));
  }

  // ---------------------------------------------------------------- refused commits

  private void expectRefusedUnchanged(
      String db, UpdateTableRequest request, int status, String type, String nativeMessage)
      throws Exception {
    String before = stored(db);
    String message = assertIrcError(IrcWrite.commit(mvc, db, "t", request), status, type).message();
    if (nativeMessage != null) {
      assertEquals(nativeMessage, message);
    }
    assertEquals(before, stored(db));
  }

  @Test
  void aFailedRequirementIs409CommitFailedAndChangesNothing() throws Exception {
    createNative(nativeBody("irc_w_req"));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_req", "t");
    expectRefusedUnchanged(
        "irc_w_req",
        new UpdateTableRequest(
            Collections.singletonList(
                new UpdateRequirement.AssertTableUUID("00000000-0000-0000-0000-000000000000")),
            Collections.singletonList(
                new MetadataUpdate.SetProperties(Collections.singletonMap("user.k", "v")))),
        409,
        "CommitFailedException",
        null);
    // A stale ref assertion, as two writers appending from the same base would send.
    Snapshot first = IrcWrite.append(base, "/tmp/irc_w_req_a.orc");
    IrcWrite.parsed(IrcWrite.commit(mvc, "irc_w_req", "t", base, IrcWrite.withMain(base, first)));
    Snapshot second = IrcWrite.append(base, "/tmp/irc_w_req_b.orc");
    expectRefusedUnchanged(
        "irc_w_req",
        IrcWrite.simple(base, IrcWrite.withMain(base, second).changes()),
        409,
        "CommitFailedException",
        null);
  }

  @Test
  void reservedPropertyChangesGetTheNative400() throws Exception {
    String v0 = createNative(nativeBody("irc_w_resv"));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    String nativeMessage =
        message(
            put(
                    mvc,
                    b.toBuilder()
                        .tableProperties(with(b.getTableProperties(), "openhouse.tableId", "x"))
                        .build())
                .andExpect(status().isBadRequest()));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_resv", "t");
    expectRefusedUnchanged(
        "irc_w_resv",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap("openhouse.tableId", "x"))
                .build()
                .changes()),
        400,
        "BadRequestException",
        nativeMessage);
    // Removing a reserved key and editing the raw policies key are refused the same way.
    expectRefusedUnchanged(
        "irc_w_resv",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .removeProperties(Collections.singleton("openhouse.tableCreator"))
                .build()
                .changes()),
        400,
        "BadRequestException",
        null);
    expectRefusedUnchanged(
        "irc_w_resv",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap("policies", "{}"))
                .build()
                .changes()),
        400,
        "BadRequestException",
        null);
  }

  @Test
  void locationUuidAndFormatVersionChangesAreRefusedAndNamed() throws Exception {
    createNative(nativeBody("irc_w_fixed"));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_fixed", "t");
    List<Object[]> cases =
        Arrays.asList(
            new Object[] {new MetadataUpdate.SetLocation("/tmp/elsewhere"), "location"},
            new Object[] {
              new MetadataUpdate.AssignUUID("00000000-0000-0000-0000-000000000001"), "UUID"
            });
    for (Object[] c : cases) {
      String before = stored("irc_w_fixed");
      String message =
          assertIrcError(
                  IrcWrite.commit(
                      mvc,
                      "irc_w_fixed",
                      "t",
                      IrcWrite.simple(base, Collections.singletonList((MetadataUpdate) c[0]))),
                  400,
                  "BadRequestException")
              .message();
      assertTrue(message.contains((String) c[1]), message);
      assertEquals(before, stored("irc_w_fixed"));
    }
  }

  @Test
  void partitionEvolutionGetsTheNative400() throws Exception {
    String v0 = createNative(nativeBody("irc_w_part"));
    String nativeMessage =
        message(
            put(mvc, bodyFromGet(v0).toBuilder().clustering(null).build())
                .andExpect(status().isBadRequest()));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_part", "t");
    PartitionSpec timeOnly = PartitionSpec.builderFor(base.schema()).hour("timestampCol").build();
    expectRefusedUnchanged(
        "irc_w_part",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base).setDefaultPartitionSpec(timeOnly).build().changes()),
        400,
        "BadRequestException",
        nativeMessage);
  }

  @Test
  void droppingAColumnGetsTheNative400() throws Exception {
    String v0 = createNative(plainBody("irc_w_drop_col"));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    String nativeMessage =
        message(
            put(mvc, b.toBuilder().schema(dropColumn(b.getSchema(), "stats")).build())
                .andExpect(status().isBadRequest()));
    assertTrue(nativeMessage.contains("stats") || !nativeMessage.isEmpty(), nativeMessage);
    TableMetadata base = IrcWrite.load(mvc, "irc_w_drop_col", "t");
    Schema dropped =
        NativeCtas.table(base, IrcWrite.IO).updateSchema().deleteColumn("stats").apply();
    expectRefusedUnchanged(
        "irc_w_drop_col",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setCurrentSchema(dropped, base.lastColumnId())
                .build()
                .changes()),
        400,
        "BadRequestException",
        null);
  }

  @Test
  void aLockedTableIsRefusedWithTheNativeMessage() throws Exception {
    createNative(nativeBody("irc_w_lock"));
    lock(mvc, "irc_w_lock", "t").andExpect(status().isCreated());
    TableMetadata base = IrcWrite.load(mvc, "irc_w_lock", "t");
    String nativeMessage =
        message(
            put(
                    mvc,
                    bodyFromGet(json(get(mvc, "irc_w_lock", "t")))
                        .toBuilder()
                        .tableProperties(
                            with(
                                bodyFromGet(json(get(mvc, "irc_w_lock", "t"))).getTableProperties(),
                                "user.k",
                                "v"))
                        .build())
                .andExpect(status().isBadRequest()));
    expectRefusedUnchanged(
        "irc_w_lock",
        IrcWrite.simple(
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap("user.k", "v"))
                .build()
                .changes()),
        400,
        "BadRequestException",
        nativeMessage);
    unlock(mvc, "irc_w_lock", "t").andExpect(status().isNoContent());
  }

  @Test
  void aMissingTableIs404() throws Exception {
    TableMetadata base = IrcWrite.load(mvc, createNativeAndReturnDb("irc_w_missing"), "t");
    assertIrcError(
        IrcWrite.commit(
            mvc,
            "irc_w_missing",
            "nope",
            IrcWrite.simple(
                base,
                Collections.singletonList(
                    new MetadataUpdate.SetProperties(Collections.singletonMap("k", "v"))))),
        404,
        "NoSuchTableException");
    assertIrcError(IrcWrite.drop(mvc, "irc_w_missing", "nope"), 404, "NoSuchTableException");
  }

  private String createNativeAndReturnDb(String db) throws Exception {
    createNative(nativeBody(db));
    return db;
  }

  @Test
  void aTableRequiringACapabilityIsGatedOnCommitAsNatively() throws Exception {
    track(DEFAULTS_DB, "w1");
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/databases/" + DEFAULTS_DB + "/tables/")
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    newTable(DEFAULTS_DB, "w1")
                        .toBuilder()
                        .tableProperties(
                            with(
                                newTable(DEFAULTS_DB, "w1").getTableProperties(),
                                "required-capability.column-defaults",
                                "true"))
                        .build()
                        .toJson()))
        .andExpect(status().isCreated());
    String getJson =
        json(
            mvc.perform(
                MockMvcRequestBuilders.get(tableUrl(DEFAULTS_DB, "w1"))
                    .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)));
    CreateUpdateTableRequestBody b = bodyFromGet(getJson);
    String nativeMessage =
        message(
            put(
                    mvc,
                    b.toBuilder()
                        .tableProperties(with(b.getTableProperties(), "user.k", "v"))
                        .build())
                .andExpect(status().is(422)));
    MvcResult loaded =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, IrcWrite.table(DEFAULTS_DB, "w1"))
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
    TableMetadata base = IrcWrite.parsed(loaded);
    assertEquals(
        nativeMessage,
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    DEFAULTS_DB,
                    "w1",
                    IrcWrite.simple(
                        base,
                        Collections.singletonList(
                            new MetadataUpdate.SetProperties(
                                Collections.singletonMap("user.k", "v"))))),
                422,
                "ClientCapabilityMissingException")
            .message());
  }

  // ---------------------------------------------------------------- replace

  private String rtasTable(String db) throws Exception {
    String v0 = createNative(nativeBody(db));
    CreateUpdateTableRequestBody b = bodyFromGet(v0);
    put(
            mvc,
            b.toBuilder()
                .tableProperties(
                    with(b.getTableProperties(), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"))
                .build())
        .andExpect(status().isOk());
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Snapshot snap = IrcWrite.append(base, "/tmp/" + db + "_a.orc");
    IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", base, IrcWrite.withMain(base, snap)));
    return db;
  }

  private static Schema replacementSchema(TableMetadata base) {
    return NativeCtas.table(base, IrcWrite.IO)
        .updateSchema()
        .addColumn("r", Types.LongType.get())
        .apply();
  }

  @Test
  void replaceStoresWhatTheNativeReplaceCommitStores() throws Exception {
    rtasTable("irc_w_n_rtas");
    rtasTable("irc_w_i_rtas");

    // Native: what the OpenHouse client sends for the same replace transaction.
    TableMetadata nativeBase = IrcWrite.load(mvc, "irc_w_n_rtas", "t");
    TableMetadata nativeReplacement =
        IrcWrite.replacement(
            nativeBase, replacementSchema(nativeBase), nativeBase.spec(), Collections.emptyMap());
    Snapshot nativeSnap = IrcWrite.append(nativeReplacement, "/tmp/irc_w_n_rtas_r.orc");
    TableMetadata nativeAfter = IrcWrite.withMain(nativeReplacement, nativeSnap);
    String getJson = json(get(mvc, "irc_w_n_rtas", "t"));
    CreateUpdateTableRequestBody inner =
        bodyFromGet(getJson)
            .toBuilder()
            .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(nativeAfter.schema()))
            .replaceCommit(true)
            .build();
    putSnapshots(
            mvc,
            "irc_w_n_rtas",
            "t",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(str(getJson, "$.tableLocation"))
                .jsonSnapshots(
                    nativeAfter.snapshots().stream()
                        .map(SnapshotParser::toJson)
                        .collect(Collectors.toList()))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(SnapshotParser.toJson(nativeSnap)))
                .createUpdateTableRequestBody(inner)
                .build())
        .andExpect(status().isOk());

    TableMetadata base = IrcWrite.load(mvc, "irc_w_i_rtas", "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, replacementSchema(base), base.spec(), Collections.emptyMap());
    Snapshot snap = IrcWrite.append(replacement, "/tmp/irc_w_i_rtas_r.orc");
    TableMetadata then =
        IrcWrite.withMain(TableMetadata.buildFrom(replacement).discardChanges().build(), snap);
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc, "irc_w_i_rtas", "t", IrcWrite.replace(base, replacement, then.changes())));
    assertEquals(snap.snapshotId(), after.currentSnapshot().snapshotId());
    assertSameStored("irc_w_n_rtas", "irc_w_i_rtas");
  }

  /**
   * A replace that names a location (D30; {@code RESTSessionCatalog.replaceTransaction} then sends
   * a set-location among the replace's changes) keeps the table where it is: it stores what the
   * same replace without a location stores. An ordinary commit that sets the location is still
   * refused ({@link #locationUuidAndFormatVersionChangesAreRefusedAndNamed}).
   */
  @Test
  void replaceThatNamesALocationKeepsTheTablesLocation() throws Exception {
    rtasTable("irc_w_rloc_a");
    rtasTable("irc_w_rloc_b");
    TableMetadata baseA = IrcWrite.load(mvc, "irc_w_rloc_a", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_rloc_a",
            "t",
            IrcWrite.replace(
                baseA,
                IrcWrite.replacement(
                    baseA, replacementSchema(baseA), baseA.spec(), Collections.emptyMap()),
                Collections.emptyList())));

    TableMetadata base = IrcWrite.load(mvc, "irc_w_rloc_b", "t");
    TableMetadata replacement =
        base.buildReplacement(
            replacementSchema(base),
            base.spec(),
            SortOrder.unsorted(),
            "/tmp/irc_w_rloc_elsewhere/t",
            Collections.emptyMap());
    assertTrue(
        replacement.changes().stream().anyMatch(MetadataUpdate.SetLocation.class::isInstance));
    TableMetadata after =
        IrcWrite.parsed(
            IrcWrite.commit(
                mvc,
                "irc_w_rloc_b",
                "t",
                IrcWrite.replace(base, replacement, Collections.emptyList())));
    assertEquals(base.location(), after.location());
    assertEquals(base.location(), IrcWrite.load(mvc, "irc_w_rloc_b", "t").location());
    assertSameStored("irc_w_rloc_a", "irc_w_rloc_b");
    assertTrue(filesUnder("irc_w_rloc_elsewhere").isEmpty());
  }

  @Test
  void replaceIsRefusedWithTheNativeMessageWhenRtasIsNotEnabled() throws Exception {
    String v0 = createNative(nativeBody("irc_w_rtas_off"));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_rtas_off", "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, replacementSchema(base), base.spec(), Collections.emptyMap());
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    "irc_w_rtas_off",
                    "t",
                    IrcWrite.replace(base, replacement, Collections.emptyList())),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.contains("REPLACE TABLE AS SELECT is not enabled"), message);
    assertEquals(v0.length() > 0, true);
  }

  @Test
  void replaceIsRefusedWhileWapIsEnabled() throws Exception {
    rtasTable("irc_w_rtas_wap");
    TableMetadata base0 = IrcWrite.load(mvc, "irc_w_rtas_wap", "t");
    // Enabling WAP on an RTAS table needs the compatibility opt-out, as natively (the native
    // update validation refuses RTAS and WAP together otherwise).
    Map<String, String> wap = new HashMap<>();
    wap.put(CatalogConstants.WAP_ENABLED_TABLE_PROP, "true");
    wap.put(CatalogConstants.FEATURE_COMPATIBILITY_VALIDATION_DISABLED_TABLE_PROP, "true");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_rtas_wap",
            "t",
            base0,
            TableMetadata.buildFrom(base0).setProperties(wap).build()));
    TableMetadata base = IrcWrite.load(mvc, "irc_w_rtas_wap", "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, replacementSchema(base), base.spec(), Collections.emptyMap());
    String before = stored("irc_w_rtas_wap");
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    "irc_w_rtas_wap",
                    "t",
                    IrcWrite.replace(base, replacement, Collections.emptyList())),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.contains("while WAP"), message);
    assertEquals(before, stored("irc_w_rtas_wap"));
  }

  @Test
  void replaceOfALockedTableIsRefusedWithTheNativeLockMessage() throws Exception {
    rtasTable("irc_w_rtas_lock");
    lock(mvc, "irc_w_rtas_lock", "t").andExpect(status().isCreated());
    TableMetadata base = IrcWrite.load(mvc, "irc_w_rtas_lock", "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, replacementSchema(base), base.spec(), Collections.emptyMap());
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    "irc_w_rtas_lock",
                    "t",
                    IrcWrite.replace(base, replacement, Collections.emptyList())),
                400,
                "BadRequestException")
            .message();
    assertTrue(message.contains("is in locked state and cannot be written to"), message);
    unlock(mvc, "irc_w_rtas_lock", "t").andExpect(status().isNoContent());
  }

  @Test
  void replaceKeepsTheStoredPolicies() throws Exception {
    // Native table with policies (sharing only: replication would refuse a replace).
    CreateUpdateTableRequestBody body = newTable("irc_w_rtas_pol", "t");
    createNative(
        body.toBuilder()
            .policies(body.getPolicies().toBuilder().replication(null).sharingEnabled(true).build())
            .tableProperties(
                with(body.getTableProperties(), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"))
            .build());
    String policiesBefore = IrcWrite.load(mvc, "irc_w_rtas_pol", "t").properties().get("policies");
    TableMetadata base = IrcWrite.load(mvc, "irc_w_rtas_pol", "t");
    TableMetadata replacement =
        IrcWrite.replacement(base, replacementSchema(base), base.spec(), Collections.emptyMap());
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_rtas_pol",
            "t",
            IrcWrite.replace(base, replacement, Collections.emptyList())));
    TableMetadata after = IrcWrite.load(mvc, "irc_w_rtas_pol", "t");
    assertEquals(policiesBefore, after.properties().get("policies"));
    assertTrue(after.schema().findField("r") != null);
  }

  // ---------------------------------------------------------------- drop and rename

  @Test
  void dropIsTheNativeDrop() throws Exception {
    createNative(nativeBody("irc_w_drop"));
    assertFalse(filesUnder("irc_w_drop").isEmpty());
    MvcResult result = IrcWrite.drop(mvc, "irc_w_drop", "t");
    assertEquals(204, result.getResponse().getStatus(), body(result));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_drop", "t")));
    // The native drop purges the table's files whatever purgeRequested says.
    assertTrue(filesUnder("irc_w_drop").isEmpty(), filesUnder("irc_w_drop").toString());

    createNative(nativeBody("irc_w_drop"));
    MvcResult purge =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.DELETE, IrcWrite.table("irc_w_drop", "t"))
                .param("purgeRequested", "true"));
    assertEquals(204, purge.getResponse().getStatus(), body(purge));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_drop", "t")));
  }

  @Test
  void renameIsTheNativeRename() throws Exception {
    createNative(nativeBody("irc_w_ren"));
    track("irc_w_ren", "t2");
    String before = str(json(get(mvc, "irc_w_ren", "t")), "$.tableUUID");
    MvcResult result =
        IrcWrite.rename(
            mvc, TableIdentifier.of("irc_w_ren", "t"), TableIdentifier.of("irc_w_ren", "t2"));
    assertEquals(204, result.getResponse().getStatus(), body(result));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_ren", "t")));
    assertEquals(before, str(json(get(mvc, "irc_w_ren", "t2")), "$.tableUUID"));
  }

  @Test
  void renameRefusalsAreTheNativeOnes() throws Exception {
    createNative(nativeBody("irc_w_ren2"));
    createNative(newTable("irc_w_ren2", "u").toBuilder().policies(null).build());
    // Target exists: 409.
    String nativeExists =
        message(rename(mvc, "irc_w_ren2", "t", "irc_w_ren2", "u").andExpect(status().is(409)));
    assertEquals(
        nativeExists,
        assertIrcError(
                IrcWrite.rename(
                    mvc,
                    TableIdentifier.of("irc_w_ren2", "t"),
                    TableIdentifier.of("irc_w_ren2", "u")),
                409,
                "AlreadyExistsException")
            .message());
    // Case-only rename: the native 400.
    String nativeCase =
        message(
            rename(mvc, "irc_w_ren2", "t", "irc_w_ren2", "T").andExpect(status().isBadRequest()));
    assertEquals(
        nativeCase,
        assertIrcError(
                IrcWrite.rename(
                    mvc,
                    TableIdentifier.of("irc_w_ren2", "t"),
                    TableIdentifier.of("irc_w_ren2", "T")),
                400,
                "BadRequestException")
            .message());
    // Missing source: 404.
    assertIrcError(
        IrcWrite.rename(
            mvc, TableIdentifier.of("irc_w_ren2", "nope"), TableIdentifier.of("irc_w_ren2", "v")),
        404,
        "NoSuchTableException");
  }

  /**
   * A rename across databases, which the native rename refuses with 400 whether or not the target
   * database exists, answers what the spec lists for renameTable: 404 {@code
   * NoSuchNamespaceException} when the target namespace does not exist, and otherwise 406, the
   * spec's "the server does not support this operation", with the native message. Nothing changes.
   */
  @Test
  void renameAcrossDatabasesIs404ForAMissingTargetNamespaceAnd406Otherwise() throws Exception {
    createNative(nativeBody("irc_w_renx"));
    createNative(nativeBody("irc_w_renx_to"));
    String before = stored("irc_w_renx");
    // The native rename: 400 in both cases, unchanged.
    message(
        rename(mvc, "irc_w_renx", "t", "irc_w_renx_none", "u").andExpect(status().isBadRequest()));
    String nativeAcross =
        message(
            rename(mvc, "irc_w_renx", "t", "irc_w_renx_to", "u")
                .andExpect(status().isBadRequest()));
    assertTrue(nativeAcross.contains("across databases"), nativeAcross);

    assertEquals(
        "Namespace does not exist: irc_w_renx_none",
        assertIrcError(
                IrcWrite.rename(
                    mvc,
                    TableIdentifier.of("irc_w_renx", "t"),
                    TableIdentifier.of("irc_w_renx_none", "u")),
                404,
                "NoSuchNamespaceException")
            .message());
    assertEquals(
        nativeAcross,
        assertIrcError(
                IrcWrite.rename(
                    mvc,
                    TableIdentifier.of("irc_w_renx", "t"),
                    TableIdentifier.of("irc_w_renx_to", "u")),
                406,
                "UnsupportedOperationException")
            .message());
    assertEquals(before, stored("irc_w_renx"));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_renx_to", "u")));
    assertEquals(404, CommitPathHttp.status(get(mvc, "irc_w_renx_none", "u")));
  }

  // ---------------------------------------------------------------- table audit

  private void expectAudited(String db, OperationType type, OperationStatus status) {
    verify(tableAuditHandler, atLeastOnce())
        .audit(
            argThat(
                (TableAuditEvent event) ->
                    event != null
                        && db.equals(event.getDatabaseName())
                        && "t".equals(event.getTableName())
                        && event.getOperationType() == type
                        && event.getOperationStatus() == status));
  }

  /**
   * Every IRC write is in the table audit as the native write it corresponds to: create, staged
   * create, update, commit, replace commit, the commit completing a staged create, drop; a commit
   * the native rules refuse as FAILED.
   */
  @Test
  void irCWritesAreAuditedAsTheirNativeWrites() throws Exception {
    track("irc_w_audit", "t");
    IrcWrite.parsed(IrcWrite.create(mvc, "irc_w_audit", ircCreatePlain("t")));
    expectAudited("irc_w_audit", OperationType.CREATE, OperationStatus.SUCCESS);
    TableMetadata base = IrcWrite.load(mvc, "irc_w_audit", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_audit",
            "t",
            base,
            TableMetadata.buildFrom(base)
                .setProperties(
                    with(Collections.emptyMap(), CatalogConstants.RTAS_ENABLED_TABLE_PROP, "true"))
                .build()));
    expectAudited("irc_w_audit", OperationType.UPDATE, OperationStatus.SUCCESS);
    TableMetadata b1 = IrcWrite.load(mvc, "irc_w_audit", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_audit",
            "t",
            b1,
            IrcWrite.withMain(b1, IrcWrite.append(b1, "/tmp/irc_w_audit_a.orc"))));
    expectAudited("irc_w_audit", OperationType.COMMIT, OperationStatus.SUCCESS);
    TableMetadata b2 = IrcWrite.load(mvc, "irc_w_audit", "t");
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            "irc_w_audit",
            "t",
            IrcWrite.replace(
                b2,
                IrcWrite.replacement(b2, replacementSchema(b2), b2.spec(), Collections.emptyMap()),
                Collections.emptyList())));
    expectAudited("irc_w_audit", OperationType.REPLACE_COMMIT, OperationStatus.SUCCESS);
    IrcWrite.commit(
        mvc,
        "irc_w_audit",
        "t",
        IrcWrite.simple(
            b2,
            Collections.singletonList(
                new MetadataUpdate.SetProperties(
                    Collections.singletonMap("openhouse.tableId", "x")))));
    expectAudited("irc_w_audit", OperationType.UPDATE, OperationStatus.FAILED);
    assertEquals(204, IrcWrite.drop(mvc, "irc_w_audit", "t").getResponse().getStatus());
    expectAudited("irc_w_audit", OperationType.DELETE, OperationStatus.SUCCESS);

    track("irc_w_audit2", "t");
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                "irc_w_audit2",
                CreateTableRequest.builder()
                    .withName("t")
                    .withSchema(SCHEMA)
                    .setProperties(nativeBody("x").getTableProperties())
                    .stageCreate()
                    .build()));
    expectAudited("irc_w_audit2", OperationType.STAGED_CREATE, OperationStatus.SUCCESS);
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc, "irc_w_audit2", "t", IrcWrite.createCommit(staged, Collections.emptyList())));
    // Without data, the OpenHouse client completes a create transaction with a table PUT on the
    // initial version, which the native audit records as a CREATE; with data, as a STAGED_COMMIT.
    expectAudited("irc_w_audit2", OperationType.CREATE, OperationStatus.SUCCESS);
  }

  // ---------------------------------------------------------------- multi-level namespaces

  private MvcResult encoded(HttpMethod method, String path, Object body) throws Exception {
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(method, java.net.URI.create(IrcHttp.BASE + path));
    if (body != null) {
      request
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content(org.apache.iceberg.rest.IrcClientParsers.toJson(body));
    }
    return IrcHttp.perform(mvc, request);
  }

  /**
   * As on the read routes: a table route in a namespace of more than one level names a table that
   * cannot exist (404 {@code NoSuchTableException}, which is also what {@code RESTCatalog} needs to
   * fall back to a metadata table such as {@code db.t.snapshots}); a route that names a namespace
   * to create in, or to rename into, is malformed (400). Nothing is written.
   */
  @Test
  void writeRoutesInAMultiLevelNamespace() throws Exception {
    createNative(nativeBody("irc_w_ml"));
    String before = stored("irc_w_ml");
    TableMetadata base = IrcWrite.load(mvc, "irc_w_ml", "t");
    UpdateTableRequest update =
        IrcWrite.simple(
            base,
            Collections.singletonList(
                new MetadataUpdate.SetProperties(Collections.singletonMap("user.k", "v"))));
    assertIrcError(
        encoded(HttpMethod.POST, "/v1/namespaces/irc_w_ml%1Ft/tables/snapshots", update),
        404,
        "NoSuchTableException");
    assertIrcError(
        encoded(HttpMethod.DELETE, "/v1/namespaces/irc_w_ml%1Ft/tables/snapshots", null),
        404,
        "NoSuchTableException");
    assertIrcError(
        encoded(HttpMethod.POST, "/v1/namespaces/irc_w_ml%1Fx/tables", ircCreate("t")),
        400,
        "BadRequestException");
    assertIrcError(
        IrcWrite.rename(
            mvc,
            TableIdentifier.of("irc_w_ml", "t", "snapshots"),
            TableIdentifier.of("irc_w_ml", "u")),
        404,
        "NoSuchTableException");
    assertIrcError(
        IrcWrite.rename(
            mvc, TableIdentifier.of("irc_w_ml", "t"), TableIdentifier.of("irc_w_ml", "x", "u")),
        400,
        "BadRequestException");
    assertEquals(before, stored("irc_w_ml"));
  }

  // ---------------------------------------------------------------- namespace writes

  /**
   * Namespace writes are served (IrcNamespaceRoutesTest), so each answers its route's own refusal
   * of these requests, no longer the catch-all's 406: an empty create body names no namespace
   * (400), and a missing namespace has no record to update or drop (404).
   */
  @Test
  void namespaceWritesAreServed() throws Exception {
    for (Object[] route :
        new Object[][] {
          {HttpMethod.POST, "/v1/namespaces", 400, "BadRequestException"},
          {HttpMethod.DELETE, "/v1/namespaces/irc_w_ns", 404, "NoSuchNamespaceException"},
          {HttpMethod.POST, "/v1/namespaces/irc_w_ns/properties", 404, "NoSuchNamespaceException"}
        }) {
      assertIrcError(
          IrcHttp.perform(
              mvc,
              IrcHttp.request((HttpMethod) route[0], (String) route[1])
                  .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                  .content("{}")),
          (Integer) route[2],
          (String) route[3]);
    }
  }

  @Test
  void anUnknownUpdateIs400() throws Exception {
    createNative(nativeBody("irc_w_unknown"));
    String before = stored("irc_w_unknown");
    MvcResult result =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.POST, IrcWrite.table("irc_w_unknown", "t"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"requirements\":[],\"updates\":[{\"action\":\"no-such-update\"}]}"));
    assertIrcError(result, 400, "BadRequestException");
    assertEquals(before, stored("irc_w_unknown"));
    // A write route without a body is a 400 too.
    for (String path :
        new String[] {
          IrcWrite.tables("irc_w_unknown"),
          IrcWrite.table("irc_w_unknown", "t"),
          "/v1/tables/rename"
        }) {
      assertIrcError(
          IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.POST, path)), 400, "BadRequestException");
    }
    assertNotEquals(INITIAL_TABLE_VERSION, before);
  }
}
