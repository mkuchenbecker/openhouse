package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
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
import java.util.stream.Stream;
import javax.sql.DataSource;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * With neither {@code cluster.housetables.metadata-location-max-length} nor {@code
 * cluster.housetables.table-id-max-length} set, which is the default, the tables service checks no
 * length of a House Tables row: every write reaches House Tables as it did before the check
 * existed, and the store decides.
 *
 * <p>The store of this test is wider than the production one, as the one the House Tables service
 * creates from its bootstrap {@code schema.sql} is ({@code metadata_location VARCHAR(512)}): the H2
 * stand-in's location and name columns are widened to 512 for the test. On it a table whose
 * location is 300 characters is created and then takes commits, a view at 300 characters is
 * created, and a table is renamed to a name of 300 characters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {HouseTableRowLimitsUnsetTest.WiderStore.class})
public class HouseTableRowLimitsUnsetTest {

  /**
   * {@code <root>/<db>/<table>-<uuid>/00000-<uuid>.metadata.json} is the root, the two names and 96
   * characters more, so a root of 202 characters makes one-character names a location of 300.
   */
  private static final String ROOT = name("/tmp/hrlunset_", 202);

  /** A local-storage cluster rooted at {@link #ROOT} that states no House Tables width. */
  static class WiderStore extends PropertyOverrideContextInitializer {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      super.initialize(applicationContext);
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
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

  @BeforeEach
  void widenTheStandInsColumns() {
    setColumnsTo(512);
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
    setColumnsTo(255);
    if (new File(ROOT).exists()) {
      try (Stream<Path> paths = Files.walk(new File(ROOT).toPath())) {
        paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
  }

  /** The stand-in's columns that hold the location, the name and the table's URI. */
  private void setColumnsTo(int width) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "select TABLE_NAME, COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS"
                + " where upper(TABLE_NAME) = 'HOUSE_TABLE'"
                + " and upper(COLUMN_NAME) in"
                + " ('TABLE_LOCATION', 'TABLE_VERSION', 'TABLE_URI', 'TABLE_ID')");
    assertEquals(4, columns.size(), "the stand-in's columns: " + columns);
    for (Map<String, Object> column : columns) {
      jdbc.execute(
          String.format(
              "alter table %s alter column %s set data type varchar(%d)",
              column.get("TABLE_NAME"), column.get("COLUMN_NAME"), width));
    }
  }

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

  private static CreateUpdateTableRequestBody with(
      CreateUpdateTableRequestBody body, String key, String value) {
    Map<String, String> properties = new HashMap<>(body.getTableProperties());
    properties.put(key, value);
    return body.toBuilder().tableProperties(properties).build();
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

  // ---------------------------------------------------------------- tests

  @Test
  void noWidthIsStatedByDefault() {
    assertEquals(HouseTableRowLimits.NO_LIMIT, houseTableRowLimits.getMetadataLocationMaxLength());
    assertEquals(HouseTableRowLimits.NO_LIMIT, houseTableRowLimits.getTableIdMaxLength());
  }

  /**
   * A table whose location is 300 characters is created, and it takes commits after: a property
   * change and another, each storing a new location of the same length.
   */
  @Test
  void aTableAt300CharactersIsCreatedAndTakesCommits() throws Exception {
    created.add(new String[] {"a", "t"});
    ResultActions create = post(mvc, plain("a", "t"));
    assertEquals(201, status(create), json(create));
    String first = row("a", "t").getTableLocation();
    assertEquals(300, first.length());

    for (String value : new String[] {"1", "2"}) {
      ResultActions commit =
          put(mvc, with(bodyFromGet(json(get(mvc, "a", "t"))), "unset.limit.commit", value));
      assertEquals(200, status(commit), json(commit));
    }
    String third = row("a", "t").getTableLocation();
    assertEquals(300, third.length());
    assertFalse(first.equals(third), third);
  }

  /** A native view whose location is 300 characters is created. */
  @Test
  void aViewAt300CharactersIsCreated() throws Exception {
    created.add(new String[] {"w", "t"});
    assertEquals(201, status(post(mvc, plain("w", "t"))));
    NativeViewHttp.enableViews(toggles, "w");
    createdViews.add(new String[] {"w", "v"});
    assertEquals(
        201,
        NativeViewHttp.code(
            NativeViewHttp.post(
                mvc,
                NativeViewHttp.createBody(
                    "w", "v", NativeViewHttp.SCHEMA_ID_NAME, "select 1", Collections.emptyMap()))));
    assertEquals(300, row("w", "v").getTableLocation().length());
  }

  /** A table is renamed to a name of 300 characters, which the store holds. */
  @Test
  void aRenameTo300CharactersLands() throws Exception {
    created.add(new String[] {"r", "t"});
    assertEquals(201, status(post(mvc, plain("r", "t"))));
    String target = name("n", 300);
    ResultActions renamed = rename(mvc, "r", "t", "r", target);
    assertEquals(204, status(renamed), json(renamed));
    created.add(new String[] {"r", target});
    assertEquals(target, row("r", target).getTableId());
    assertFalse(hasRow("r", "t"));
    assertEquals(200, status(get(mvc, "r", target)));
  }
}
