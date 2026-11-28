package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * A create with data (CTAS: a create transaction with one append) through an unmodified Iceberg
 * {@link RESTCatalog}, over HTTP, can never be given the directory of another table.
 *
 * <p>The first table is created, written and renamed through the same catalog, so its directory
 * still carries its first name. The second is a create transaction of that first name which asks
 * for the first table's directory in each way the catalog's builder offers: as the location, and as
 * the properties of the first table, among them {@code openhouse.tableUUID}, which the native
 * create reads a table's UUID from. Either way the transaction is staged, written and committed in
 * a directory of its own, and its drop leaves the first table as it was. (See {@link
 * IrcDirectoryReuseRoutesTest} for the requests a catalog does not send.)
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogDirectoryReuseTest {
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

  @LocalServerPort private int port;

  private RESTCatalog rest;
  private String db;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_reuse").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      for (String table : new String[] {"a", "b"}) {
        if (db != null && rest.tableExists(TableIdentifier.of(db, table))) {
          rest.dropTable(TableIdentifier.of(db, table));
        }
      }
    } finally {
      rest.close();
    }
  }

  private static DataFile file(Table table, String name) {
    return DataFiles.builder(table.spec())
        .withPath(table.location() + "/data/" + name + ".orc")
        .withFileSizeInBytes(10)
        .withRecordCount(2)
        .build();
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

  /** {@code db.a} with one append, renamed to {@code db.b}: the first table. */
  private Table liveRenamed() {
    Table a = rest.createTable(TableIdentifier.of(db, "a"), SCHEMA, PartitionSpec.unpartitioned());
    a.newFastAppend().appendFile(file(a, "first")).commit();
    rest.renameTable(TableIdentifier.of(db, "a"), TableIdentifier.of(db, "b"));
    Table b = rest.loadTable(TableIdentifier.of(db, "b"));
    assertTrue(
        b.location().endsWith("/" + db + "/a-" + b.properties().get("openhouse.tableUUID")),
        b.location());
    return b;
  }

  /**
   * The create transaction of {@code db.a} with one append, built with {@code location} and {@code
   * properties}; then its drop. The first table is untouched throughout.
   */
  private void ctasThenDropLeavesTheFirstTable(
      Table first, String location, Map<String, String> properties) throws Exception {
    long snapshotId = first.currentSnapshot().snapshotId();
    TreeSet<String> filesBefore = filesUnder(first.location());
    assertFalse(filesBefore.isEmpty());

    Transaction create =
        rest.buildTable(TableIdentifier.of(db, "a"), SCHEMA)
            .withLocation(location)
            .withProperties(properties)
            .createTransaction();
    // Where the client writes the new table's files: the location the staged create answered.
    assertNotEquals(first.location(), create.table().location());
    create.newFastAppend().appendFile(file(create.table(), "second")).commit();
    create.commitTransaction();

    Table second = rest.loadTable(TableIdentifier.of(db, "a"));
    assertNotEquals(first.location(), second.location());
    assertNotEquals(
        first.properties().get("openhouse.tableUUID"),
        second.properties().get("openhouse.tableUUID"));
    assertTrue(
        second.currentSnapshot().manifestListLocation().startsWith(second.location() + "/"),
        second.currentSnapshot().manifestListLocation());
    assertEquals(filesBefore, filesUnder(first.location()));

    assertTrue(rest.dropTable(TableIdentifier.of(db, "a")));
    assertEquals(filesBefore, filesUnder(first.location()));
    Table reloaded = rest.loadTable(TableIdentifier.of(db, "b"));
    assertEquals(snapshotId, reloaded.currentSnapshot().snapshotId());
    assertEquals(1, reloaded.currentSnapshot().allManifests(reloaded.io()).size());
  }

  /** The builder's {@code withLocation}: ignored, as on every IRC create. */
  @Test
  void aCtasNamingALiveTablesLocationIsWrittenAndCreatedInADirectoryOfItsOwn() throws Exception {
    db = "irc_reuse_rest_loc";
    Table first = liveRenamed();
    ctasThenDropLeavesTheFirstTable(first, first.location(), new HashMap<>());
  }

  /**
   * The builder's {@code withProperties}, carrying the first table's {@code openhouse.tableUUID}
   * and what the native create wants beside it: once as a replicated table's create (no path
   * check), once with a file that exists in the first table's directory.
   */
  @Test
  void aCtasCarryingALiveTablesUuidIsWrittenAndCreatedInADirectoryOfItsOwn() throws Exception {
    for (boolean replica : new boolean[] {false, true}) {
      db = "irc_reuse_rest_uuid" + (replica ? "_r" : "");
      Table first = liveRenamed();
      Map<String, String> properties = new HashMap<>();
      properties.put("openhouse.tableUUID", first.properties().get("openhouse.tableUUID"));
      properties.put("openhouse.databaseId", db);
      properties.put("openhouse.tableId", "a");
      if (replica) {
        properties.put("openhouse.isTableReplicated", "true");
        properties.put("last-updated-ms", Long.toString(System.currentTimeMillis() - 1000));
      } else {
        properties.put(
            "openhouse.tableLocation", first.properties().get("openhouse.tableLocation"));
      }
      ctasThenDropLeavesTheFirstTable(first, null, properties);
      rest.dropTable(TableIdentifier.of(db, "b"));
    }
  }
}
