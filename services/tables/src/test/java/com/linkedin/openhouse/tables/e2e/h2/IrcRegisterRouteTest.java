package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import com.linkedin.openhouse.tablestest.HouseTablesH2Repository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.types.Types;
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
 * The register-table route of the Iceberg REST Catalog (IRC) protocol at the HTTP boundary: {@code
 * POST /v1/namespaces/{namespace}/register} with a table name and a metadata file location.
 *
 * <p>OpenHouse registers a table by creating it, through the native create, from what the metadata
 * file holds, in the directory the file lies in. So the registered table is stored as a created
 * table is, and the server keeps owning locations: the only metadata that can be registered is
 * metadata in the directory OpenHouse itself allocates to a table of that name in that database,
 * that no table uses. The native drop removes a table's files with its house-table row, so such a
 * directory is left only where the row was lost without the files; the tests make one by deleting
 * the row from the house-table store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcRegisterRouteTest {

  @Autowired private HouseTablesH2Repository houseTablesH2Store;

  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTableRepository;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    houseTablesH2Store.softDeletedTables().clear();
  }

  // ---------------------------------------------------------------- fixtures

  static String registerPath(String db) {
    return "/v1/namespaces/" + db + "/register";
  }

  static String registerBody(String name, String metadataLocation) {
    return "{\"name\":\"" + name + "\",\"metadata-location\":\"" + metadataLocation + "\"}";
  }

  private MvcResult register(String db, String name, String metadataLocation) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, registerPath(db))
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody(name, metadataLocation)));
  }

  /** The native test table without policies. */
  private static CreateUpdateTableRequestBody plain(String db, String table) {
    return newTable(db, table)
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private void createNative(CreateUpdateTableRequestBody body) throws Exception {
    created.add(new String[] {body.getDatabaseId(), body.getTableId()});
    post(mvc, body).andExpect(status().isCreated());
  }

  private HouseTable row(String db, String table) {
    return houseTableRepository
        .findById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .orElseThrow(() -> new AssertionError("no house-table row for " + db + "." + table));
  }

  private boolean hasRow(String db, String table) {
    return houseTableRepository
        .findEntityById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .isPresent();
  }

  /**
   * Loses the house-table row of {@code db.table} and keeps its files: what register is for. (The
   * native drop removes both.) Returns the metadata location the row held.
   */
  private String loseTheRow(String db, String table) {
    String metadataLocation = row(db, table).getTableLocation();
    houseTableRepository.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build());
    assertFalse(hasRow(db, table));
    assertTrue(new File(metadataLocation).isFile(), metadataLocation);
    return metadataLocation;
  }

  /**
   * A table with one appended snapshot, then its row lost; returns its last metadata location as
   * the house-table row held it (without the storage's scheme).
   */
  private String orphanWithData(String db, String table) throws Exception {
    createNative(plain(db, table));
    TableMetadata base = IrcWrite.load(mvc, db, table);
    Snapshot snapshot = IrcWrite.append(base, "/tmp/" + db + "_" + table + "_a.orc");
    IrcWrite.parsed(IrcWrite.commit(mvc, db, table, base, IrcWrite.withMain(base, snapshot)));
    return loseTheRow(db, table);
  }

  private static TableMetadata read(String metadataLocation) {
    return TableMetadataParser.read(IrcWrite.IO, metadataLocation);
  }

  private static String directory(String metadataLocation) {
    return metadataLocation.substring(0, metadataLocation.lastIndexOf('/'));
  }

  /** Every file under the database's directory of the test storage (checksum files aside). */
  private static TreeSet<String> filesUnder(String db) throws Exception {
    File root = new File("/tmp/" + db);
    if (!root.exists()) {
      return new TreeSet<>();
    }
    try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
      return paths
          .filter(Files::isRegularFile)
          .map(java.nio.file.Path::toString)
          .filter(path -> !path.endsWith(".crc"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  /**
   * A register that is refused: the status and type, a message that names the table and holds
   * {@code fragment}, and nothing changed (no row, no file written or removed).
   */
  private ErrorResponse expectRefused(
      String db, String name, String metadataLocation, int status, String type, String fragment)
      throws Exception {
    boolean rowBefore = hasRow(db, name);
    TreeSet<String> filesBefore = filesUnder(db);
    ErrorResponse error = assertIrcError(register(db, name, metadataLocation), status, type);
    assertTrue(error.message().contains(fragment), error.message());
    assertEquals(rowBefore, hasRow(db, name));
    assertEquals(filesBefore, filesUnder(db));
    return error;
  }

  private static Map<String, String> without(Map<String, String> properties, String... keys) {
    Map<String, String> out = new HashMap<>(properties);
    for (String key : keys) {
      out.remove(key);
    }
    return out;
  }

  // ---------------------------------------------------------------- a registered table

  /** What registering is for: a registered table loads and commits. */
  @Test
  void aRegisteredTableLoadsAndCommits() throws Exception {
    String db = "irc_reg_ok";
    createNative(plain(db, "t"));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Snapshot first = IrcWrite.append(base, "/tmp/" + db + "_t_a.orc");
    // The metadata location as a client holds it: the one the service reports.
    String lost =
        JsonPath.read(
            body(IrcWrite.commit(mvc, db, "t", base, IrcWrite.withMain(base, first))),
            "$.metadata-location");
    assertEquals(lost, str(json(get(mvc, db, "t")), "$.tableLocation"));
    loseTheRow(db, "t");
    TableMetadata before = read(lost);
    assertEquals(404, IrcHttp.get(mvc, IrcWrite.table(db, "t")).getResponse().getStatus());

    MvcResult result = register(db, "t", lost);
    TableMetadata registered = IrcWrite.parsed(result);

    // The table is where the file is, holding what the file holds.
    assertEquals(before.location(), registered.location());
    assertTrue(before.schema().sameSchema(registered.schema()), registered.schema().toString());
    assertEquals(before.spec(), registered.spec());
    assertEquals(before.currentSnapshot().snapshotId(), registered.currentSnapshot().snapshotId());
    assertEquals(
        before.currentSnapshot().manifestListLocation(),
        registered.currentSnapshot().manifestListLocation());
    assertEquals(before.refs(), registered.refs());
    assertEquals(before.formatVersion(), registered.formatVersion());
    // It is stored by the server, in a new metadata file next to the registered one.
    String stored = JsonPath.read(body(result), "$.metadata-location");
    assertNotEquals(lost, stored);
    assertEquals(directory(lost), directory(stored));
    assertTrue(IrcWrite.IO.newInputFile(stored).exists(), stored);

    // It loads, over IRC and natively, as what was stored.
    TableMetadata loaded = IrcWrite.load(mvc, db, "t");
    assertEquals(registered.uuid(), loaded.uuid());
    assertEquals(registered.currentSnapshot().snapshotId(), loaded.currentSnapshot().snapshotId());
    assertEquals(stored, str(json(get(mvc, db, "t")), "$.tableLocation"));

    // It commits: a second append lands on top of the registered snapshot.
    Snapshot second = IrcWrite.append(loaded, "/tmp/" + db + "_t_b.orc");
    TableMetadata committed =
        IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", loaded, IrcWrite.withMain(loaded, second)));
    assertEquals(second.snapshotId(), committed.currentSnapshot().snapshotId());
    assertEquals(2, committed.snapshots().size());
    assertEquals(
        before.currentSnapshot().snapshotId(), committed.currentSnapshot().parentId().longValue());
  }

  /** Every snapshot and ref of the file is registered: two appends, a branch and a tag. */
  @Test
  void aTableWithSeveralSnapshotsAndRefsIsRegisteredWithAllOfThem() throws Exception {
    String db = "irc_reg_refs";
    createNative(plain(db, "t"));
    TableMetadata base = IrcWrite.load(mvc, db, "t");
    Snapshot first = IrcWrite.append(base, "/tmp/" + db + "_t_a.orc");
    TableMetadata one =
        IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", base, IrcWrite.withMain(base, first)));
    Snapshot second = IrcWrite.append(one, "/tmp/" + db + "_t_b.orc");
    TableMetadata two =
        IrcWrite.parsed(IrcWrite.commit(mvc, db, "t", one, IrcWrite.withMain(one, second)));
    IrcWrite.parsed(
        IrcWrite.commit(
            mvc,
            db,
            "t",
            two,
            TableMetadata.buildFrom(two)
                .setRef(
                    "b1", org.apache.iceberg.SnapshotRef.branchBuilder(first.snapshotId()).build())
                .setRef(
                    "t1", org.apache.iceberg.SnapshotRef.tagBuilder(second.snapshotId()).build())
                .build()));
    String lost = loseTheRow(db, "t");
    TableMetadata before = read(lost);
    assertEquals(2, before.snapshots().size());
    assertEquals(3, before.refs().size());

    TableMetadata registered = IrcWrite.parsed(register(db, "t", lost));
    assertEquals(before.refs(), registered.refs());
    assertEquals(
        before.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toList()),
        registered.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toList()));
    assertEquals(before.lastSequenceNumber(), registered.lastSequenceNumber());
    assertEquals(second.snapshotId(), registered.currentSnapshot().snapshotId());
    assertEquals(registered.refs(), IrcWrite.load(mvc, db, "t").refs());
  }

  /**
   * The registered table's house-table row is the row a create writes: equal to the row the table
   * had when it was created, apart from the metadata file it points at and the times. Its reserved
   * properties are the server's own, as for a created table, and its policies are kept.
   */
  @Test
  void theRegisteredTableIsStoredAsACreatedTableIs() throws Exception {
    String db = "irc_reg_row";
    // The native test table, with its policies, time partitioning and clustering.
    createNative(newTable(db, "t"));
    HouseTable created = row(db, "t");
    String lost = loseTheRow(db, "t");
    TableMetadata before = read(lost);
    assertTrue(before.properties().get("policies").contains("retention"), before.properties() + "");

    TableMetadata registered = IrcWrite.parsed(register(db, "t", lost));
    HouseTable row = row(db, "t");

    assertEquals(created.getDatabaseId(), row.getDatabaseId());
    assertEquals(created.getTableId(), row.getTableId());
    assertEquals(created.getClusterId(), row.getClusterId());
    assertEquals(created.getTableUri(), row.getTableUri());
    assertEquals(created.getTableUUID(), row.getTableUUID());
    assertEquals(created.getTableCreator(), row.getTableCreator());
    assertEquals(created.getStorageType(), row.getStorageType());
    assertEquals(created.getEntityType(), row.getEntityType());
    // A created table's row holds its first metadata file as its version too.
    assertEquals(created.getTableLocation(), created.getTableVersion());
    assertEquals(row.getTableLocation(), row.getTableVersion());
    assertEquals(created.getDeletedAtMs(), row.getDeletedAtMs());
    assertEquals(created.getPurgeAfterMs(), row.getPurgeAfterMs());
    // What must differ: the row points at the file the registration wrote, in the same directory.
    assertNotEquals(created.getTableLocation(), row.getTableLocation());
    assertEquals(directory(created.getTableLocation()), directory(row.getTableLocation()));
    assertTrue(row.getCreationTime() >= created.getCreationTime());

    // The stored metadata: what the created table's held, the times and the file name aside.
    String[] differing = {
      "openhouse.tableLocation", "openhouse.lastModifiedTime", "openhouse.creationTime"
    };
    assertEquals(
        without(before.properties(), differing), without(registered.properties(), differing));
    assertTrue(before.schema().sameSchema(registered.schema()));
    assertEquals(before.spec(), registered.spec());
    assertEquals(before.sortOrder(), registered.sortOrder());
    // The Iceberg table UUID is drawn by the create, as for every create over IRC.
    assertNotEquals(before.uuid(), registered.uuid());
  }

  // ---------------------------------------------------------------- refusals: the name

  @Test
  void registerOfANameThatIsTakenIs409AlreadyExists() throws Exception {
    String db = "irc_reg_exists";
    createNative(plain(db, "t"));
    String location = row(db, "t").getTableLocation();
    ErrorResponse error =
        expectRefused(db, "t", location, 409, "AlreadyExistsException", db + ".t");
    assertEquals("Table already exists: " + db + ".t", error.message());
    assertEquals(location, row(db, "t").getTableLocation());
  }

  // ---------------------------------------------------------------- refusals: the location

  /**
   * The server owns locations: only metadata in the directory it allocates to a table of that name
   * in that database can be registered. Each of these files is real table metadata.
   */
  @Test
  void aMetadataLocationThatIsNotTheTablesOwnDirectoryIsRefused() throws Exception {
    String db = "irc_reg_loc";
    String lost = orphanWithData(db, "t");
    String file = lost.substring(lost.lastIndexOf('/') + 1);
    String directoryName = directory(lost).substring(directory(lost).lastIndexOf('/') + 1);

    // Outside the storage root, in a directory named as the server would name it.
    File elsewhere = new File("/tmp/irc_reg_elsewhere/" + db + "/" + directoryName);
    elsewhere.mkdirs();
    Files.copy(
        Paths.get(lost),
        new File(elsewhere, file).toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    expectRefused(
        db,
        "t",
        new File(elsewhere, file).getPath(),
        400,
        "BadRequestException",
        "OpenHouse assigns the location of a table");

    // Another database's storage: the same file, registered into a database it does not lie in.
    expectRefused(
        "irc_reg_loc_other",
        "t",
        lost,
        400,
        "BadRequestException",
        "OpenHouse assigns the location of a table");

    // Another table's directory: the same file, registered under another name.
    expectRefused(
        db, "other", lost, 400, "BadRequestException", "OpenHouse assigns the location of a table");

    // A path that only resolves to the directory.
    expectRefused(
        db,
        "t",
        directory(lost) + "/../" + directoryName + "/" + file,
        400,
        "BadRequestException",
        "OpenHouse assigns the location of a table");

    // A file below the directory, or one that is not a metadata file by name.
    expectRefused(
        db,
        "t",
        directory(lost) + "/metadata/" + file,
        400,
        "BadRequestException",
        "OpenHouse assigns the location of a table");
    expectRefused(
        db,
        "t",
        read(lost).currentSnapshot().manifestListLocation(),
        400,
        "BadRequestException",
        "OpenHouse assigns the location of a table");

    // The file itself, under its own name, in its own database, is registered.
    assertEquals(200, register(db, "t", lost).getResponse().getStatus());
  }

  /** A table renamed away keeps its directory: that directory is a live table's. */
  @Test
  void aMetadataLocationOfALiveTableIsRefused() throws Exception {
    String db = "irc_reg_live";
    createNative(plain(db, "t"));
    assertEquals(
        204,
        IrcWrite.rename(mvc, TableIdentifier.of(db, "t"), TableIdentifier.of(db, "renamed"))
            .getResponse()
            .getStatus());
    created.add(new String[] {db, "renamed"});
    String location = row(db, "renamed").getTableLocation();
    assertTrue(location.contains("/" + db + "/t-"), location);

    ErrorResponse error =
        expectRefused(db, "t", location, 400, "BadRequestException", "belongs to");
    assertTrue(error.message().contains(db + ".renamed"), error.message());
    assertEquals(location, row(db, "renamed").getTableLocation());
  }

  /** A soft-deleted table's files are still that table's: it can be restored. */
  @Test
  void aMetadataLocationOfASoftDeletedTableIsRefused() throws Exception {
    String db = "irc_reg_soft";
    createNative(plain(db, "t"));
    String location = row(db, "t").getTableLocation();
    // What a soft delete leaves: the files, and the row in the soft-deleted store.
    houseTableRepository.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId("t").build(), false);
    assertFalse(hasRow(db, "t"));

    expectRefused(db, "t", location, 400, "BadRequestException", "soft-deleted");
  }

  // ---------------------------------------------------------------- refusals: the file

  @Test
  void metadataWhoseLocationIsNotTheDirectoryItLiesInIsRefused() throws Exception {
    String db = "irc_reg_foreign";
    String lost = orphanWithData(db, "t");
    String other = orphanWithData(db, "u");
    // The other table's metadata, placed in this table's directory.
    String planted = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    Files.copy(Paths.get(other), Paths.get(planted));
    try {
      ErrorResponse error =
          expectRefused(db, "t", planted, 400, "BadRequestException", "is not the directory");
      // The other table's location is not told to the caller.
      assertFalse(error.message().contains(read(other).location()), error.message());
    } finally {
      Files.delete(Paths.get(planted));
    }
  }

  @Test
  void aFileThatIsMissingOrIsNotTableMetadataIsRefusedWithoutEchoingIt() throws Exception {
    String db = "irc_reg_nofile";
    String lost = orphanWithData(db, "t");
    expectRefused(
        db,
        "t",
        directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json",
        400,
        "BadRequestException",
        "no file");

    String garbage = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    Files.write(
        Paths.get(garbage), "{\"secret\": \"hunter2\", nonsense".getBytes(StandardCharsets.UTF_8));
    try {
      ErrorResponse error =
          expectRefused(db, "t", garbage, 400, "BadRequestException", "not table metadata");
      assertFalse(error.message().contains("hunter2"), error.message());
      assertFalse(error.message().contains("secret"), error.message());
    } finally {
      Files.delete(Paths.get(garbage));
    }
  }

  /**
   * Metadata a create cannot store as it is: another format version than the cluster's, and a
   * schema history (a table whose schema was changed), which a created table does not have.
   */
  @Test
  void metadataACreateCannotStoreIsRefusedAndNamed() throws Exception {
    String db = "irc_reg_shape";
    String lost = orphanWithData(db, "t");
    TableMetadata metadata = read(lost);

    Map<String, String> v1 = new HashMap<>();
    v1.put("format-version", "1");
    String otherVersion = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    TableMetadataParser.write(
        TableMetadata.newTableMetadata(
            SCHEMA, PartitionSpec.unpartitioned(), SortOrder.unsorted(), metadata.location(), v1),
        IrcWrite.IO.newOutputFile(otherVersion));

    List<Types.NestedField> columns = new ArrayList<>(metadata.schema().columns());
    columns.add(
        Types.NestedField.optional(
            metadata.lastColumnId() + 1, "added_later", Types.StringType.get()));
    String evolved = directory(lost) + "/00010-" + UUID.randomUUID() + ".metadata.json";
    TableMetadataParser.write(
        TableMetadata.buildFrom(metadata)
            .setCurrentSchema(new Schema(columns), metadata.lastColumnId() + 1)
            .build(),
        IrcWrite.IO.newOutputFile(evolved));
    try {
      expectRefused(db, "t", otherVersion, 400, "BadRequestException", "format version 1");
      expectRefused(db, "t", evolved, 400, "BadRequestException", "schema");
    } finally {
      Files.delete(Paths.get(otherVersion));
      Files.delete(Paths.get(evolved));
    }
  }

  /** Writes {@code metadata} as a metadata file next to {@code lost} and returns its location. */
  private static String plant(String lost, TableMetadata metadata) {
    String planted = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    TableMetadataParser.write(metadata, IrcWrite.IO.newOutputFile(planted));
    return planted;
  }

  /**
   * Writes the metadata file {@code lost} again next to it, with its top-level JSON field {@code
   * field} set to {@code valueJson}, and returns the new file's location.
   */
  private static String plantWithField(String lost, String field, String valueJson)
      throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode json =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            mapper.readTree(
                new String(Files.readAllBytes(Paths.get(lost)), StandardCharsets.UTF_8));
    json.set(field, mapper.readTree(valueJson));
    String planted = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    Files.write(Paths.get(planted), mapper.writeValueAsBytes(json));
    return planted;
  }

  /**
   * A partition spec history (a table whose partitioning was changed, or given a second spec),
   * which a created table does not have: a create stores one spec, with id 0.
   */
  @Test
  void metadataWithAPartitionSpecHistoryIsRefused() throws Exception {
    String db = "irc_reg_specs";
    String lost = orphanWithData(db, "t");
    TableMetadata metadata = read(lost);
    PartitionSpec byName = PartitionSpec.builderFor(metadata.schema()).identity("name").build();
    // The partitioning changed: two specs, the second the default.
    String changed =
        plant(lost, TableMetadata.buildFrom(metadata).setDefaultPartitionSpec(byName).build());
    // A second spec that was added and never made the default.
    String added = plant(lost, TableMetadata.buildFrom(metadata).addPartitionSpec(byName).build());
    try {
      assertEquals(2, read(changed).specs().size());
      assertEquals(1, read(changed).defaultSpecId());
      expectRefused(
          db,
          "t",
          changed,
          400,
          "BadRequestException",
          "its metadata holds a partition spec history (2 specs, default spec id 1), which a "
              + "created table does not have");
      assertEquals(2, read(added).specs().size());
      assertEquals(0, read(added).defaultSpecId());
      expectRefused(
          db,
          "t",
          added,
          400,
          "BadRequestException",
          "its metadata holds a partition spec history (2 specs, default spec id 0), which a "
              + "created table does not have");
    } finally {
      Files.delete(Paths.get(changed));
      Files.delete(Paths.get(added));
    }
    // The table's own file, which has one spec, is registered.
    assertEquals(200, register(db, "t", lost).getResponse().getStatus());
  }

  /**
   * Field ids a create would assign differently: a create numbers the columns from 1 in order and
   * the partition fields from 1000, and the table's data files are written with the ids its
   * metadata holds, so metadata with other ids cannot be stored by a create.
   */
  @Test
  void metadataWithFieldIdsACreateWouldRenumberIsRefused() throws Exception {
    String db = "irc_reg_ids";
    String lost = orphanWithData(db, "t");
    TableMetadata metadata = read(lost);

    // One schema, with id 0, whose columns are numbered from 11: what a table has whose first
    // columns were dropped and added again before its schema history was lost.
    List<Types.NestedField> columns =
        metadata.schema().columns().stream()
            .map(
                field ->
                    Types.NestedField.of(
                        field.fieldId() + 10, field.isOptional(), field.name(), field.type()))
            .collect(Collectors.toList());
    TableMetadata renumberedColumns =
        TableMetadata.buildFromEmpty()
            .assignUUID()
            .setLocation(metadata.location())
            .setCurrentSchema(new Schema(columns), metadata.lastColumnId() + 10)
            .setDefaultPartitionSpec(PartitionSpec.unpartitioned())
            .setDefaultSortOrder(SortOrder.unsorted())
            .setProperties(metadata.properties())
            .build();
    assertEquals(1, renumberedColumns.schemas().size());
    assertEquals(0, renumberedColumns.currentSchemaId());
    assertEquals(11, renumberedColumns.schema().columns().get(0).fieldId());
    String columnIds = plant(lost, renumberedColumns);

    // The table's own schema, partitioned by a field numbered 1005 where a create assigns 1000.
    int nameId = metadata.schema().findField("name").fieldId();
    PartitionSpec byName =
        org.apache.iceberg.PartitionSpecParser.fromJson(
            metadata.schema(),
            "{\"spec-id\":0,\"fields\":[{\"name\":\"name\",\"transform\":\"identity\","
                + "\"source-id\":"
                + nameId
                + ",\"field-id\":1005}]}");
    TableMetadata renumberedPartitionField =
        TableMetadata.buildFromEmpty()
            .assignUUID()
            .setLocation(metadata.location())
            .setCurrentSchema(metadata.schema(), metadata.lastColumnId())
            .setDefaultPartitionSpec(byName)
            .setDefaultSortOrder(SortOrder.unsorted())
            .setProperties(metadata.properties())
            .build();
    assertEquals(1, renumberedPartitionField.specs().size());
    assertEquals(0, renumberedPartitionField.defaultSpecId());
    assertEquals(1005, renumberedPartitionField.spec().fields().get(0).fieldId());
    String partitionFieldId = plant(lost, renumberedPartitionField);
    try {
      for (String planted : new String[] {columnIds, partitionFieldId}) {
        expectRefused(
            db,
            "t",
            planted,
            400,
            "BadRequestException",
            "its schema, partition spec or sort order uses field ids a create would assign "
                + "differently, and its data files are written with them");
      }
    } finally {
      Files.delete(Paths.get(columnIds));
      Files.delete(Paths.get(partitionFieldId));
    }
    assertEquals(200, register(db, "t", lost).getResponse().getStatus());
  }

  /**
   * Statistics files (table statistics, or partition statistics), which a create does not store:
   * registering the table would silently lose them.
   */
  @Test
  void metadataWithStatisticsFilesIsRefused() throws Exception {
    String db = "irc_reg_stats";
    String lost = orphanWithData(db, "t");
    long snapshotId = read(lost).currentSnapshot().snapshotId();
    String statistics =
        plantWithField(
            lost,
            "statistics",
            "[{\"snapshot-id\":"
                + snapshotId
                + ",\"statistics-path\":\""
                + directory(lost)
                + "/metadata/stats.puffin\",\"file-size-in-bytes\":10,"
                + "\"file-footer-size-in-bytes\":4,\"blob-metadata\":[]}]");
    String partitionStatistics =
        plantWithField(
            lost,
            "partition-statistics",
            "[{\"snapshot-id\":"
                + snapshotId
                + ",\"statistics-path\":\""
                + directory(lost)
                + "/metadata/partition-stats.parquet\",\"file-size-in-bytes\":10}]");
    try {
      assertEquals(1, read(statistics).statisticsFiles().size());
      assertEquals(0, read(statistics).partitionStatisticsFiles().size());
      assertEquals(0, read(partitionStatistics).statisticsFiles().size());
      assertEquals(1, read(partitionStatistics).partitionStatisticsFiles().size());
      for (String planted : new String[] {statistics, partitionStatistics}) {
        expectRefused(
            db,
            "t",
            planted,
            400,
            "BadRequestException",
            "its metadata holds statistics files, which a create does not store");
      }
    } finally {
      Files.delete(Paths.get(statistics));
      Files.delete(Paths.get(partitionStatistics));
    }
    assertEquals(200, register(db, "t", lost).getResponse().getStatus());
  }

  /**
   * A replica table's metadata: the routes create primary tables only (a create over IRC that asks
   * for another type is refused too), so registering it would turn a replica into a primary table.
   */
  @Test
  void metadataOfAReplicaTableIsRefused() throws Exception {
    String db = "irc_reg_replica";
    String lost = orphanWithData(db, "t");
    TableMetadata metadata = read(lost);
    assertEquals("PRIMARY_TABLE", metadata.properties().get("openhouse.tableType"));
    Map<String, String> replica = new HashMap<>();
    replica.put("openhouse.tableType", "REPLICA_TABLE");
    String planted = plant(lost, TableMetadata.buildFrom(metadata).setProperties(replica).build());
    try {
      assertEquals("REPLICA_TABLE", read(planted).properties().get("openhouse.tableType"));
      expectRefused(
          db,
          "t",
          planted,
          400,
          "BadRequestException",
          "its metadata is a REPLICA_TABLE's: the routes create PRIMARY_TABLE tables");
    } finally {
      Files.delete(Paths.get(planted));
    }
    assertEquals(200, register(db, "t", lost).getResponse().getStatus());
  }

  /** A snapshot whose manifest list is another table's attaches that table's data. */
  @Test
  void metadataWithASnapshotOutsideItsDirectoryIsRefused() throws Exception {
    String db = "irc_reg_snap";
    String lost = orphanWithData(db, "t");
    String other = orphanWithData(db, "u");
    String json = new String(Files.readAllBytes(Paths.get(lost)), StandardCharsets.UTF_8);
    String ownList = read(lost).currentSnapshot().manifestListLocation();
    String otherList = read(other).currentSnapshot().manifestListLocation();
    assertTrue(json.contains(ownList), json);
    String planted = directory(lost) + "/00009-" + UUID.randomUUID() + ".metadata.json";
    Files.write(
        Paths.get(planted), json.replace(ownList, otherList).getBytes(StandardCharsets.UTF_8));
    try {
      expectRefused(db, "t", planted, 400, "BadRequestException", "manifest list");
    } finally {
      Files.delete(Paths.get(planted));
    }
  }

  // ---------------------------------------------------------------- refusals: the request

  @Test
  void aRequestThatIsNotARegistrationIs400() throws Exception {
    String db = "irc_reg_bad";
    for (String content :
        new String[] {
          "", "not json", "{}", "{\"name\":\"t\"}", "{\"metadata-location\":\"/tmp/x\"}"
        }) {
      assertIrcError(
          IrcHttp.perform(
              mvc,
              IrcHttp.request(HttpMethod.POST, registerPath(db))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(content)),
          400,
          "BadRequestException");
    }
    // A name that is not an OpenHouse table id, as on create.
    assertIrcError(
        register(db, "not-a-valid-id", "/tmp/" + db + "/x.metadata.json"),
        400,
        "BadRequestException");
    // A namespace of more than one level is not a database, as on create.
    assertIrcError(
        IrcHttp.perform(
            mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    HttpMethod.POST, java.net.URI.create(IrcHttp.BASE + registerPath(db + "%1Fx")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("t", "/tmp/" + db + "/x.metadata.json"))),
        400,
        "BadRequestException");
  }

  // ---------------------------------------------------------------- the config

  @Test
  void theConfigListsTheRoute() throws Exception {
    List<String> endpoints = JsonPath.read(body(IrcHttp.get(mvc, "/v1/config")), "$.endpoints");
    assertTrue(
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/register"),
        endpoints.toString());
  }
}
