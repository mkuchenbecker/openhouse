package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The metadata location's limit is the cluster's to state ({@code
 * cluster.housetables.metadata-location-max-length}), for a House Tables store that is wider than
 * the production one: the store the House Tables service creates from its bootstrap {@code
 * schema.sql} declares {@code metadata_location VARCHAR(512)}. With the property at 512, a location
 * of 256 characters and one of 512 fit, and one of 513 is refused before anything is written, with
 * the configured limit in the message. {@link MetadataLocationLimitTest} shows the production
 * width, 255; {@link HouseTableRowLimitsUnsetTest} the default, no check.
 *
 * <p>The cluster of this test is such a deployment: the property is 512, and the H2 stand-in's
 * columns that hold the location are widened to 512 for the test, as that store's column is. Its
 * storage root is chosen so that the shortest location (one-character names) is exactly 256
 * characters; the longest names a create may carry (128 characters each) then give 510, and a table
 * that compresses its metadata, whose file name is three characters longer, reaches 512 and 513.
 *
 * <p>Lengths are of the location as stored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {MetadataLocationConfiguredLimitTest.WiderStore.class})
public class MetadataLocationConfiguredLimitTest {
  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);
  private static final String CODEC = "write.metadata.compression-codec";

  /**
   * {@code <root>/<db>/<table>-<uuid>/00000-<uuid>.metadata.json} is the root, the two names and 96
   * characters more, so a root of 158 characters makes one-character names a location of 256.
   */
  private static final String ROOT = name("/tmp/mll512_", 158);

  /** A local-storage cluster rooted at {@link #ROOT} whose House Tables store holds 512. */
  static class WiderStore extends PropertyOverrideContextInitializer {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      super.initialize(applicationContext);
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "cluster.housetables.metadata-location-max-length=512",
          "cluster.storages.default-type=local",
          "cluster.storages.types.local.endpoint=file:",
          "cluster.storages.types.local.root-path=" + ROOT);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTableRepository;
  @Autowired HouseTableRowLimits houseTableRowLimits;
  @Autowired ToggleStatusesRepository toggles;
  @Autowired DataSource dataSource;

  private final List<String[]> created = new ArrayList<>();
  private final List<String[]> createdViews = new ArrayList<>();

  /** Makes the stand-in the store this cluster says it has: 512 characters of location. */
  @BeforeEach
  void widenTheStandInsLocationColumns() {
    setLocationColumnsTo(512);
  }

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
    setLocationColumnsTo(255);
    if (new File(ROOT).exists()) {
      try (Stream<Path> paths = Files.walk(new File(ROOT).toPath())) {
        paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
  }

  /**
   * The stand-in keeps the location twice, as the location and as the version it reports. It also
   * keeps the table's URI ({@code <cluster>.<database>.<table>}), a column the store does not have,
   * which two 128-character names overflow at the stand-in's 255.
   */
  private void setLocationColumnsTo(int width) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "select TABLE_NAME, COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS"
                + " where upper(TABLE_NAME) = 'HOUSE_TABLE'"
                + " and upper(COLUMN_NAME) in ('TABLE_LOCATION', 'TABLE_VERSION', 'TABLE_URI')");
    assertEquals(3, columns.size(), "the stand-in's location columns: " + columns);
    for (Map<String, Object> column : columns) {
      jdbc.execute(
          String.format(
              "alter table %s alter column %s set data type varchar(%d)",
              column.get("TABLE_NAME"), column.get("COLUMN_NAME"), width));
    }
  }

  // ---------------------------------------------------------------- fixtures

  private static String name(String prefix, int length) {
    return prefix + String.join("", Collections.nCopies(length - prefix.length(), "x"));
  }

  private static CreateUpdateTableRequestBody plain(String db, String table) {
    return newTable(db, table)
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private static CreateUpdateTableRequestBody compressed(String db, String table) {
    CreateUpdateTableRequestBody body = plain(db, table);
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(CODEC, "gzip");
    return body.toBuilder().tableProperties(properties).build();
  }

  private static CreateTableRequest.Builder ircCreate(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .withPartitionSpec(PartitionSpec.unpartitioned());
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

  /** Every file and directory under the storage root. */
  private static TreeSet<String> stored() throws Exception {
    File root = new File(ROOT);
    if (!root.exists()) {
      return new TreeSet<>();
    }
    try (Stream<Path> paths = Files.walk(root.toPath())) {
      return paths.map(Path::toString).collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static String tooLong(String entity, int length) {
    return entity
        + " cannot be written: its metadata location would be "
        + length
        + " characters long and House Tables holds at most 512"
        + " (user_table_row.metadata_location). The location is made of the storage root, the"
        + " database name, the name and UUID it was created with, and the metadata file's name.";
  }

  // ---------------------------------------------------------------- tests

  @Test
  void theClusterStatesItsStoresWidth() {
    assertEquals(512, houseTableRowLimits.getMetadataLocationMaxLength());
  }

  @Test
  void aCreateAt256AndAt512FitsAndAt513IsRefusedWithNothingWritten() throws Exception {
    // 256: the shortest location of this cluster, one over the default limit.
    track("a", "t");
    assertEquals(201, status(post(mvc, plain("a", "t"))));
    assertEquals(256, row("a", "t").getTableLocation().length());

    // 512: the longest database name, a table name one short of it, and compressed metadata.
    String db = name("d", 128);
    String fits = name("f", 127);
    track(db, fits);
    assertEquals(201, status(post(mvc, compressed(db, fits))));
    assertEquals(512, row(db, fits).getTableLocation().length());
    assertEquals(200, status(get(mvc, db, fits)));

    // 513: one character more.
    String over = name("o", 128);
    TreeSet<String> before = stored();
    String refusal = tooLong("Table " + db + "." + over, 513);
    assertEquals(400, status(post(mvc, compressed(db, over))));
    assertEquals(refusal, message(post(mvc, compressed(db, over))));
    assertEquals(
        refusal,
        assertIrcError(
                IrcWrite.create(
                    mvc,
                    db,
                    ircCreate(over).setProperties(Collections.singletonMap(CODEC, "gzip")).build()),
                400,
                "BadRequestException")
            .message());
    assertFalse(hasRow(db, over));
    assertEquals(before, stored());
  }

  /** The IRC staged create and the view routes read the same configured limit. */
  @Test
  void theStagedCreateAndTheViewRoutesUseTheConfiguredLimitToo() throws Exception {
    String db = name("d", 128);
    track(db, "t");
    assertEquals(201, status(post(mvc, plain(db, "t"))));

    // A staged create over the default 255 is answered, and one at 513 is refused.
    String over = name("o", 128);
    TableMetadata staged =
        IrcWrite.parsed(IrcWrite.create(mvc, db, ircCreate(over).stageCreate().build()));
    assertTrue(staged.location().length() > 255);
    MvcResult refused =
        IrcWrite.create(
            mvc,
            db,
            ircCreate(over)
                .setProperties(Collections.singletonMap(CODEC, "gzip"))
                .stageCreate()
                .build());
    assertEquals(
        tooLong("Table " + db + "." + over, 513),
        assertIrcError(refused, 400, "BadRequestException").message());

    // A view's location cannot reach 512 here (510 at most); 510 is over the default and fits.
    NativeViewHttp.enableViews(toggles, db);
    String view = name("v", 128);
    createdViews.add(new String[] {db, view});
    assertEquals(
        201,
        NativeViewHttp.code(
            NativeViewHttp.post(
                mvc,
                NativeViewHttp.createBody(
                    db, view, NativeViewHttp.SCHEMA_ID_NAME, "select 1", Collections.emptyMap()))));
    assertEquals(510, row(db, view).getTableLocation().length());
  }

  private void track(String db, String table) {
    created.add(new String[] {db, table});
  }
}
