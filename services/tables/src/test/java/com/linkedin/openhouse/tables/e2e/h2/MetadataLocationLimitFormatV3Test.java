package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.formatversion.IcebergV3;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A format v3 table's metadata location is held to the House Tables row's limit like any other
 * table's ({@link MetadataLocationLimitTest} shows the v2 cases): on a cluster with the format v3
 * switch on, a v3 create at 255 fits and at 256 is refused on both APIs, and a commit to a v3 table
 * that would cross the limit is refused; each refusal writes nothing. Format v3 needs the Iceberg
 * 1.12 line, so these tests run only where Iceberg builds v3, as the other format v3 tests do.
 *
 * <p>Lengths are of the location as stored.
 */
@SpringBootTest(properties = SWITCH_ON)
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class MetadataLocationLimitFormatV3Test {
  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);
  private static final String CODEC = "write.metadata.compression-codec";

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired HouseTableRepository houseTableRepository;

  private final List<String[]> created = new ArrayList<>();
  /** Database name to its directory on the test storage. */
  private final Map<String, String> directories = new HashMap<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    for (String directory : directories.values()) {
      if (new File(directory).exists()) {
        try (Stream<Path> paths = Files.walk(new File(directory).toPath())) {
          paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
      }
    }
    directories.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private static CreateUpdateTableRequestBody plainV3(String db, String table) {
    return asking(
        newTable(db, table)
            .toBuilder()
            .policies(null)
            .timePartitioning(null)
            .clustering(null)
            .build(),
        "3");
  }

  private static CreateTableRequest ircCreateV3(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .withPartitionSpec(PartitionSpec.unpartitioned())
        .setProperties(Collections.singletonMap(FORMAT_VERSION, "3"))
        .build();
  }

  /** A name of exactly {@code length} characters that starts with {@code prefix}. */
  private static String name(String prefix, int length) {
    return prefix + String.join("", Collections.nCopies(length - prefix.length(), "x"));
  }

  private HouseTable row(String db, String table) {
    return houseTableRepository
        .findEntityById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .orElseThrow(() -> new AssertionError("no house-table row for " + db + "." + table));
  }

  private boolean hasRow(String db, String table) {
    return houseTableRepository
        .findEntityById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .isPresent();
  }

  private void track(String db, String table) {
    created.add(new String[] {db, table});
  }

  /**
   * A database in which a v3 table with a name of {@code nameLength} characters has a first
   * metadata location of exactly 255 characters, measured on a v3 table in a probe database.
   */
  private String databaseWhere255IsANameOf(String prefix, int nameLength) throws Exception {
    String probeDb = prefix + "p";
    track(probeDb, "t");
    assertEquals(201, status(post(mvc, plainV3(probeDb, "t"))));
    assertEquals(3, metadata(catalog, probeDb, "t").formatVersion());
    // <root>/<db>/<table>-<uuid>/00000-<uuid>.metadata.json
    String location = row(probeDb, "t").getTableLocation();
    String probeDirectory = location.substring(0, location.indexOf("/t-"));
    String root = probeDirectory.substring(0, probeDirectory.length() - probeDb.length());
    int withoutNames = location.length() - probeDb.length() - "t".length();
    String db = name(prefix, 255 - withoutNames - nameLength);
    assertTrue(db.length() <= 128, "the storage root is too deep for this test: " + location);
    directories.put(probeDb, probeDirectory);
    directories.put(db, root + db);
    return db;
  }

  /** Every file and directory under the database's directory, with UUIDs masked. */
  private TreeSet<String> stored(String db) throws Exception {
    File root = new File(directories.get(db));
    if (!root.exists()) {
      return new TreeSet<>();
    }
    try (Stream<Path> paths = Files.walk(root.toPath())) {
      return paths
          .filter(path -> !path.equals(root.toPath()))
          .map(path -> root.toPath().relativize(path).toString())
          .filter(path -> !path.endsWith(".crc"))
          .map(path -> path.replaceAll("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", "<uuid>"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static String tooLong(String entity, int length) {
    return entity
        + " cannot be written: its metadata location would be "
        + length
        + " characters long and House Tables holds at most 255"
        + " (user_table_row.metadata_location). The location is made of the storage root, the"
        + " database name, the name and UUID it was created with, and the metadata file's name.";
  }

  // ---------------------------------------------------------------- tests

  @Test
  void aNativeV3CreateAt255FitsAndAt256IsRefusedWithNothingWritten() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = databaseWhere255IsANameOf("mllv3_nc_", 60);
    String fits = name("a", 60);
    track(db, fits);
    assertEquals(201, status(post(mvc, plainV3(db, fits))));
    assertEquals(3, metadata(catalog, db, fits).formatVersion());
    assertEquals(255, row(db, fits).getTableLocation().length());

    String over = name("b", 61);
    TreeSet<String> before = stored(db);
    ResultActions answer = post(mvc, plainV3(db, over));
    assertEquals(400, status(answer), json(answer));
    assertEquals(tooLong("Table " + db + "." + over, 256), message(answer));
    assertFalse(hasRow(db, over));
    assertEquals(404, status(get(mvc, db, over)));
    assertEquals(before, stored(db));
  }

  @Test
  void anIrcV3CreateAt255FitsAndAt256IsRefusedWithNothingWritten() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = databaseWhere255IsANameOf("mllv3_ic_", 60);
    String fits = name("a", 60);
    track(db, fits);
    assertEquals(200, IrcWrite.create(mvc, db, ircCreateV3(fits)).getResponse().getStatus());
    assertEquals(3, metadata(catalog, db, fits).formatVersion());
    assertEquals(255, row(db, fits).getTableLocation().length());

    String over = name("b", 61);
    TreeSet<String> before = stored(db);
    assertEquals(
        tooLong("Table " + db + "." + over, 256),
        assertIrcError(IrcWrite.create(mvc, db, ircCreateV3(over)), 400, "BadRequestException")
            .message());
    assertFalse(hasRow(db, over));
    assertEquals(before, stored(db));
  }

  /** Compressed metadata makes the next file name three characters longer: 258 on a v3 table. */
  @Test
  void aCommitThatTakesAV3TableOverTheLimitIsRefusedAndTheNextCommitLands() throws Exception {
    assumeTrue(IcebergV3.supported());
    String db = databaseWhere255IsANameOf("mllv3_nu_", 60);
    String table = name("a", 60);
    track(db, table);
    assertEquals(201, status(post(mvc, plainV3(db, table))));
    String before = json(get(mvc, db, table));
    TreeSet<String> storedBefore = stored(db);

    ResultActions answer = put(mvc, with(bodyFromGet(before), CODEC, "gzip"));
    assertEquals(400, status(answer), json(answer));
    assertEquals(tooLong("Table " + db + "." + table, 258), message(answer));
    expectUnchanged(mvc, before, db, table);
    assertEquals(storedBefore, stored(db));

    assertEquals(200, status(put(mvc, with(bodyFromGet(before), "user.note", "fits"))));
    assertEquals(3, metadata(catalog, db, table).formatVersion());
    assertEquals(255, row(db, table).getTableLocation().length());
  }
}
