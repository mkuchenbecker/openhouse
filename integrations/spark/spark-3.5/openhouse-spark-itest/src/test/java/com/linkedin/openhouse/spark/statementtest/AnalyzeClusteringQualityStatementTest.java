package com.linkedin.openhouse.spark.statementtest;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AnalyzeClusteringQualityStatementTest {

  private static SparkSession spark = null;

  /** Collapse the (metric, dimension, value) output into metric[/dimension] -> value. */
  private Map<String, String> analyze(String table) {
    Map<String, String> out = new HashMap<>();
    for (Row r :
        spark.sql("ANALYZE TABLE " + table + " COMPUTE CLUSTERING QUALITY").collectAsList()) {
      String metric = r.getString(0);
      String dimension = r.isNullAt(1) ? null : r.getString(1);
      out.put(dimension == null ? metric : metric + "/" + dimension, r.getString(2));
    }
    return out;
  }

  @Test
  public void testAnalyzeUnconfiguredReportsNotConfigured() {
    Map<String, String> m = analyze("openhouse.db.table");
    Assertions.assertEquals("false", m.get("clustering_configured"));
    // A not-configured table reports only the single flag row.
    Assertions.assertEquals(1, m.size());
  }

  @Test
  public void testAnalyzeAfterOptimizeReportsCoverageAndDepth() {
    spark
        .sql(
            "ALTER TABLE openhouse.db.table SET TBLPROPERTIES ("
                + "'optimize.cluster.keys' = 'id', "
                + "'optimize.cluster.sort-mode' = 'sort')")
        .show();
    spark.sql("OPTIMIZE openhouse.db.table").collect();

    Map<String, String> m = analyze("openhouse.db.table");
    Assertions.assertEquals("true", m.get("clustering_configured"));
    Assertions.assertEquals("id", m.get("keys"));
    Assertions.assertEquals("sort", m.get("sort_mode"));
    Assertions.assertNotNull(m.get("layout_id"));
    // After OPTIMIZE every live file carries the current layout stamp.
    Assertions.assertEquals("100.00", m.get("coverage_bytes_pct"));
    Assertions.assertEquals("100.00", m.get("coverage_files_pct"));
    Assertions.assertEquals("0", m.get("files_new"));
    Assertions.assertEquals("0", m.get("files_stale"));
    Assertions.assertEquals("0", m.get("files_damaged"));
    Assertions.assertEquals("0.0", m.get("unclustered_tail_hours"));
    Assertions.assertEquals("none", m.get("oldest_uncovered_seq"));
    // Per-key depth rows are emitted for the leading key.
    Assertions.assertNotNull(m.get("depth_avg/id"));
    Assertions.assertNotNull(m.get("depth_max/id"));
    Assertions.assertNotNull(m.get("depth_avg_covered/id"));
    // The persisted epoch history is echoed back.
    Assertions.assertTrue(m.get("epochs").trim().startsWith("["));
  }

  @Test
  public void testAnalyzeClassifiesNewStaleAndDamagedFiles() {
    spark
        .sql(
            "ALTER TABLE openhouse.db.table SET TBLPROPERTIES ("
                + "'optimize.cluster.keys' = 'id', "
                + "'optimize.cluster.sort-mode' = 'sort')")
        .show();
    spark.sql("OPTIMIZE openhouse.db.table").collect();

    // New: unstamped data above the watermark.
    spark.sql("INSERT INTO openhouse.db.table VALUES (7, 'd7', 'tableid')").show();
    Map<String, String> m = analyze("openhouse.db.table");
    Assertions.assertEquals("1", m.get("files_new"));
    Assertions.assertEquals("0", m.get("files_damaged"));
    Assertions.assertEquals("1", m.get("files_covered"));
    Assertions.assertNotEquals("none", m.get("oldest_uncovered_seq"));
    Assertions.assertTrue(Double.parseDouble(m.get("unclustered_tail_hours")) >= 0.0);

    // Stale: after a key change every previously clustered file carries an older layout.
    spark
        .sql("ALTER TABLE openhouse.db.table SET TBLPROPERTIES ('optimize.cluster.keys' = 'data')")
        .show();
    m = analyze("openhouse.db.table");
    Assertions.assertEquals("1", m.get("files_stale"));
    Assertions.assertEquals("0", m.get("files_covered"));
    Assertions.assertEquals("0.00", m.get("coverage_files_pct"));

    // Damaged: a foreign rewrite of clustered files leaves unstamped files at or below the
    // watermark. Recluster under the new keys first so there is a watermark to be below.
    spark.sql("OPTIMIZE openhouse.db.table FULL").collect();
    Assertions.assertEquals("0", analyze("openhouse.db.table").get("files_damaged"));
    spark
        .sql(
            "CALL openhouse.system.rewrite_data_files(table => 'db.table', "
                + "options => map('min-input-files', '1', 'rewrite-all', 'true', "
                + "'use-starting-sequence-number', 'true'))")
        .collect();
    m = analyze("openhouse.db.table");
    Assertions.assertEquals("0", m.get("files_covered"));
    Assertions.assertTrue(
        Integer.parseInt(m.get("files_damaged")) + Integer.parseInt(m.get("files_new")) >= 1);
  }

  @Test
  public void testAnalyzeIsReadOnly() {
    spark
        .sql(
            "ALTER TABLE openhouse.db.table SET TBLPROPERTIES ("
                + "'optimize.cluster.keys' = 'id')")
        .show();
    long snapshotsBefore = spark.sql("SELECT * FROM openhouse.db.table.snapshots").count();
    analyze("openhouse.db.table");
    long snapshotsAfter = spark.sql("SELECT * FROM openhouse.db.table.snapshots").count();
    // A read-only probe commits nothing.
    Assertions.assertEquals(snapshotsBefore, snapshotsAfter);
  }

  @Test
  public void testAnalyzeComputeStatisticsStillDelegatesToSpark() {
    // COMPUTE STATISTICS must NOT be intercepted by the OpenHouse grammar; it is handled by Spark.
    // For a v2 Iceberg table Spark rejects it with its own AnalysisException -- crucially NOT an
    // OpenhouseParseException -- which confirms the extension did not claim the statement.
    Exception e =
        Assertions.assertThrows(
            Exception.class,
            () -> spark.sql("ANALYZE TABLE openhouse.db.table COMPUTE STATISTICS").collect());
    Assertions.assertFalse(
        e
            instanceof
            com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException,
        "COMPUTE STATISTICS must delegate to Spark, not the OpenHouse parser");
  }

  @Test
  public void testAnalyzeNonOpenhouseTableThrows() {
    Assertions.assertThrows(
        Exception.class,
        () ->
            spark
                .sql("ANALYZE TABLE openhouse.db.not_openhouse COMPUTE CLUSTERING QUALITY")
                .collect());
  }

  @SneakyThrows
  @BeforeAll
  public void setupSpark() {
    Path unittest = new Path(Files.createTempDirectory("unittest").toString());
    spark =
        SparkSession.builder()
            .master("local[2]")
            .config(
                "spark.sql.extensions",
                ("org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions"))
            .config("spark.sql.catalog.openhouse", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.openhouse.type", "hadoop")
            .config("spark.sql.catalog.openhouse.warehouse", unittest.toString())
            .getOrCreate();
  }

  @BeforeEach
  public void setup() {
    spark
        .sql(
            "CREATE TABLE openhouse.db.table (id bigint, data string, `openhouse.tableId` string) USING iceberg")
        .show();
    spark
        .sql("ALTER TABLE openhouse.db.table SET TBLPROPERTIES ('openhouse.tableId' = 'tableid')")
        .show();
    for (int i = 1; i <= 6; i++) {
      spark
          .sql("INSERT INTO openhouse.db.table VALUES (" + i + ", 'd" + i + "', 'tableid')")
          .show();
    }
    spark
        .sql("CREATE TABLE openhouse.db.not_openhouse (id bigint, data string) USING iceberg")
        .show();
  }

  @AfterEach
  public void tearDown() {
    spark.sql("DROP TABLE IF EXISTS openhouse.db.table").show();
    spark.sql("DROP TABLE IF EXISTS openhouse.db.not_openhouse").show();
  }

  @AfterAll
  public void tearDownSpark() {
    spark.close();
  }
}
