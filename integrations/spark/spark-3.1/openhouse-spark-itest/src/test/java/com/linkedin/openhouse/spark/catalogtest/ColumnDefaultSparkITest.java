package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SparkItestColumnDefaults;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.Table;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Catalog → ReadBridge overlay → Spark's Iceberg table. The Spark ORC row reader fills {@code
 * initial-default} (linkedin/iceberg#267–269), so the ORC backfill cases run; Spark Parquet readers
 * do not, so the Parquet cases are disabled.
 *
 * <p>Every case has an expectation per Iceberg line ({@link IcebergLine}). The read bridge is the
 * legacy line's mechanism: there each case asserts the overlay and the filled rows. On the v3 line
 * the client neither overlays nor declares {@code column-defaults} (decisions D5 and D11a), so
 * creating a table that requires the capability is refused with the service's 422 {@code
 * CLIENT_CAPABILITY_MISSING}, and that refusal is what each case asserts there.
 */
public class ColumnDefaultSparkITest extends OpenHouseSparkITest {

  private static final String DATABASE = SparkItestColumnDefaults.DATABASE;
  private static final String CAPABILITY_PROP = "required-capability.column-defaults";

  @Test
  public void readBridgeOverlaysInitialDefaultAfterSparkAddColumn() throws Exception {
    String fqtn = "openhouse." + DATABASE + ".members_overlay";
    try (SparkSession spark = getSparkSession()) {
      spark.sql("DROP TABLE IF EXISTS " + fqtn);
      if (!createBridged(
          spark,
          fqtn,
          "CREATE TABLE "
              + fqtn
              + " (id bigint, name string) USING iceberg TBLPROPERTIES ('"
              + CAPABILITY_PROP
              + "'='true')")) {
        return;
      }
      spark.sql("INSERT INTO " + fqtn + " VALUES (1, 'Alice')");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN country string");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN tier int");
      spark.sql("REFRESH TABLE " + fqtn);

      Table table = Spark3Util.loadIcebergTable(spark, fqtn);
      assertEquals(
          "true",
          table.properties().get(CAPABILITY_PROP),
          "opt-in property missing; props=" + table.properties());
      assertEquals(
          "US",
          table.schema().findField("country").initialDefault(),
          "ReadBridge overlay missing on country");
      assertEquals(
          1,
          table.schema().findField("tier").initialDefault(),
          "ReadBridge overlay missing on tier");

      spark.sql("DROP TABLE " + fqtn);
    }
  }

  /**
   * Defaults on fields of structs inside list elements and map values reach Spark through the
   * overlay, and the ORC row reader fills them for rows written before the fields existed. With ORC
   * vectorization enabled, a projection with defaults still uses the row reader. The append goes
   * through the overlaid table, so write protection sees the nested {@code initial-default}.
   */
  @ParameterizedTest(name = "vectorized={0}")
  @ValueSource(booleans = {false, true})
  public void orcFillsDefaultsInsideListsAndMaps(boolean vectorized) throws Exception {
    String fqtn = String.format("openhouse.%s.members_collections_%s", DATABASE, vectorized);
    try (SparkSession spark = getSparkSession()) {
      spark.sql("DROP TABLE IF EXISTS " + fqtn);
      if (!createBridged(
          spark,
          fqtn,
          String.format(
              "CREATE TABLE %s (id bigint, tags array<struct<a:int>>,"
                  + " attrs map<string, struct<a:int>>) USING iceberg TBLPROPERTIES ("
                  + "'write.format.default'='orc', 'read.orc.vectorization.enabled'='%b', '%s'='true')",
              fqtn, vectorized, CAPABILITY_PROP))) {
        return;
      }
      spark.sql(
          "INSERT INTO "
              + fqtn
              + " VALUES (1, array(named_struct('a', 1)), map('k', named_struct('a', 2)))");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN tags.element.country string");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN attrs.value.tier int");
      spark.sql("REFRESH TABLE " + fqtn);
      spark.sql(
          "INSERT INTO "
              + fqtn
              + " VALUES (2, array(named_struct('a', 3, 'country', 'CA')),"
              + " map('k', named_struct('a', 4, 'tier', 5)))");

      assertRowsEqual(
          Arrays.asList(row(1L, "US", 1), row(2L, "CA", 5)),
          rows(spark, "SELECT id, tags[0].country, attrs['k'].tier FROM " + fqtn + " ORDER BY id"));

      spark.sql("DROP TABLE " + fqtn);
    }
  }

  @ParameterizedTest(name = "vectorized={0}")
  @ValueSource(booleans = {false, true})
  public void orcBackfillsColumnDefaultsViaReadBridge(boolean vectorized) throws Exception {
    assertBackfillViaReadBridge("orc", vectorized);
  }

  /** Enable once Spark Parquet readers fill {@code initial-default}; #267–269 added ORC only. */
  @Disabled("Spark Parquet readers do not fill initial-default")
  @ParameterizedTest(name = "vectorized={0}")
  @ValueSource(booleans = {false, true})
  public void parquetBackfillsColumnDefaultsViaReadBridge(boolean vectorized) throws Exception {
    assertBackfillViaReadBridge("parquet", vectorized);
  }

  private void assertBackfillViaReadBridge(String fileFormat, boolean vectorized) throws Exception {
    String vectorizationProp =
        "parquet".equals(fileFormat)
            ? "read.parquet.vectorization.enabled"
            : "read.orc.vectorization.enabled";
    String tableName = String.format("members_%s_%s", fileFormat, vectorized);
    String fqtn = String.format("openhouse.%s.%s", DATABASE, tableName);

    try (SparkSession spark = getSparkSession()) {
      spark.sql("DROP TABLE IF EXISTS " + fqtn);
      if (!createBridged(
          spark,
          fqtn,
          String.format(
              "CREATE TABLE %s (id bigint, name string) USING iceberg TBLPROPERTIES ("
                  + "'write.format.default'='%s', '%s'='%b', '%s'='true')",
              fqtn, fileFormat, vectorizationProp, vectorized, CAPABILITY_PROP))) {
        return;
      }
      spark.sql("INSERT INTO " + fqtn + " VALUES (1, 'Alice')");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN country string");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN tier int");
      spark.sql("INSERT INTO " + fqtn + " VALUES (2, 'Bob', 'CA', 5)");
      spark.sql(
          "INSERT INTO " + fqtn + " VALUES (3, 'Cleo', CAST(NULL AS string), CAST(NULL AS int))");

      List<Object[]> full =
          rows(spark, "SELECT id, name, country, tier FROM " + fqtn + " ORDER BY id");
      assertRowsEqual(
          Arrays.asList(
              row(1L, "Alice", "US", 1), row(2L, "Bob", "CA", 5), row(3L, "Cleo", null, null)),
          full);

      assertIds(spark, fqtn, "country = 'US'", 1L);
      assertIds(spark, fqtn, "country = 'CA'", 2L);
      assertIds(spark, fqtn, "upper(country) = 'US'", 1L);
      assertIds(spark, fqtn, "tier = 1", 1L);
      assertIds(spark, fqtn, "country IS NOT NULL", 1L, 2L);
      assertIds(spark, fqtn, "country IS NULL", 3L);

      List<Object[]> projected =
          rows(spark, "SELECT country FROM " + fqtn + " WHERE country = 'US'");
      assertRowsEqual(Collections.singletonList(row("US")), projected);

      spark.sql("DROP TABLE " + fqtn);
    }
  }

  /**
   * Creates a table that requires {@code column-defaults}. On the legacy line the Spark catalog
   * declares the capability and the table is created: returns true, and the caller goes on with the
   * legacy assertions. On the v3 line it declares none, so the service refuses the create with the
   * 422 {@code CLIENT_CAPABILITY_MISSING}: asserts that and returns false.
   */
  private static boolean createBridged(SparkSession spark, String fqtn, String createSql) {
    if (IcebergLine.current() == IcebergLine.LEGACY) {
      spark.sql(createSql);
      return true;
    }
    assertCapabilityMissing(fqtn.substring("openhouse.".length()), () -> spark.sql(createSql));
    return false;
  }

  /**
   * The statement fails with the service's 422 {@code CLIENT_CAPABILITY_MISSING} for {@code table}
   * ({@code db.table}), raised by the OpenHouse client as {@link
   * WebClientResponseWithMessageException}.
   */
  static void assertCapabilityMissing(String table, Executable statement) {
    Throwable thrown = null;
    try {
      statement.execute();
    } catch (Throwable t) {
      thrown = t;
    }
    assertNotNull(thrown, "expected the 422 CLIENT_CAPABILITY_MISSING, nothing was thrown");
    assertEquals(
        WebClientResponseWithMessageException.class, thrown.getClass(), String.valueOf(thrown));
    assertEquals(422, ((WebClientResponseWithMessageException) thrown).getStatusCode());
    assertTrue(
        thrown
            .getMessage()
            .contains(
                "CLIENT_CAPABILITY_MISSING: " + table + " requires the column-defaults capability"),
        thrown.getMessage());
  }

  private static void assertRowsEqual(List<Object[]> expectedRows, List<Object[]> actualRows) {
    assertEquals(expectedRows.size(), actualRows.size(), "row count");
    for (int row = 0; row < expectedRows.size(); row++) {
      Object[] expected = expectedRows.get(row);
      Object[] actual = actualRows.get(row);
      assertEquals(expected.length, actual.length, "column count at row " + row);
      for (int col = 0; col < expected.length; col++) {
        assertEquals(expected[col], actual[col], "row " + row + " col " + col);
      }
    }
  }

  private static List<Object[]> rows(SparkSession spark, String sql) {
    return spark.sql(sql).collectAsList().stream()
        .map(
            row -> {
              Object[] values = new Object[row.size()];
              for (int i = 0; i < row.size(); i++) {
                values[i] = row.isNullAt(i) ? null : row.get(i);
              }
              return values;
            })
        .collect(Collectors.toList());
  }

  private static Object[] row(Object... values) {
    return values;
  }

  private static void assertIds(
      SparkSession spark, String fqtn, String predicate, Object... expectedIds) {
    List<Object[]> result =
        rows(spark, "SELECT id FROM " + fqtn + " WHERE " + predicate + " ORDER BY id");
    assertEquals(
        Arrays.asList(expectedIds),
        result.stream().map(r -> r[0]).collect(Collectors.toList()),
        "filter: " + predicate);
  }
}
