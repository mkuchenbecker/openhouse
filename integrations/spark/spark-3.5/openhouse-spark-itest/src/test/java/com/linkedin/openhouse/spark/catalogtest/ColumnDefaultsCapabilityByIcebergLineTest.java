package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code column-defaults} capability by Iceberg line (decisions D5 and D11a, work item W3).
 *
 * <p>On the legacy line the Spark catalogs declare {@code column-defaults}, so a table that
 * requires it ({@code required-capability.column-defaults=true}) is read, written and evolved
 * through Spark SQL as before. On the v3 line they declare nothing and the client applies no
 * read-bridge overlay, so the service refuses such a table with its existing 422 {@code
 * CLIENT_CAPABILITY_MISSING} instead of letting old and new clients read it differently. A table
 * that requires nothing works on both lines.
 *
 * <p>The bridged table is created by a session whose catalog declares the capability through its
 * {@code header.X-OpenHouse-Client-Capabilities} property, standing in for a legacy client.
 */
public class ColumnDefaultsCapabilityByIcebergLineTest extends OpenHouseSparkITest {

  private static final String DB = "d1_capability_line";
  private static final String CAPABILITY_PROP = "required-capability.column-defaults";
  private static final String CAPABILITY_HEADER = "X-OpenHouse-Client-Capabilities";

  private static boolean legacyLine() {
    return IcebergLine.current() == IcebergLine.LEGACY;
  }

  /** Both Spark catalog paths declare {@code column-defaults} on the legacy line only. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "com.linkedin.openhouse.spark.OpenHouseCatalog",
        "com.linkedin.openhouse.spark.OpenHouseRestCatalog"
      })
  public void sparkCatalogDeclaresColumnDefaultsOnLegacyLineOnly(String catalogImpl)
      throws Exception {
    try (MockWebServer tables = new MockWebServer()) {
      tables.start();
      Map<String, String> properties = new HashMap<>();
      properties.put(CatalogProperties.URI, tables.url("/").toString());
      Catalog catalog =
          CatalogUtil.loadCatalog(
              catalogImpl, "openhouse", properties, new org.apache.hadoop.conf.Configuration());
      tables.enqueue(new MockResponse().setResponseCode(404));
      assertFalse(catalog.tableExists(TableIdentifier.of("db", "t")));
      RecordedRequest request = tables.takeRequest(5, TimeUnit.SECONDS);
      assertNotNull(request, "no request reached the tables service");
      assertEquals(legacyLine() ? "column-defaults" : null, request.getHeader(CAPABILITY_HEADER));
    }
  }

  /**
   * Read, write and schema change through Spark SQL on a table that requires {@code
   * column-defaults}: as before on the legacy line, the 422 on the v3 line, and the table is left
   * as it was.
   */
  @Test
  public void tableRequiringColumnDefaultsByIcebergLine() throws Exception {
    // Closed at the end, as the other suites do: a session left open makes later suites that
    // set static Spark configurations (DeleteFileReplicationTestSpark) reuse it.
    try (SparkSession spark = getSparkSession()) {
      SparkSession declaring = declaringSession(spark);
      String fqtn = "openhouse." + DB + ".bridged";
      declaring.sql("DROP TABLE IF EXISTS " + fqtn);
      declaring.sql(
          "CREATE TABLE "
              + fqtn
              + " (id bigint, name string) USING iceberg TBLPROPERTIES ('"
              + CAPABILITY_PROP
              + "'='true')");
      declaring.sql("INSERT INTO " + fqtn + " VALUES (1, 'a')");

      if (legacyLine()) {
        assertEquals(Collections.singletonList("1,a"), rows(spark, "SELECT * FROM " + fqtn));
        spark.sql("INSERT INTO " + fqtn + " VALUES (2, 'b')");
        spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN extra int");
        assertEquals(
            Arrays.asList("1,a,null", "2,b,null"),
            rows(spark, "SELECT * FROM " + fqtn + " ORDER BY id"));
      } else {
        String table = DB + ".bridged";
        assertCapabilityMissing(table, () -> spark.sql("SELECT * FROM " + fqtn).collectAsList());
        assertCapabilityMissing(table, () -> spark.sql("INSERT INTO " + fqtn + " VALUES (2, 'b')"));
        assertCapabilityMissing(
            table, () -> spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN extra int"));
        // Nothing reached the table: a declaring client still sees one row and two columns.
        assertEquals(Collections.singletonList("1,a"), rows(declaring, "SELECT * FROM " + fqtn));
      }
      declaring.sql("DROP TABLE " + fqtn);
    }
  }

  /** Creating a table that requires the capability: allowed on the legacy line, 422 on v3. */
  @Test
  public void createTableRequiringColumnDefaultsByIcebergLine() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String fqtn = "openhouse." + DB + ".bridged_create";
      declaringSession(spark).sql("DROP TABLE IF EXISTS " + fqtn);
      String create =
          "CREATE TABLE "
              + fqtn
              + " (id bigint) USING iceberg TBLPROPERTIES ('"
              + CAPABILITY_PROP
              + "'='true')";
      if (legacyLine()) {
        spark.sql(create);
        assertEquals("true", property(spark, fqtn, CAPABILITY_PROP));
        spark.sql("DROP TABLE " + fqtn);
      } else {
        assertCapabilityMissing(DB + ".bridged_create", () -> spark.sql(create));
        // The plain Java catalog requires nothing; an existing bridged table would be a 422 here.
        assertFalse(
            getOpenHouseCatalog(spark).tableExists(TableIdentifier.of(DB, "bridged_create")));
      }
    }
  }

  /** A table that requires nothing is read, written and evolved on both lines. */
  @Test
  public void plainTableWorksOnBothLines() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String fqtn = "openhouse." + DB + ".plain";
      spark.sql("DROP TABLE IF EXISTS " + fqtn);
      spark.sql("CREATE TABLE " + fqtn + " (id bigint, name string) USING iceberg");
      spark.sql("INSERT INTO " + fqtn + " VALUES (1, 'a')");
      spark.sql("ALTER TABLE " + fqtn + " ADD COLUMN extra int");
      spark.sql("INSERT INTO " + fqtn + " VALUES (2, 'b', 3)");
      assertEquals(
          Arrays.asList("1,a,null", "2,b,3"),
          rows(spark, "SELECT * FROM " + fqtn + " ORDER BY id"));
      spark.sql("DROP TABLE " + fqtn);
    }
  }

  /** A session whose {@code openhouse} catalog declares {@code column-defaults} explicitly. */
  private static SparkSession declaringSession(SparkSession spark) {
    SparkSession declaring = spark.newSession();
    declaring
        .conf()
        .set("spark.sql.catalog.openhouse.header." + CAPABILITY_HEADER, "column-defaults");
    return declaring;
  }

  private static String property(SparkSession spark, String fqtn, String key) {
    return spark.sql("SHOW TBLPROPERTIES " + fqtn + " ('" + key + "')").collectAsList().stream()
        .map(row -> row.getString(1))
        .findFirst()
        .orElse(null);
  }

  private static List<String> rows(SparkSession spark, String sql) {
    return spark.sql(sql).collectAsList().stream()
        .map(
            row -> {
              StringBuilder line = new StringBuilder();
              for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                  line.append(',');
                }
                line.append(row.isNullAt(i) ? "null" : String.valueOf(row.get(i)));
              }
              return line.toString();
            })
        .collect(Collectors.toList());
  }

  private static void assertCapabilityMissing(String table, Executable statement) {
    ColumnDefaultSparkITest.assertCapabilityMissing(table, statement);
  }
}
