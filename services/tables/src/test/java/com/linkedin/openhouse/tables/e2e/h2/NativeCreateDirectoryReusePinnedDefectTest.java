package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.obtainSnapshotRefsFromSnapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.IcebergSnapshotsRequestBody;
import com.linkedin.openhouse.tables.common.TableType;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PINNED DEFECT of the native API (LinkedIn's line is the behaviour reference, and a defect found
 * in it is pinned by a test and reported, not changed). These tests assert what the native API does
 * today, which is wrong. A fix must change them deliberately; nothing here says the behaviour is
 * wanted.
 *
 * <p>The defect: a native create can be given the directory of a live table, and the drop of the
 * table it creates then deletes the live table's files.
 *
 * <p>A table's files live in the directory the server allocates, {@code
 * <root>/<database>/<table>-<uuid>}, and the drop deletes that directory. A rename changes the
 * house-table row's key and leaves the directory, so after {@code db.a} is renamed to {@code db.b}
 * the directory {@code <root>/db/a-<uuid>} is {@code db.b}'s. The create takes the UUID of the new
 * table from the request, and nothing asks whether a house-table row already uses it. Any principal
 * who may create tables in the database can send any of the four requests below (no privilege on
 * {@code db.b} is checked); each creates a second {@code db.a} in {@code db.b}'s directory:
 *
 * <ol>
 *   <li>the create whose properties carry {@code openhouse.tableUUID}, with {@code
 *       openhouse.tableLocation} naming any file that exists in that directory: the path check
 *       ({@code TableUUIDGenerator}, {@code Storage.isPathValid}) asks only that the file exists
 *       under {@code <root>/<openhouse.databaseId>/<openhouse.tableId>-<uuid>}, and the renamed
 *       table's own metadata file does;
 *   <li>the same with {@code openhouse.isTableReplicated=true}, which skips the path check;
 *   <li>the create of a {@code REPLICA_TABLE}, which skips it too;
 *   <li>the create with data (the snapshots {@code PUT} on the initial version, as a CTAS sends
 *       it), which takes the UUID from the directory its first snapshot's manifest list lies in.
 * </ol>
 *
 * <p>After each: both rows point into one directory; the drop of {@code db.a} answers 204 and
 * deletes every file in it; {@code db.b}'s row is left pointing at a metadata file that is gone,
 * and reading {@code db.b} answers 500.
 *
 * <p>The Iceberg REST Catalog routes do not have this defect (see {@link
 * IrcDirectoryReuseRoutesTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class NativeCreateDirectoryReusePinnedDefectTest {
  private static final FileIO IO = new HadoopFileIO(new Configuration());

  @Autowired MockMvc mvc;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  /** The native test table without policies, partitioning or clustering. */
  private static CreateUpdateTableRequestBody plain(String db, String table) {
    return newTable(db, table)
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private static String directory(String metadataLocation) {
    return metadataLocation.substring(0, metadataLocation.lastIndexOf('/'));
  }

  /** Every file under {@code directory} (checksum files aside). */
  private static TreeSet<String> filesUnder(String directory) throws Exception {
    File root = new File(directory);
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

  /** The GET response of {@code db.table}. */
  private String stored(String db, String table) throws Exception {
    ResultActions got = get(mvc, db, table);
    assertEquals(200, CommitPathHttp.status(got), json(got));
    return json(got);
  }

  /** One snapshot appending {@code dataPath}, written under the table {@code getJson} describes. */
  private static Snapshot append(String getJson, String dataPath) throws Exception {
    TableMetadata metadata = TableMetadataParser.read(IO, str(getJson, "$.tableLocation"));
    return NativeCtas.table(metadata, IO)
        .newAppend()
        .appendFile(
            com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities
                .createDummyDataFile(dataPath, metadata.spec()))
        .apply();
  }

  /**
   * The first table: {@code db.a} with one committed snapshot, renamed to {@code db.b}, all through
   * the native API. Returns {@code db.b}'s GET response.
   */
  private String liveRenamed(String db) throws Exception {
    created.add(new String[] {db, "a"});
    assertEquals(201, CommitPathHttp.status(post(mvc, plain(db, "a"))));
    String a = stored(db, "a");
    String snapshot = SnapshotParser.toJson(append(a, "/tmp/" + db + "_a_first.orc"));
    assertEquals(
        200,
        CommitPathHttp.status(
            putSnapshots(
                mvc,
                db,
                "a",
                IcebergSnapshotsRequestBody.builder()
                    .baseTableVersion(str(a, "$.tableLocation"))
                    .jsonSnapshots(Collections.singletonList(snapshot))
                    .snapshotRefs(obtainSnapshotRefsFromSnapshot(snapshot))
                    .createUpdateTableRequestBody(bodyFromGet(a))
                    .build())));
    assertEquals(204, CommitPathHttp.status(rename(mvc, db, "a", db, "b")));
    created.add(new String[] {db, "b"});
    assertEquals(404, CommitPathHttp.status(get(mvc, db, "a")));
    String b = stored(db, "b");
    // The renamed table keeps its UUID and its directory, which carries the old name.
    assertEquals(str(a, "$.tableUUID"), str(b, "$.tableUUID"));
    assertTrue(
        str(b, "$.tableLocation").contains("/" + db + "/a-" + str(b, "$.tableUUID") + "/"),
        str(b, "$.tableLocation"));
    return b;
  }

  /** The house-table spelling of a metadata location: without the local storage's scheme. */
  private static String path(String tableLocation) {
    return tableLocation.startsWith("file:")
        ? tableLocation.substring("file:".length())
        : tableLocation;
  }

  /**
   * What the defect is, after the second {@code db.a} was created ({@code second} is the create's
   * 201 response): it is a second table in the first one's directory, and its drop destroys the
   * first one's files.
   */
  private void assertTheSecondTableSharesTheDirectoryAndItsDropDestroysTheFirst(
      String db, String first, ResultActions second) throws Exception {
    assertEquals(201, CommitPathHttp.status(second), json(second));
    String secondJson = json(second);
    String firstDirectory = directory(path(str(first, "$.tableLocation")));

    // Two live tables, one UUID, one directory.
    assertEquals(str(first, "$.tableUUID"), str(secondJson, "$.tableUUID"));
    assertEquals(firstDirectory, directory(path(str(secondJson, "$.tableLocation"))));
    assertEquals(str(first, "$.tableLocation"), str(stored(db, "b"), "$.tableLocation"));
    TreeSet<String> filesBefore = filesUnder(firstDirectory);
    assertTrue(filesBefore.contains(path(str(first, "$.tableLocation"))), filesBefore.toString());

    // The drop of the second table deletes the directory, the first table's files with it.
    assertEquals(204, CommitPathHttp.status(delete(mvc, db, "a")));
    assertEquals(new TreeSet<String>(), filesUnder(firstDirectory));
    assertFalse(new File(path(str(first, "$.tableLocation"))).exists());

    // The first table's row is still there, pointing at a file that is gone: reading it is a 500.
    ResultActions read = get(mvc, db, "b");
    assertEquals(500, CommitPathHttp.status(read), json(read));
    assertTrue(message(read).contains("Failed to open input stream for file"), message(read));
  }

  private static Map<String, String> with(CreateUpdateTableRequestBody body, String... keyValues) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    for (int i = 0; i < keyValues.length; i += 2) {
      properties.put(keyValues[i], keyValues[i + 1]);
    }
    return properties;
  }

  // ---------------------------------------------------------------- the four requests

  /** Request 1: {@code openhouse.tableUUID} with a file of that directory as the location. */
  @Test
  void pinnedDefectCreateNamingTheUuidOfARenamedTableSharesItsDirectoryAndItsDropDestroysIt()
      throws Exception {
    String db = "native_reuse_uuid";
    String first = liveRenamed(db);
    CreateUpdateTableRequestBody body = plain(db, "a");
    ResultActions second =
        post(
            mvc,
            body.toBuilder()
                .tableProperties(
                    with(
                        body,
                        "openhouse.tableUUID",
                        str(first, "$.tableUUID"),
                        "openhouse.databaseId",
                        db,
                        "openhouse.tableId",
                        "a",
                        // Any file that exists in the directory passes the path check: here the
                        // renamed table's own current metadata file.
                        "openhouse.tableLocation",
                        path(str(first, "$.tableLocation"))))
                .build());
    assertTheSecondTableSharesTheDirectoryAndItsDropDestroysTheFirst(db, first, second);
  }

  /** Request 2: the same UUID as a replicated table's create; no file is named or checked. */
  @Test
  void pinnedDefectReplicatedCreateNamingTheUuidOfARenamedTableSkipsThePathCheck()
      throws Exception {
    String db = "native_reuse_replicated";
    String first = liveRenamed(db);
    CreateUpdateTableRequestBody body = plain(db, "a");
    ResultActions second =
        post(
            mvc,
            body.toBuilder()
                .tableProperties(
                    with(
                        body,
                        "openhouse.tableUUID",
                        str(first, "$.tableUUID"),
                        "openhouse.databaseId",
                        db,
                        "openhouse.tableId",
                        "a",
                        "openhouse.isTableReplicated",
                        "true",
                        "last-updated-ms",
                        Long.toString(System.currentTimeMillis() - 1000)))
                .build());
    assertTheSecondTableSharesTheDirectoryAndItsDropDestroysTheFirst(db, first, second);
  }

  /** Request 3: the same UUID on the create of a replica table; no file is named or checked. */
  @Test
  void pinnedDefectReplicaTableCreateNamingTheUuidOfARenamedTableSkipsThePathCheck()
      throws Exception {
    String db = "native_reuse_replica";
    String first = liveRenamed(db);
    CreateUpdateTableRequestBody body = plain(db, "a");
    ResultActions second =
        post(
            mvc,
            body.toBuilder()
                .tableType(TableType.REPLICA_TABLE)
                .tableProperties(
                    with(
                        body,
                        "openhouse.tableUUID",
                        str(first, "$.tableUUID"),
                        "openhouse.databaseId",
                        db,
                        "openhouse.tableId",
                        "a"))
                .build());
    assertTheSecondTableSharesTheDirectoryAndItsDropDestroysTheFirst(db, first, second);
  }

  /**
   * Request 4: the create with data. No UUID is named: the snapshot's manifest list lies in the
   * renamed table's directory (a client can write there, or name a manifest list that is already
   * there), and the create puts the table where the manifest list is.
   */
  @Test
  void pinnedDefectCreateWithDataWhoseSnapshotLiesInARenamedTablesDirectorySharesIt()
      throws Exception {
    String db = "native_reuse_ctas";
    String first = liveRenamed(db);
    String snapshot = SnapshotParser.toJson(append(first, "/tmp/" + db + "_a_second.orc"));
    CreateUpdateTableRequestBody body = plain(db, "a");
    ResultActions second =
        putSnapshots(
            mvc,
            db,
            "a",
            IcebergSnapshotsRequestBody.builder()
                .baseTableVersion(INITIAL_TABLE_VERSION)
                .jsonSnapshots(Collections.singletonList(snapshot))
                .snapshotRefs(obtainSnapshotRefsFromSnapshot(snapshot))
                .createUpdateTableRequestBody(
                    body.toBuilder()
                        .baseTableVersion(INITIAL_TABLE_VERSION)
                        .tableProperties(
                            with(
                                body,
                                "openhouse.databaseId",
                                db,
                                "openhouse.tableId",
                                "a",
                                "openhouse.tableLocation",
                                path(str(first, "$.tableLocation"))))
                        .build())
                .build());
    assertTheSecondTableSharesTheDirectoryAndItsDropDestroysTheFirst(db, first, second);
  }
}
