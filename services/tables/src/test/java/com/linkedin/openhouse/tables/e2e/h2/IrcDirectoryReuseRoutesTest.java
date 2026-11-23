package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * No create over the Iceberg REST Catalog (IRC) routes can be given a directory another table uses.
 *
 * <p>A table's files live in the directory the server allocates to it, {@code
 * <root>/<database>/<table>-<uuid>}, and a drop removes that directory with the table's house-table
 * row. A table that was renamed keeps its directory, which still carries its old name, and a
 * soft-deleted table's directory is kept for its restore. So a create of the old name that named
 * the old UUID would be a second table in the first one's directory, and the drop of the second
 * would destroy the first's files.
 *
 * <p>Each test makes such a first table ({@code db.a} with a snapshot, renamed to {@code db.b}, or
 * soft-deleted), then asks for a second {@code db.a} in its directory through one way an IRC client
 * can spell that: the commit that completes a staged create (naming the directory as its location,
 * or carrying a snapshot that lies in it), or a create or staged create whose properties carry the
 * first table's {@code openhouse.tableUUID}, which the native create reads the UUID from. The
 * completion is refused and changes nothing; a create is made in a directory of its own (the server
 * owns locations, D30), and its drop leaves the first table as it was.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcDirectoryReuseRoutesTest {

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
    HouseTablesH2Repository.softDeletedTables.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private void track(String db, String table) {
    created.add(new String[] {db, table});
  }

  /** The native test table without policies, partitioning or clustering. */
  private static CreateUpdateTableRequestBody plain(String db, String table) {
    return newTable(db, table)
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private static CreateTableRequest.Builder ircCreate(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .withPartitionSpec(PartitionSpec.unpartitioned());
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

  /** {@code db.a} with one appended snapshot. */
  private void tableWithData(String db) throws Exception {
    track(db, "a");
    assertEquals(201, CommitPathHttp.status(post(mvc, plain(db, "a"))));
    TableMetadata base = IrcWrite.load(mvc, db, "a");
    Snapshot snapshot = IrcWrite.append(base, "/tmp/" + db + "_a_first.orc");
    IrcWrite.parsed(IrcWrite.commit(mvc, db, "a", base, IrcWrite.withMain(base, snapshot)));
  }

  /**
   * The first table: {@code db.a} with one snapshot, renamed to {@code db.b}. Its directory keeps
   * the name it was allocated under. Returns its house-table row.
   */
  private HouseTable liveRenamed(String db) throws Exception {
    tableWithData(db);
    assertEquals(
        204,
        IrcWrite.rename(mvc, TableIdentifier.of(db, "a"), TableIdentifier.of(db, "b"))
            .getResponse()
            .getStatus());
    track(db, "b");
    HouseTable b = row(db, "b");
    assertTrue(b.getTableLocation().contains("/" + db + "/a-" + b.getTableUUID() + "/"));
    assertFalse(hasRow(db, "a"));
    return b;
  }

  /** The staged create of {@code db.a} a client makes, in the directory the server allocates. */
  private TableMetadata staged(String db) throws Exception {
    return IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate("a").stageCreate().build()));
  }

  /** {@code staged} as a client would hold it had the staged create answered {@code location}. */
  private static TableMetadata at(TableMetadata staged, String location) {
    return TableMetadata.buildFrom(staged).discardChanges().setLocation(location).build();
  }

  /** The changes a create transaction adds for one append of {@code snapshot}. */
  private static List<MetadataUpdate> appendOf(TableMetadata staged, Snapshot snapshot) {
    return IrcWrite.withMain(TableMetadata.buildFrom(staged).discardChanges().build(), snapshot)
        .changes();
  }

  /** The properties the native create reads a table's UUID, and so its directory, from. */
  private static Map<String, String> uuidProperties(String db, HouseTable first, boolean replica) {
    Map<String, String> properties = new HashMap<>();
    properties.put("openhouse.tableUUID", first.getTableUUID());
    properties.put("openhouse.databaseId", db);
    properties.put("openhouse.tableId", "a");
    if (replica) {
      // A replicated table's create: the native create does not look at the path at all.
      properties.put("openhouse.isTableReplicated", "true");
      properties.put("last-updated-ms", Long.toString(System.currentTimeMillis() - 1000));
    } else {
      // The native path check: a file that exists under <root>/<db>/a-<uuid>.
      properties.put("openhouse.tableLocation", first.getTableLocation());
    }
    return properties;
  }

  /**
   * A completion that is refused: 400 {@code BadRequestException} (which the spec lists for the
   * commit route) with exactly {@code message}, no row for {@code db.a}, no file written or
   * removed, and the first table's row still there.
   */
  private void expectRefused(String db, MvcResult completion, String message, String firstTable)
      throws Exception {
    ErrorResponse error = assertIrcError(completion, 400, "BadRequestException");
    assertEquals(message, error.message());
    assertFalse(hasRow(db, "a"));
    assertEquals(404, IrcHttp.get(mvc, IrcWrite.table(db, "a")).getResponse().getStatus());
    if (firstTable != null) {
      assertTrue(hasRow(db, firstTable));
    }
  }

  /** The first table is what it was: its row, every file of its directory, and it loads. */
  private void assertFirstTableIntact(
      String db, HouseTable before, TreeSet<String> filesBefore, long snapshotId) throws Exception {
    assertEquals(before.getTableLocation(), row(db, "b").getTableLocation());
    assertEquals(filesBefore, filesUnder(db));
    TableMetadata loaded = IrcWrite.load(mvc, db, "b");
    assertEquals(snapshotId, loaded.currentSnapshot().snapshotId());
    assertTrue(IrcWrite.IO.newInputFile(loaded.currentSnapshot().manifestListLocation()).exists());
    assertEquals(200, CommitPathHttp.status(get(mvc, db, "b")));
  }

  // ---------------------------------------------------------------- the completion of a staged
  // create

  /**
   * The staged create answers a directory of its own, and its completion there is the ordinary
   * case. A completion that names the renamed table's directory instead (the server stores nothing
   * for a staged create, so the location a completion carries is the client's word) is refused.
   */
  @Test
  void aCompletionNamingALiveTablesDirectoryIsRefusedAndChangesNothing() throws Exception {
    String db = "irc_reuse_live";
    HouseTable first = liveRenamed(db);
    String firstDirectory = directory(first.getTableLocation());
    long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
    TableMetadata staged = staged(db);
    assertNotEquals(firstDirectory, staged.location());
    TreeSet<String> filesBefore = filesUnder(db);

    expectRefused(
        db,
        IrcWrite.commit(
            mvc,
            db,
            "a",
            IrcWrite.createCommit(at(staged, firstDirectory), Collections.emptyList())),
        "Cannot create table " + db + ".a: that directory belongs to " + db + ".b",
        "b");
    assertFirstTableIntact(db, first, filesBefore, snapshotId);

    // The completion the staged create asked for is still accepted, in its own directory, and the
    // drop of that table leaves the first one alone.
    track(db, "a");
    TableMetadata completed =
        IrcWrite.parsed(
            IrcWrite.commit(mvc, db, "a", IrcWrite.createCommit(staged, Collections.emptyList())));
    assertEquals(staged.location(), completed.location());
    assertEquals(204, IrcWrite.drop(mvc, db, "a").getResponse().getStatus());
    assertFirstTableIntact(db, first, filesBefore, snapshotId);
  }

  /** The same with data (a CTAS's completion): the snapshot's files were written there too. */
  @Test
  void aCompletionWithDataNamingALiveTablesDirectoryIsRefusedAndChangesNothing() throws Exception {
    String db = "irc_reuse_live_data";
    HouseTable first = liveRenamed(db);
    String firstDirectory = directory(first.getTableLocation());
    long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
    TableMetadata misplaced = at(staged(db), firstDirectory);
    // What the client wrote before it commits: a manifest and a manifest list in that directory.
    Snapshot snapshot = IrcWrite.append(misplaced, "/tmp/" + db + "_a_second.orc");
    assertTrue(snapshot.manifestListLocation().startsWith(firstDirectory + "/"));
    TreeSet<String> filesBefore = filesUnder(db);

    expectRefused(
        db,
        IrcWrite.commit(
            mvc, db, "a", IrcWrite.createCommit(misplaced, appendOf(misplaced, snapshot))),
        "Cannot create table " + db + ".a: that directory belongs to " + db + ".b",
        "b");
    assertFirstTableIntact(db, first, filesBefore, snapshotId);
  }

  /**
   * A completion with data names its own staged directory, but its snapshot lies in the renamed
   * table's: the native create-with-data puts the table where its first snapshot's manifest list
   * is, so the snapshot must lie in the directory the completion names.
   */
  @Test
  void aCompletionWhoseSnapshotLiesInALiveTablesDirectoryIsRefusedAndChangesNothing()
      throws Exception {
    String db = "irc_reuse_live_snap";
    HouseTable first = liveRenamed(db);
    String firstDirectory = directory(first.getTableLocation());
    long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
    TableMetadata staged = staged(db);
    Snapshot snapshot = IrcWrite.append(at(staged, firstDirectory), "/tmp/" + db + "_a_second.orc");
    assertTrue(snapshot.manifestListLocation().startsWith(firstDirectory + "/"));
    TreeSet<String> filesBefore = filesUnder(db);

    expectRefused(
        db,
        IrcWrite.commit(mvc, db, "a", IrcWrite.createCommit(staged, appendOf(staged, snapshot))),
        "Cannot create table "
            + db
            + ".a: the manifest list of snapshot "
            + snapshot.snapshotId()
            + " does not lie in the table's directory",
        "b");
    assertFirstTableIntact(db, first, filesBefore, snapshotId);
  }

  /** A soft-deleted table's files are still that table's: it can be restored into them. */
  @Test
  void aCompletionNamingASoftDeletedTablesDirectoryIsRefusedAndChangesNothing() throws Exception {
    String db = "irc_reuse_soft";
    tableWithData(db);
    HouseTable first = row(db, "a");
    String firstDirectory = directory(first.getTableLocation());
    // What a soft delete leaves: the files, and the row in the soft-deleted store.
    houseTableRepository.deleteById(
        HouseTablePrimaryKey.builder().databaseId(db).tableId("a").build(), false);
    assertFalse(hasRow(db, "a"));
    TableMetadata staged = staged(db);
    assertNotEquals(firstDirectory, staged.location());
    TreeSet<String> filesBefore = filesUnder(db);
    assertFalse(filesBefore.isEmpty());

    expectRefused(
        db,
        IrcWrite.commit(
            mvc,
            db,
            "a",
            IrcWrite.createCommit(at(staged, firstDirectory), Collections.emptyList())),
        "Cannot create table "
            + db
            + ".a: that directory belongs to the soft-deleted table "
            + db
            + ".a, which can be restored",
        null);
    assertEquals(filesBefore, filesUnder(db));
    // Remove what the soft delete kept, which no drop of this test reaches.
    for (String file : filesBefore) {
      Files.delete(new File(file).toPath());
    }
  }

  // ---------------------------------------------------------------- creates

  /**
   * A create, plain or staged, that names the renamed table's directory as its location: the server
   * owns locations (D30), so the table is created, or staged, in a directory of its own.
   */
  @Test
  void aCreateNamingALiveTablesLocationIsCreatedInADirectoryOfItsOwn() throws Exception {
    String db = "irc_reuse_loc";
    HouseTable first = liveRenamed(db);
    String firstDirectory = directory(first.getTableLocation());
    long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
    TreeSet<String> filesBefore = filesUnder(db);

    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc, db, ircCreate("a").withLocation(firstDirectory).stageCreate().build()));
    assertNotEquals(firstDirectory, staged.location());
    assertFirstTableIntact(db, first, filesBefore, snapshotId);

    track(db, "a");
    TableMetadata second =
        IrcWrite.parsed(
            IrcWrite.create(mvc, db, ircCreate("a").withLocation(firstDirectory).build()));
    assertNotEquals(firstDirectory, second.location());
    assertNotEquals(first.getTableUUID(), row(db, "a").getTableUUID());

    assertEquals(204, IrcWrite.drop(mvc, db, "a").getResponse().getStatus());
    assertFirstTableIntact(db, first, filesBefore, snapshotId);
  }

  /**
   * A create whose properties carry the renamed table's {@code openhouse.tableUUID}, in the two
   * forms the native create accepts it in (with a file that exists in that directory as {@code
   * openhouse.tableLocation}, or as a replicated table's create, which skips that check): the UUID
   * names the directory, which the server owns, so the property is not read and the table is
   * created in a directory of its own, under a UUID of its own.
   */
  @Test
  void aCreateCarryingALiveTablesUuidIsCreatedInADirectoryOfItsOwn() throws Exception {
    for (boolean replica : new boolean[] {false, true}) {
      String db = "irc_reuse_uuid" + (replica ? "_r" : "");
      HouseTable first = liveRenamed(db);
      String firstDirectory = directory(first.getTableLocation());
      long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
      TreeSet<String> filesBefore = filesUnder(db);

      track(db, "a");
      TableMetadata second =
          IrcWrite.parsed(
              IrcWrite.create(
                  mvc,
                  db,
                  ircCreate("a").setProperties(uuidProperties(db, first, replica)).build()));
      assertNotEquals(firstDirectory, second.location(), "replica=" + replica);
      HouseTable secondRow = row(db, "a");
      assertNotEquals(first.getTableUUID(), secondRow.getTableUUID());
      assertEquals(secondRow.getTableUUID(), second.properties().get("openhouse.tableUUID"));
      assertNotEquals(firstDirectory, directory(secondRow.getTableLocation()));

      assertEquals(204, IrcWrite.drop(mvc, db, "a").getResponse().getStatus());
      assertFirstTableIntact(db, first, filesBefore, snapshotId);
    }
  }

  /**
   * The staged create with those properties is staged in a directory of its own, so the completion
   * a client then sends (it sends the staged location back) is not in the first table's.
   */
  @Test
  void aStagedCreateCarryingALiveTablesUuidIsStagedInADirectoryOfItsOwn() throws Exception {
    for (boolean replica : new boolean[] {false, true}) {
      String db = "irc_reuse_suuid" + (replica ? "_r" : "");
      HouseTable first = liveRenamed(db);
      String firstDirectory = directory(first.getTableLocation());
      long snapshotId = IrcWrite.load(mvc, db, "b").currentSnapshot().snapshotId();
      TreeSet<String> filesBefore = filesUnder(db);

      TableMetadata staged =
          IrcWrite.parsed(
              IrcWrite.create(
                  mvc,
                  db,
                  ircCreate("a")
                      .setProperties(uuidProperties(db, first, replica))
                      .stageCreate()
                      .build()));
      assertNotEquals(firstDirectory, staged.location(), "replica=" + replica);
      assertNotEquals(first.getTableUUID(), staged.properties().get("openhouse.tableUUID"));

      track(db, "a");
      TableMetadata completed =
          IrcWrite.parsed(
              IrcWrite.commit(
                  mvc, db, "a", IrcWrite.createCommit(staged, Collections.emptyList())));
      assertEquals(staged.location(), completed.location());
      assertEquals(204, IrcWrite.drop(mvc, db, "a").getResponse().getStatus());
      assertFirstTableIntact(db, first, filesBefore, snapshotId);
    }
  }
}
