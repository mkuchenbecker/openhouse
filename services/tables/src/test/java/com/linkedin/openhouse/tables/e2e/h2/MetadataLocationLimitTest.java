package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PINNED DEFECT (work item V15), as it is before the fix: what the service answers, and what it
 * leaves on storage, when a write's house-table row will not fit House Tables.
 *
 * <p>The row holds the metadata location in 255 characters and the table name in 255 ({@code
 * user_table_row.metadata_location} and {@code table_id}, both {@code VARCHAR(255)} in {@code
 * services/housetables/ddl/0000__baseline.sql}; the H2 stand-in these tests run on has the same
 * widths). The location is {@code <table directory>/<5-digit version>-<uuid>.metadata.json}, and
 * the table directory is {@code <storage root>/<database>/<table>-<uuid>}, so it grows with the two
 * names (each at most 128 characters) and the storage root. The service writes the metadata file
 * first and the row second, so a row that does not fit fails after the file is written.
 *
 * <p>Lengths are of the location as stored, which for this storage has no scheme; the native GET
 * reports it with {@code file:} in front.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class MetadataLocationLimitTest {
  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);
  private static final String CODEC = "write.metadata.compression-codec";

  /** What the pinned answers have in common: the row write's failure, as the store words it. */
  private static final String ROW_WRITE_FAILED = "could not execute statement";

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTableRepository;
  @Autowired ToggleStatusesRepository toggles;

  private final List<String[]> created = new ArrayList<>();
  private final List<String[]> createdViews = new ArrayList<>();
  /** Database name to its directory on the test storage. */
  private final Map<String, String> directories = new HashMap<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    for (String[] key : createdViews) {
      NativeViewHttp.delete(mvc, key[0], key[1]);
    }
    created.clear();
    createdViews.clear();
    // The files the failed writes left behind.
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

  private static CreateUpdateTableRequestBody withProperty(
      CreateUpdateTableRequestBody body, String key, String value) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(key, value);
    return body.toBuilder().tableProperties(properties).build();
  }

  private MvcResult nativeView(String db, String view) throws Exception {
    return NativeViewHttp.post(
        mvc,
        NativeViewHttp.createBody(
            db, view, NativeViewHttp.SCHEMA_ID_NAME, "select 1", Collections.emptyMap()));
  }

  private static CreateViewRequest ircView(String db, String view) {
    return IrcViewWrite.createRequest(
        view,
        new Schema(Types.NestedField.optional(1, "id", Types.LongType.get())),
        "select 1",
        Namespace.of(db),
        Collections.emptyMap());
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
   * A database in which a table or view with a name of {@code nameLength} characters has a first
   * metadata location of exactly 255 characters. Measured on a table created in a probe database,
   * because the storage root's length is the test environment's.
   */
  private String databaseWhere255IsANameOf(String prefix, int nameLength) throws Exception {
    String probeDb = prefix + "p";
    track(probeDb, "t");
    assertEquals(201, status(post(mvc, plain(probeDb, "t"))));
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

  /**
   * Every file and directory under the database's directory, as paths below it with UUIDs masked.
   */
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

  /** What {@code after} holds that {@code before} did not. */
  private static List<String> added(TreeSet<String> before, TreeSet<String> after) {
    return after.stream().filter(path -> !before.contains(path)).collect(Collectors.toList());
  }

  // ---------------------------------------------------------------- creates

  @Test
  void aNativeCreateOneCharacterOverTheLimitIs503AndLeavesItsMetadataFile() throws Exception {
    String db = databaseWhere255IsANameOf("mll_nc_", 60);
    String fits = name("a", 60);
    track(db, fits);
    assertEquals(201, status(post(mvc, plain(db, fits))));
    assertEquals(255, row(db, fits).getTableLocation().length());

    String over = name("b", 61);
    TreeSet<String> before = stored(db);
    ResultActions answer = post(mvc, plain(db, over));

    expectError(answer, 503, "isn't ack'ed by server about the commit status");
    expectError(answer, 503, ROW_WRITE_FAILED);
    assertFalse(hasRow(db, over));
    assertEquals(404, status(get(mvc, db, over)));
    assertEquals(
        Arrays.asList(over + "-<uuid>", over + "-<uuid>/00000-<uuid>.metadata.json"),
        added(before, stored(db)));
  }

  @Test
  void anIrcCreateOneCharacterOverTheLimitIs500CommitStateUnknownAndLeavesItsMetadataFile()
      throws Exception {
    String db = databaseWhere255IsANameOf("mll_ic_", 60);
    String fits = name("a", 60);
    track(db, fits);
    assertEquals(200, IrcWrite.create(mvc, db, ircCreate(fits).build()).getResponse().getStatus());
    assertEquals(255, row(db, fits).getTableLocation().length());

    String over = name("b", 61);
    TreeSet<String> before = stored(db);
    MvcResult answer = IrcWrite.create(mvc, db, ircCreate(over).build());

    assertTrue(
        assertIrcError(answer, 500, "CommitStateUnknownException")
            .message()
            .contains(ROW_WRITE_FAILED));
    assertFalse(hasRow(db, over));
    assertEquals(
        Arrays.asList(over + "-<uuid>", over + "-<uuid>/00000-<uuid>.metadata.json"),
        added(before, stored(db)));
  }

  @Test
  void anIrcStagedCreateOverTheLimitIsAnsweredAndItsCompletionIs500() throws Exception {
    String db = databaseWhere255IsANameOf("mll_is_", 60);
    String over = name("b", 61);
    TreeSet<String> before = stored(db);

    TableMetadata staged =
        IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(over).stageCreate().build()));
    assertEquals(before, stored(db));
    MvcResult completion =
        IrcWrite.commit(mvc, db, over, IrcWrite.createCommit(staged, Collections.emptyList()));

    assertIrcError(completion, 500, "CommitStateUnknownException");
    assertFalse(hasRow(db, over));
    assertEquals(
        Arrays.asList(over + "-<uuid>", over + "-<uuid>/00000-<uuid>.metadata.json"),
        added(before, stored(db)));
  }

  @Test
  void aNativeCreateWithDataOverTheLimitIs500AndLeavesAMetadataFile() throws Exception {
    String db = databaseWhere255IsANameOf("mll_nd_", 60);
    String over = name("b", 61);

    // The staged create writes its metadata file and no row, over the limit or not; the snapshots
    // PUT that completes it lets the unknown commit state through as a plain 500.
    Path dataFile = Files.createTempFile("mll_ctas", ".orc");
    dataFile.toFile().deleteOnExit();
    ResultActions answer = NativeCtas.createWithData(mvc, plain(db, over), dataFile.toString());

    expectError(answer, 500, "CommitStateUnknownException");
    expectError(answer, 500, ROW_WRITE_FAILED);
    assertFalse(hasRow(db, over));
    assertEquals(
        2,
        new File(directories.get(db))
            .listFiles()[0].listFiles((dir, file) -> file.endsWith(".metadata.json")).length,
        "the staged create's metadata file and the failed create's");
  }

  // ---------------------------------------------------------------- commits

  /**
   * A later commit can cross the limit although the create did not: the file name is three
   * characters longer once the table compresses its metadata ({@code .gz.metadata.json}), and one
   * longer from version 100000 on.
   */
  @Test
  void aNativeCommitThatCrossesTheLimitIs503LeavesItsFileAndBlocksTheTableForAWhile()
      throws Exception {
    String db = databaseWhere255IsANameOf("mll_nu_", 60);
    String table = name("a", 60);
    track(db, table);
    assertEquals(201, status(post(mvc, plain(db, table))));
    String before = json(get(mvc, db, table));
    TreeSet<String> storedBefore = stored(db);

    ResultActions answer = put(mvc, withProperty(bodyFromGet(before), CODEC, "gzip"));

    expectError(answer, 503, ROW_WRITE_FAILED);
    expectUnchanged(mvc, before, db, table);
    assertEquals(
        Collections.singletonList(table + "-<uuid>/00001-<uuid>.gz.metadata.json"),
        added(storedBefore, stored(db)));
    // The failed commit stays in the catalog's retry cache, which answers any commit on the same
    // base as stale for five minutes: a change that fits is refused too.
    expectError(
        put(mvc, withProperty(bodyFromGet(before), "user.note", "fits")),
        409,
        "is stale, please consider retry from application");
  }

  @Test
  void anIrcCommitThatCrossesTheLimitIs500CommitStateUnknownAndLeavesItsFile() throws Exception {
    String db = databaseWhere255IsANameOf("mll_iu_", 60);
    String table = name("a", 60);
    track(db, table);
    assertEquals(201, status(post(mvc, plain(db, table))));
    TableMetadata base = IrcWrite.load(mvc, db, table);
    TreeSet<String> storedBefore = stored(db);

    MvcResult answer =
        IrcWrite.commit(
            mvc,
            db,
            table,
            base,
            TableMetadata.buildFrom(base)
                .setProperties(Collections.singletonMap(CODEC, "gzip"))
                .build());

    assertIrcError(answer, 500, "CommitStateUnknownException");
    assertEquals(base.metadataFileLocation(), IrcWrite.load(mvc, db, table).metadataFileLocation());
    assertEquals(
        Collections.singletonList(table + "-<uuid>/00001-<uuid>.gz.metadata.json"),
        added(storedBefore, stored(db)));
  }

  // ---------------------------------------------------------------- rename

  /** The rename target has no length check; the create's 128 does not apply to it. */
  @Test
  void aRenameToANameOverTheRowsLimitIs500AndLeavesItsMetadataFile() throws Exception {
    String db = databaseWhere255IsANameOf("mll_rn_", 100);
    for (String source : new String[] {"a", "b", "c"}) {
      track(db, source);
      assertEquals(201, status(post(mvc, plain(db, source))));
    }
    String fits = name("f", 255);
    assertEquals(204, status(rename(mvc, db, "a", db, fits)));
    track(db, fits);
    assertEquals(fits, row(db, fits).getTableId());

    TreeSet<String> before = stored(db);
    String over = name("o", 256);
    expectError(rename(mvc, db, "b", db, over), 500, "CommitStateUnknownException");
    assertFalse(hasRow(db, over));
    assertEquals(200, status(get(mvc, db, "b")));
    assertEquals(
        Collections.singletonList("b-<uuid>/00001-<uuid>.metadata.json"),
        added(before, stored(db)));

    before = stored(db);
    MvcResult irc = IrcWrite.rename(mvc, TableIdentifier.of(db, "c"), TableIdentifier.of(db, over));
    assertEquals(500, irc.getResponse().getStatus(), IrcHttp.body(irc));
    assertFalse(hasRow(db, over));
    assertEquals(
        Collections.singletonList("c-<uuid>/00001-<uuid>.metadata.json"),
        added(before, stored(db)));
  }

  /**
   * PINNED DEFECT of the native API, not part of V15 and not changed by it (decision D9): a rename
   * to a name longer than the create's 128 characters succeeds, and the table can then not be
   * updated through the native PUT, whose body may not carry such a name.
   */
  @Test
  void aTableRenamedToMoreThan128CharactersCanNoLongerBeUpdatedNatively() throws Exception {
    String db = databaseWhere255IsANameOf("mll_rp_", 100);
    track(db, "a");
    assertEquals(201, status(post(mvc, plain(db, "a"))));
    String longer = name("l", 129);
    assertEquals(204, status(rename(mvc, db, "a", db, longer)));
    track(db, longer);

    expectError(
        put(mvc, withProperty(bodyFromGet(json(get(mvc, db, longer))), "user.note", "x")),
        400,
        "CreateUpdateTableRequestBody.tableId : size must be between 0 and 128");
  }

  // ---------------------------------------------------------------- views

  @Test
  void aViewCreateOneCharacterOverTheLimitIs500AndLeavesItsMetadataFile() throws Exception {
    String db = databaseWhere255IsANameOf("mll_vw_", 60);
    NativeViewHttp.enableViews(toggles, db);
    // A view needs its database to exist, and a database exists while it holds a table.
    track(db, "t");
    assertEquals(201, status(post(mvc, plain(db, "t"))));
    String fits = name("a", 60);
    createdViews.add(new String[] {db, fits});
    assertEquals(201, NativeViewHttp.code(nativeView(db, fits)));
    assertEquals(255, row(db, fits).getTableLocation().length());

    String over = name("b", 61);
    TreeSet<String> before = stored(db);
    MvcResult answer = nativeView(db, over);
    assertEquals(500, NativeViewHttp.code(answer), NativeViewHttp.body(answer));
    assertEquals("Unexpected view service failure", str(NativeViewHttp.body(answer), "$.message"));
    assertFalse(hasRow(db, over));
    assertEquals(
        Arrays.asList(over + "-<uuid>", over + "-<uuid>/00001-<uuid>.metadata.json"),
        added(before, stored(db)));

    String overIrc = name("c", 61);
    before = stored(db);
    MvcResult irc = IrcViewWrite.create(mvc, db, ircView(db, overIrc));
    assertIrcError(irc, 500, "ServiceFailureException");
    assertFalse(hasRow(db, overIrc));
    assertEquals(
        Arrays.asList(overIrc + "-<uuid>", overIrc + "-<uuid>/00001-<uuid>.metadata.json"),
        added(before, stored(db)));
  }
}
