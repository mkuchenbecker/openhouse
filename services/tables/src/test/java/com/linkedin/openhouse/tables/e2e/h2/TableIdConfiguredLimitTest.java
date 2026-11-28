package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.iceberg.catalog.TableIdentifier;
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
 * A rename's target name on a House Tables store narrower than the production one.
 *
 * <p>The store the House Tables service creates from its bootstrap {@code schema.sql} declares
 * {@code user_table_row.table_id VARCHAR(128)}; the production DDL ({@code
 * services/housetables/ddl/0000__baseline.sql}) declares {@code VARCHAR(255)}. A create's name is
 * at most 128 characters by request validation, but a rename's target has no such rule, so on the
 * narrower store a rename to 129 to 255 characters wrote its metadata file and then failed to write
 * its row.
 *
 * <p>The cluster of this test is such a deployment: the H2 stand-in's {@code TABLE_ID} column is
 * narrowed to 128 for the test, as that store's column is, and it states that width ({@code
 * cluster.housetables.table-id-max-length=128}). A rename to 129 characters is then refused before
 * anything is written. {@link MetadataLocationLimitTest} shows the production widths, 255: 255 fits
 * and 256 is refused; {@link HouseTableRowLimitsUnsetTest} shows the default, no check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {TableIdConfiguredLimitTest.NarrowerStore.class})
public class TableIdConfiguredLimitTest {

  private static final String ROOT = "/tmp/tidlimit128";

  /** A local-storage cluster rooted at {@link #ROOT}. */
  static class NarrowerStore extends PropertyOverrideContextInitializer {
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
      super.initialize(applicationContext);
      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "cluster.housetables.table-id-max-length=128",
          "cluster.storages.default-type=local",
          "cluster.storages.types.local.endpoint=file:",
          "cluster.storages.types.local.root-path=" + ROOT);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTableRepository;
  @Autowired DataSource dataSource;
  @Autowired HouseTableRowLimits houseTableRowLimits;

  private final List<String[]> created = new ArrayList<>();

  /** Makes the stand-in the store this cluster says it has: 128 characters of name. */
  @BeforeEach
  void narrowTheStandInsNameColumn() {
    setTableIdColumnTo(128);
  }

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
    setTableIdColumnTo(255);
    if (new File(ROOT).exists()) {
      try (Stream<Path> paths = Files.walk(new File(ROOT).toPath())) {
        paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
      }
    }
  }

  private void setTableIdColumnTo(int width) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            "select TABLE_NAME, COLUMN_NAME from INFORMATION_SCHEMA.COLUMNS"
                + " where upper(TABLE_NAME) = 'HOUSE_TABLE' and upper(COLUMN_NAME) = 'TABLE_ID'");
    assertEquals(1, columns.size(), "the stand-in's name column: " + columns);
    jdbc.execute(
        String.format(
            "alter table %s alter column %s set data type varchar(%d)",
            columns.get(0).get("TABLE_NAME"), columns.get(0).get("COLUMN_NAME"), width));
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

  /** Every file and directory under the storage root, checksum files aside. */
  private static TreeSet<String> stored() throws Exception {
    File root = new File(ROOT);
    if (!root.exists()) {
      return new TreeSet<>();
    }
    try (Stream<Path> paths = Files.walk(root.toPath())) {
      return paths
          .map(Path::toString)
          .filter(path -> !path.endsWith(".crc"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private void track(String db, String table) {
    created.add(new String[] {db, table});
  }

  // ---------------------------------------------------------------- tests

  /** The cluster states its store's width, and the service reads it. */
  @Test
  void theClusterStatesItsStoresWidth() {
    assertEquals(128, houseTableRowLimits.getTableIdMaxLength());
    assertEquals(0, houseTableRowLimits.getMetadataLocationMaxLength());
  }

  /**
   * A rename to 129 characters is refused with 400 on the native API and the IRC rename route,
   * before anything is written: no row of the new name, no new file, the table as it was. A later
   * rename to 128 characters, which fits, lands.
   */
  @Test
  void aRenameTo129CharactersIsRefusedOnBothApisWithNothingWritten() throws Exception {
    String db = "tid_128";
    for (String source : new String[] {"a", "b"}) {
      track(db, source);
      assertEquals(201, status(post(mvc, plain(db, source))));
    }
    String locationA = row(db, "a").getTableLocation();
    String locationB = row(db, "b").getTableLocation();
    TreeSet<String> before = stored();

    String over = name("o", 129);
    ResultActions refused = rename(mvc, db, "a", db, over);
    assertEquals(400, status(refused), json(refused));
    assertEquals(tooLong(db + ".a"), message(refused));
    assertEquals(
        tooLong(db + ".b"),
        assertIrcError(
                IrcWrite.rename(mvc, TableIdentifier.of(db, "b"), TableIdentifier.of(db, over)),
                400,
                "BadRequestException")
            .message());

    assertFalse(hasRow(db, over));
    assertEquals(before, stored());
    assertEquals(200, status(get(mvc, db, "a")));
    assertEquals(200, status(get(mvc, db, "b")));
    assertEquals(locationA, row(db, "a").getTableLocation());
    assertEquals(locationB, row(db, "b").getTableLocation());

    String fits = name("f", 128);
    assertEquals(204, status(rename(mvc, db, "a", db, fits)));
    track(db, fits);
    assertEquals(fits, row(db, fits).getTableId());
    assertFalse(hasRow(db, "a"));
  }

  private static String tooLong(String table) {
    return "Table "
        + table
        + " cannot be written under a name of 129 characters: House Tables holds at most 128"
        + " (user_table_row.table_id).";
  }
}
