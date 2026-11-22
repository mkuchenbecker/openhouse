package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Black box: an unmodified Iceberg 1.5.2 {@link RESTCatalog}, loaded as any engine loads a catalog,
 * writes over real HTTP to the embedded tables service, and after every step the existing OpenHouse
 * Java client's view of the table equals the {@code RESTCatalog}'s (the stored metadata, aside from
 * {@code last-updated-ms}, which the 1.5.2 client restamps when it parses a response).
 *
 * <p>Data files are metadata-only (their paths are never read), since the point is what reaches the
 * catalog; reading data back through Spark is covered by {@link IcebergRestSparkSqlTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestCatalogWriteTest extends OpenHouseSparkITest {
  private static final String DB = "irc_write";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()),
          Types.NestedField.optional(3, "p", Types.StringType.get()));

  private Catalog openhouse;
  private RESTCatalog rest;
  private RESTCatalog rest2;

  @BeforeAll
  void start() throws Exception {
    getSparkSession();
    openhouse = javaCatalog();
    rest = restCatalog();
    rest2 = restCatalog();
    for (String t :
        Arrays.asList(
            "plain",
            "parted",
            "ops",
            "refs",
            "rtas",
            "staged",
            "staged_data",
            "dropped",
            "ren_a",
            "ren_b",
            "race",
            "race_strict",
            "refused",
            "refused2")) {
      try {
        openhouse.dropTable(TableIdentifier.of(DB, t));
      } catch (RuntimeException ignored) {
        // not there
      }
    }
  }

  @AfterAll
  void stop() throws Exception {
    rest.close();
    rest2.close();
  }

  private RESTCatalog restCatalog() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", getOpenHouseLocalServerURI() + "/iceberg");
    properties.put(
        "token", new DummyTokenInterceptor.DummySecurityJWT("irc_writer").buildNoopJWT());
    return (RESTCatalog)
        CatalogUtil.loadCatalog(
            RESTCatalog.class.getName(),
            "openhouse",
            properties,
            getSparkSession().sparkContext().hadoopConfiguration());
  }

  private Catalog javaCatalog() throws Exception {
    return getOpenHouseCatalog(getSparkSession());
  }

  private static TableIdentifier id(String table) {
    return TableIdentifier.of(DB, table);
  }

  private static TableMetadata current(Table table) {
    return ((HasTableOperations) table).operations().current();
  }

  private static String withoutLastUpdated(TableMetadata metadata) {
    return TableMetadataParser.toJson(metadata)
        .replaceFirst("\"last-updated-ms\"\\s*:\\s*\\d+", "\"last-updated-ms\":0");
  }

  /** The OpenHouse client and a fresh RESTCatalog load show the same table. */
  private void assertSameView(String table) {
    TableMetadata viaRest = current(rest.loadTable(id(table)));
    TableMetadata viaOpenHouse = current(openhouse.loadTable(id(table)));
    Assertions.assertEquals(withoutLastUpdated(viaOpenHouse), withoutLastUpdated(viaRest));
    Assertions.assertEquals(viaOpenHouse.metadataFileLocation(), viaRest.metadataFileLocation());
  }

  private static DataFile file(Table table, String name, String partition) {
    DataFiles.Builder builder =
        DataFiles.builder(table.spec())
            .withPath(table.location() + "/data/" + name + ".parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(10)
            .withRecordCount(1);
    if (table.spec().isPartitioned()) {
      builder.withPartitionPath("p=" + partition);
    }
    return builder.build();
  }

  private static Set<String> files(Table table) throws Exception {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      return StreamSupport.stream(tasks.spliterator(), false)
          .map(task -> task.file().path().toString())
          .collect(Collectors.toSet());
    }
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  // ---------------------------------------------------------------- create

  @Test
  void createUnpartitionedAndPartitioned() {
    Table plain = rest.createTable(id("plain"), SCHEMA);
    Assertions.assertFalse(plain.spec().isPartitioned());
    assertSameView("plain");

    Table parted =
        rest.createTable(
            id("parted"),
            SCHEMA,
            PartitionSpec.builderFor(SCHEMA).identity("p").build(),
            Collections.singletonMap("user.key", "v"));
    Assertions.assertEquals("p", parted.spec().fields().get(0).name());
    Assertions.assertEquals("v", parted.properties().get("user.key"));
    // The server owns the location and the UUID, and stamps its own properties.
    Assertions.assertTrue(
        parted.location().contains("/" + DB + "/parted-" + current(parted).uuid()));
    Assertions.assertEquals(DB, parted.properties().get("openhouse.databaseId"));
    assertSameView("parted");
    Assertions.assertTrue(rest.tableExists(id("parted")));
    Assertions.assertTrue(
        rest.listTables(org.apache.iceberg.catalog.Namespace.of(DB)).contains(id("parted")));
  }

  // ---------------------------------------------------------------- commits

  @Test
  void appendOverwriteDeleteSchemaPropertiesAndSortOrder() throws Exception {
    Table table =
        rest.createTable(id("ops"), SCHEMA, PartitionSpec.builderFor(SCHEMA).identity("p").build());
    DataFile a = file(table, "a", "x");
    DataFile b = file(table, "b", "y");
    table.newAppend().appendFile(a).appendFile(b).commit();
    assertSameView("ops");

    DataFile c = file(table, "c", "x");
    table.newOverwrite().deleteFile(a).addFile(c).commit();
    assertSameView("ops");
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList(b.path().toString(), c.path().toString())), files(table));

    DeleteFile positions =
        FileMetadata.deleteFileBuilder(table.spec())
            .ofPositionDeletes()
            .withPath(table.location() + "/data/del.parquet")
            .withFormat(FileFormat.PARQUET)
            .withFileSizeInBytes(10)
            .withRecordCount(1)
            .withPartitionPath("p=x")
            .build();
    table.newRowDelta().addDeletes(positions).commit();
    Assertions.assertEquals("overwrite", table.currentSnapshot().operation());
    Assertions.assertEquals(
        "1", openhouse.loadTable(id("ops")).currentSnapshot().summary().get("added-delete-files"));
    assertSameView("ops");

    table.updateSchema().addColumn("extra", Types.IntegerType.get()).commit();
    table.updateSchema().renameColumn("data", "payload").commit();
    Assertions.assertNotNull(table.schema().findField("payload"));
    assertSameView("ops");

    table.updateProperties().set("user.a", "1").set("user.b", "2").commit();
    table.updateProperties().remove("user.a").commit();
    Assertions.assertFalse(table.properties().containsKey("user.a"));
    Assertions.assertEquals("2", table.properties().get("user.b"));
    assertSameView("ops");

    table.replaceSortOrder().asc("id").commit();
    Assertions.assertTrue(table.sortOrder().isSorted());
    assertSameView("ops");

    table.newDelete().deleteFile(b).commit();
    assertSameView("ops");
    Assertions.assertEquals(
        Collections.singleton(c.path().toString()), files(openhouse.loadTable(id("ops"))));
  }

  @Test
  void branchesAndTags() {
    Table table = rest.createTable(id("refs"), SCHEMA);
    table.newAppend().appendFile(file(table, "a", null)).commit();
    long first = table.currentSnapshot().snapshotId();
    table.manageSnapshots().createBranch("dev", first).createTag("v1", first).commit();
    table.newAppend().appendFile(file(table, "b", null)).toBranch("dev").commit();
    Table reloaded = openhouse.loadTable(id("refs"));
    Assertions.assertEquals(first, reloaded.refs().get("v1").snapshotId());
    Assertions.assertNotEquals(first, reloaded.refs().get("dev").snapshotId());
    Assertions.assertEquals(first, reloaded.currentSnapshot().snapshotId());
    assertSameView("refs");
  }

  @Test
  void replaceTransaction() {
    Table table =
        rest.createTable(
            id("rtas"),
            SCHEMA,
            PartitionSpec.unpartitioned(),
            Collections.singletonMap("replace.enabled", "true"));
    table.newAppend().appendFile(file(table, "a", null)).commit();
    Schema replacement =
        new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "data", Types.StringType.get()),
            Types.NestedField.optional(3, "p", Types.StringType.get()),
            Types.NestedField.optional(4, "added", Types.LongType.get()));
    Transaction replace = rest.buildTable(id("rtas"), replacement).replaceTransaction();
    replace.newAppend().appendFile(file(replace.table(), "r", null)).commit();
    replace.commitTransaction();
    Table after = openhouse.loadTable(id("rtas"));
    Assertions.assertNotNull(after.schema().findField("added"));
    Assertions.assertEquals(
        Collections.singletonList(after.location() + "/data/r.parquet"),
        StreamSupport.stream(
                after.currentSnapshot().addedDataFiles(after.io()).spliterator(), false)
            .map(f -> f.path().toString())
            .collect(Collectors.toList()));
    Assertions.assertEquals(current(table).uuid(), current(after).uuid());
    assertSameView("rtas");
  }

  @Test
  void replaceIsRefusedWhenTheTableDoesNotEnableIt() {
    rest.createTable(id("refused2"), SCHEMA);
    Transaction replace = rest.buildTable(id("refused2"), SCHEMA).replaceTransaction();
    Throwable t = thrown(replace::commitTransaction);
    Assertions.assertTrue(t instanceof BadRequestException, t.toString());
    Assertions.assertTrue(
        t.getMessage().contains("REPLACE TABLE AS SELECT is not enabled"), t.getMessage());
  }

  @Test
  void stagedCreateThenCommit() throws Exception {
    Transaction create =
        rest.buildTable(id("staged"), SCHEMA).withProperty("user.k", "v").createTransaction();
    Assertions.assertFalse(rest.tableExists(id("staged")));
    Assertions.assertFalse(openhouse.tableExists(id("staged")));
    create.updateProperties().set("user.later", "x").commit();
    create.commitTransaction();
    Table created = openhouse.loadTable(id("staged"));
    Assertions.assertEquals("v", created.properties().get("user.k"));
    Assertions.assertEquals("x", created.properties().get("user.later"));
    assertSameView("staged");

    Transaction withData = rest.buildTable(id("staged_data"), SCHEMA).createTransaction();
    withData.newAppend().appendFile(file(withData.table(), "a", null)).commit();
    withData.commitTransaction();
    Table loaded = openhouse.loadTable(id("staged_data"));
    Assertions.assertEquals(
        Collections.singleton(loaded.location() + "/data/a.parquet"), files(loaded));
    assertSameView("staged_data");
  }

  @Test
  void dropAndRename() {
    rest.createTable(id("dropped"), SCHEMA);
    Assertions.assertTrue(rest.dropTable(id("dropped")));
    Assertions.assertFalse(rest.tableExists(id("dropped")));
    Assertions.assertFalse(openhouse.tableExists(id("dropped")));
    Assertions.assertFalse(rest.dropTable(id("dropped")));

    rest.createTable(id("ren_a"), SCHEMA);
    String uuid = current(rest.loadTable(id("ren_a"))).uuid();
    rest.renameTable(id("ren_a"), id("ren_b"));
    Assertions.assertFalse(openhouse.tableExists(id("ren_a")));
    Assertions.assertEquals(uuid, current(openhouse.loadTable(id("ren_b"))).uuid());
    assertSameView("ren_b");
    Throwable missing = thrown(() -> rest.renameTable(id("ren_a"), id("ren_c")));
    Assertions.assertTrue(missing instanceof NoSuchTableException, missing.toString());
  }

  // ---------------------------------------------------------------- concurrency

  /** Two writers append from the same base: the second retries on the new base; nothing is lost. */
  @Test
  void twoWritersRacingOnOneTableLoseNothing() throws Exception {
    rest.createTable(id("race"), SCHEMA);
    Table one = rest.loadTable(id("race"));
    Table two = rest2.loadTable(id("race"));
    AppendFiles first = one.newAppend().appendFile(file(one, "one", null));
    AppendFiles second = two.newAppend().appendFile(file(two, "two", null));
    first.commit();
    second.commit(); // refused once with 409, then retried by the client on the refreshed table
    Table after = openhouse.loadTable(id("race"));
    Assertions.assertEquals(
        new HashSet<>(
            Arrays.asList(
                after.location() + "/data/one.parquet", after.location() + "/data/two.parquet")),
        files(after));
    List<Snapshot> snapshots =
        StreamSupport.stream(after.snapshots().spliterator(), false).collect(Collectors.toList());
    Assertions.assertEquals(2, snapshots.size());
    assertSameView("race");
  }

  /** With retries off, the second writer fails with a commit conflict and changes nothing. */
  @Test
  void aWriterThatDoesNotRetryGetsACommitConflict() throws Exception {
    rest.createTable(
        id("race_strict"),
        SCHEMA,
        PartitionSpec.unpartitioned(),
        Collections.singletonMap("commit.retry.num-retries", "0"));
    Table one = rest.loadTable(id("race_strict"));
    Table two = rest2.loadTable(id("race_strict"));
    one.newAppend().appendFile(file(one, "one", null)).commit();
    String before = current(openhouse.loadTable(id("race_strict"))).metadataFileLocation();
    Throwable t = thrown(() -> two.newAppend().appendFile(file(two, "two", null)).commit());
    Assertions.assertTrue(t instanceof CommitFailedException, t.toString());
    Assertions.assertEquals(
        before, current(openhouse.loadTable(id("race_strict"))).metadataFileLocation());
  }

  // ---------------------------------------------------------------- refused

  @Test
  void refusedWrites() {
    Throwable location =
        thrown(
            () -> rest.buildTable(id("refused"), SCHEMA).withLocation("/tmp/elsewhere").create());
    Assertions.assertTrue(location instanceof BadRequestException, location.toString());
    Assertions.assertTrue(location.getMessage().contains("location"), location.getMessage());
    Assertions.assertFalse(openhouse.tableExists(id("refused")));

    Table table = rest.createTable(id("refused"), SCHEMA);
    Throwable exists = thrown(() -> rest.createTable(id("refused"), SCHEMA));
    Assertions.assertTrue(exists instanceof AlreadyExistsException, exists.toString());

    Throwable reserved =
        thrown(() -> table.updateProperties().set("openhouse.tableId", "x").commit());
    Assertions.assertTrue(reserved instanceof BadRequestException, reserved.toString());
    Throwable move = thrown(() -> table.updateLocation().setLocation("/tmp/elsewhere").commit());
    Assertions.assertTrue(move instanceof BadRequestException, move.toString());
    Throwable dropColumn = thrown(() -> table.updateSchema().deleteColumn("data").commit());
    Assertions.assertTrue(dropColumn instanceof BadRequestException, dropColumn.toString());
    Throwable evolve = thrown(() -> table.updateSpec().addField("p").commit());
    Assertions.assertTrue(evolve instanceof BadRequestException, evolve.toString());
    assertSameView("refused");
  }
}
