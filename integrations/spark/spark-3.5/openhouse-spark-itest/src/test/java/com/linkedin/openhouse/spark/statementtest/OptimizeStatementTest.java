package com.linkedin.openhouse.spark.statementtest;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * OPTIMIZE against a Hadoop catalog: the two tracking signals (per-file layout stamp and sequence
 * watermark), their interaction with foreign rewrites and layout changes, migration from the
 * snapshot-id watermark, and the surface of the command. Needs the OpenHouse Iceberg fork's rewrite
 * options at runtime (1.5.2.22 or later).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OptimizeStatementTest {
  private static SparkSession spark = null;

  private static final String TABLE = "openhouse.db.table";

  private Map<String, String> optimize(String sql) {
    Map<String, String> metrics = new HashMap<>();
    for (Row r : spark.sql(sql).collectAsList()) {
      metrics.put(r.getString(0), r.getString(1));
    }
    return metrics;
  }

  private long rowCount(String table) {
    return spark.sql("SELECT * FROM " + table).count();
  }

  /** A table property's value, or null when the table does not have it. */
  private String tableProperty(String table, String key) {
    List<Row> rows = spark.sql("SHOW TBLPROPERTIES " + table + " ('" + key + "')").collectAsList();
    if (rows.isEmpty()) {
      return null;
    }
    String value = rows.get(0).getString(1);
    // Spark answers an absent key with a message row rather than no row.
    return value.startsWith("Table ") && value.contains("does not have property") ? null : value;
  }

  private void setProperties(String table, String properties) {
    spark.sql("ALTER TABLE " + table + " SET TBLPROPERTIES (" + properties + ")").show();
  }

  private void cluster(String keys, String mode) {
    setProperties(
        TABLE,
        "'optimize.cluster.keys' = '" + keys + "', 'optimize.cluster.sort-mode' = '" + mode + "'");
  }

  /** (data sequence number, sort order id) of every live data file. */
  private List<Row> liveFiles(String table) {
    return spark
        .sql(
            "SELECT sequence_number, data_file.sort_order_id FROM "
                + table
                + ".entries WHERE status < 2 AND data_file.content = 0")
        .collectAsList();
  }

  private List<Integer> stamps(String table) {
    return liveFiles(table).stream()
        .map(r -> r.isNullAt(1) ? null : r.getInt(1))
        .collect(Collectors.toList());
  }

  private long maxSequence(String table) {
    return liveFiles(table).stream().mapToLong(r -> r.getLong(0)).max().orElse(0L);
  }

  /** Plain writes carry no sort order id or the unsorted order's id 0; neither is a layout. */
  private static boolean unstamped(Integer sortOrderId) {
    return sortOrderId == null || sortOrderId == 0;
  }

  private static int layoutId(Map<String, String> metrics) {
    return Integer.parseInt(metrics.get("layout_id"));
  }

  @Test
  public void testOptimizeBinPackReturnsMetricsAndPreservesRows() {
    Map<String, String> m = optimize("OPTIMIZE " + TABLE);
    Assertions.assertTrue(m.containsKey("files_before"));
    Assertions.assertTrue(m.containsKey("files_after"));
    Assertions.assertTrue(m.containsKey("files_removed"));
    Assertions.assertTrue(m.containsKey("snapshots_committed"));
    Assertions.assertTrue(m.containsKey("files_rewritten"));
    Assertions.assertEquals(6, rowCount(TABLE));
    Assertions.assertTrue(
        Long.parseLong(m.get("files_after")) < Long.parseLong(m.get("files_before")));
    // Bin-pack output carries no layout stamp.
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(OptimizeStatementTest::unstamped));
    Assertions.assertNull(tableProperty(TABLE, "optimize.cluster.layout-id"));
  }

  @Test
  public void testOptimizeClusteringStampsFilesAndWritesState() {
    cluster("id", "sort");
    long sequenceBefore = maxSequence(TABLE);

    Map<String, String> m = optimize("OPTIMIZE " + TABLE);
    Assertions.assertEquals(6, rowCount(TABLE));
    Assertions.assertEquals("6", m.get("files_rewritten"));
    Assertions.assertEquals("1", m.get("snapshots_committed"));

    // Every live file carries the layout stamp, and its data sequence number is that of the newest
    // input it replaced, not a new one: the run's own output never reads as new data.
    int layout = layoutId(m);
    Assertions.assertTrue(layout >= 1000, "layout id must clear the registered sort order ids");
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(s -> !unstamped(s) && s == layout));
    Assertions.assertEquals(sequenceBefore, maxSequence(TABLE));

    // The state written back: layout id, its definition, the watermark and the epoch history.
    Assertions.assertEquals(
        Integer.toString(layout), tableProperty(TABLE, "optimize.cluster.layout-id"));
    Assertions.assertTrue(
        tableProperty(TABLE, "optimize.cluster.layout." + layout).contains("\"keys\""));
    Assertions.assertEquals(
        Long.toString(sequenceBefore), tableProperty(TABLE, "optimize.cluster.hwm-seq"));
    Assertions.assertEquals(
        m.get("hwm_seq_after"), tableProperty(TABLE, "optimize.cluster.hwm-seq"));
    Assertions.assertTrue(tableProperty(TABLE, "optimize.cluster.epochs").startsWith("[{"));
  }

  @Test
  public void testOptimizeIncrementalTakesOnlyNewData() {
    cluster("id", "sort");
    optimize("OPTIMIZE " + TABLE);

    // Nothing new: nothing rewritten, no commit, watermark unchanged.
    Map<String, String> second = optimize("OPTIMIZE " + TABLE);
    Assertions.assertEquals("0", second.get("files_rewritten"));
    Assertions.assertEquals("0", second.get("snapshots_committed"));
    Assertions.assertEquals(second.get("hwm_seq_before"), second.get("hwm_seq_after"));

    // New data lands above the watermark, unstamped; the next run takes exactly that file and
    // moves the watermark to its sequence.
    spark.sql("INSERT INTO " + TABLE + " VALUES (7, 'd7', 'tableid')").show();
    spark.sql("INSERT INTO " + TABLE + " VALUES (8, 'd8', 'tableid')").show();
    Assertions.assertEquals(
        2, stamps(TABLE).stream().filter(OptimizeStatementTest::unstamped).count());
    long newest = maxSequence(TABLE);

    Map<String, String> third = optimize("OPTIMIZE " + TABLE);
    Assertions.assertEquals("2", third.get("files_rewritten"));
    Assertions.assertEquals(Long.toString(newest), third.get("hwm_seq_after"));
    Assertions.assertTrue(stamps(TABLE).stream().noneMatch(OptimizeStatementTest::unstamped));
    Assertions.assertEquals(8, rowCount(TABLE));
  }

  @Test
  public void testOptimizeReclustersFilesAForeignRewriteDamaged() {
    cluster("id", "sort");
    Map<String, String> first = optimize("OPTIMIZE " + TABLE);
    int layout = layoutId(first);

    // A scheduled bin-pack (or any other engine) rewrites the clustered files: the outputs carry
    // no stamp and a new sequence number, so they are no longer clustered.
    spark.sql("INSERT INTO " + TABLE + " VALUES (7, 'd7', 'tableid')").show();
    spark
        .sql(
            "CALL openhouse.system.rewrite_data_files(table => 'db.table', "
                + "options => map('min-input-files', '1', 'rewrite-all', 'true'))")
        .collect();
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(OptimizeStatementTest::unstamped));

    // The next incremental run sees them above the watermark and reclusters them.
    Map<String, String> repair = optimize("OPTIMIZE " + TABLE);
    Assertions.assertTrue(Integer.parseInt(repair.get("files_rewritten")) >= 1);
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(s -> !unstamped(s) && s == layout));
    Assertions.assertEquals(7, rowCount(TABLE));
  }

  @Test
  public void testOptimizeLayoutChangeLeavesOldFilesToFull() {
    cluster("id", "sort");
    int oldLayout = layoutId(optimize("OPTIMIZE " + TABLE));

    // Switch the key selection. Files stamped with the old layout sit below the watermark, so an
    // incremental run skips them and only takes new data.
    cluster("data", "sort");
    spark.sql("INSERT INTO " + TABLE + " VALUES (7, 'd7', 'tableid')").show();
    Map<String, String> incremental = optimize("OPTIMIZE " + TABLE);
    int newLayout = layoutId(incremental);
    Assertions.assertNotEquals(oldLayout, newLayout);
    Assertions.assertEquals("1", incremental.get("files_rewritten"));
    Assertions.assertTrue(stamps(TABLE).contains(oldLayout));
    Assertions.assertTrue(stamps(TABLE).contains(newLayout));

    // FULL rewrites everything that does not carry the current layout.
    Map<String, String> full = optimize("OPTIMIZE " + TABLE + " FULL");
    Assertions.assertTrue(Integer.parseInt(full.get("files_rewritten")) >= 1);
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(s -> !unstamped(s) && s == newLayout));
    Assertions.assertEquals(7, rowCount(TABLE));

    // Both layouts remain described, and the history records both epochs.
    Assertions.assertNotNull(tableProperty(TABLE, "optimize.cluster.layout." + oldLayout));
    Assertions.assertNotNull(tableProperty(TABLE, "optimize.cluster.layout." + newLayout));
    String epochs = tableProperty(TABLE, "optimize.cluster.epochs");
    Assertions.assertTrue(epochs.contains("\"layout\":" + oldLayout));
    Assertions.assertTrue(epochs.contains("\"layout\":" + newLayout));

    // FULL again is a no-op: every file already carries the current layout.
    Assertions.assertEquals("0", optimize("OPTIMIZE " + TABLE + " FULL").get("files_rewritten"));
  }

  @Test
  public void testOptimizeMigratesSnapshotIdWatermark() {
    cluster("id", "sort");
    long liveSnapshot =
        spark
            .sql("SELECT snapshot_id FROM " + TABLE + ".snapshots ORDER BY committed_at")
            .collectAsList()
            .get(2)
            .getLong(0);
    setProperties(
        TABLE,
        "'optimize.cluster.hwm-snapshot-id' = '"
            + liveSnapshot
            + "', 'optimize.cluster.state' = '[]', 'optimize.cluster.config-id' = 'abc', "
            + "'optimize.cluster.min-snapshot-age-minutes' = '0'");

    // The live watermark snapshot maps to its sequence number (the third insert), so the run takes
    // only the three files above it, and the legacy properties are gone afterwards.
    Map<String, String> m = optimize("OPTIMIZE " + TABLE);
    Assertions.assertEquals("3", m.get("hwm_seq_before"));
    Assertions.assertEquals("3", m.get("files_rewritten"));
    Assertions.assertNull(tableProperty(TABLE, "optimize.cluster.hwm-snapshot-id"));
    Assertions.assertNull(tableProperty(TABLE, "optimize.cluster.state"));
    Assertions.assertNull(tableProperty(TABLE, "optimize.cluster.config-id"));
    Assertions.assertNull(tableProperty(TABLE, "optimize.cluster.min-snapshot-age-minutes"));
    Assertions.assertEquals("6", tableProperty(TABLE, "optimize.cluster.hwm-seq"));

    // An expired (unknown) watermark snapshot starts from 0: everything is examined and the
    // stamps decide, so the three older unstamped files are clustered now.
    spark.sql("ALTER TABLE " + TABLE + " UNSET TBLPROPERTIES ('optimize.cluster.hwm-seq')").show();
    setProperties(TABLE, "'optimize.cluster.hwm-snapshot-id' = '424242'");
    Map<String, String> again = optimize("OPTIMIZE " + TABLE);
    Assertions.assertEquals("0", again.get("hwm_seq_before"));
    Assertions.assertEquals("3", again.get("files_rewritten"));
    Assertions.assertTrue(stamps(TABLE).stream().noneMatch(OptimizeStatementTest::unstamped));
    Assertions.assertEquals(6, rowCount(TABLE));
  }

  @Test
  public void testOptimizeClusteringRefusesFormatVersionOne() {
    spark
        .sql(
            "CREATE TABLE openhouse.db.v1 (id bigint, data string, `openhouse.tableId` string) "
                + "USING iceberg TBLPROPERTIES ('openhouse.tableId' = 'tableid', "
                + "'format-version' = '1', 'optimize.cluster.keys' = 'id')")
        .show();
    spark.sql("INSERT INTO openhouse.db.v1 VALUES (1, 'a', 'tableid')").show();
    try {
      Exception e =
          Assertions.assertThrows(
              UnsupportedOperationException.class,
              () -> spark.sql("OPTIMIZE openhouse.db.v1").collect());
      Assertions.assertTrue(e.getMessage().contains("format-version"));
    } finally {
      spark.sql("DROP TABLE IF EXISTS openhouse.db.v1").show();
    }
  }

  @Test
  public void testOptimizeRejectsUnknownSortMode() {
    cluster("id", "hilbert");
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> spark.sql("OPTIMIZE " + TABLE).collect());
  }

  @Test
  public void testOptimizeZOrderStampsFiles() {
    cluster("id,data", "zorder");
    Map<String, String> m = optimize("OPTIMIZE " + TABLE);
    int layout = layoutId(m);
    Assertions.assertEquals("6", m.get("files_rewritten"));
    Assertions.assertTrue(stamps(TABLE).stream().allMatch(s -> !unstamped(s) && s == layout));
    Assertions.assertEquals(6, rowCount(TABLE));
    Assertions.assertEquals("0", optimize("OPTIMIZE " + TABLE).get("files_rewritten"));
  }

  @Test
  @Disabled(
      "The itest classpath carries both the Parquet shaded into iceberg-spark-runtime and the "
          + "unshaded Parquet of the test fixtures; reading a position delete fails in "
          + "org.apache.iceberg.parquet.ReadConf with a ClassCastException before OPTIMIZE runs.")
  public void testOptimizeCompactsMergeOnReadDeletesAndKeepsRowsCorrect() {
    // A merge-on-read table: the DELETE writes position delete files rather than rewriting data.
    spark
        .sql(
            "CREATE TABLE openhouse.db.mor (id bigint, data string, `openhouse.tableId` string) "
                + "USING iceberg TBLPROPERTIES ("
                + "'openhouse.tableId' = 'tableid', 'format-version' = '2', "
                + "'write.delete.mode' = 'merge-on-read')")
        .show();
    try {
      // Two rows per file (one task per insert), so deleting one row cannot be satisfied by
      // dropping a whole file and must write a position delete.
      for (int i = 1; i <= 6; i += 2) {
        spark
            .sql(
                "INSERT INTO openhouse.db.mor SELECT /*+ COALESCE(1) */ * FROM VALUES ("
                    + i
                    + "L, 'd"
                    + i
                    + "', 'tableid'), ("
                    + (i + 1)
                    + "L, 'd"
                    + (i + 1)
                    + "', 'tableid') AS t(id, data, tid)")
            .show();
      }
      Assertions.assertEquals(3, spark.sql("SELECT * FROM openhouse.db.mor.files").count());
      spark.sql("DELETE FROM openhouse.db.mor WHERE id = 3").show();
      Assertions.assertEquals(5, rowCount("openhouse.db.mor"));
      long deleteFilesBefore = spark.sql("SELECT * FROM openhouse.db.mor.delete_files").count();
      Assertions.assertTrue(deleteFilesBefore >= 1);

      // OPTIMIZE rewrites data and then compacts position deletes, dropping the ones the rewrite
      // made dangling. The visible rows must be exactly the same across the rewrite.
      Map<String, String> m = optimize("OPTIMIZE openhouse.db.mor");
      Assertions.assertTrue(m.containsKey("files_after"));
      Assertions.assertEquals(5, rowCount("openhouse.db.mor"));
      Assertions.assertEquals(0, spark.sql("SELECT * FROM openhouse.db.mor WHERE id = 3").count());
      Assertions.assertEquals(0, spark.sql("SELECT * FROM openhouse.db.mor.delete_files").count());
    } finally {
      spark.sql("DROP TABLE IF EXISTS openhouse.db.mor").show();
    }
  }

  @Test
  public void testOptimizeRewriteManifestsPreservesRows() {
    Map<String, String> m = optimize("OPTIMIZE " + TABLE + " REWRITE MANIFESTS");
    Assertions.assertTrue(m.containsKey("files_after"));
    Assertions.assertEquals(6, rowCount(TABLE));
  }

  @Test
  public void testOptimizeFullRewriteManifestsParsesAndRuns() {
    Map<String, String> m = optimize("OPTIMIZE " + TABLE + " FULL REWRITE MANIFESTS");
    Assertions.assertEquals(6, rowCount(TABLE));
    Assertions.assertTrue(m.containsKey("files_removed"));
  }

  @Test
  public void testOptimizeMaintenanceDisabledThrows() {
    setProperties(TABLE, "'maintenance.DATA_COMPACTION.disabled' = 'true'");
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> spark.sql("OPTIMIZE " + TABLE).collect());
  }

  @Test
  public void testOptimizeNonOpenhouseTableThrows() {
    Assertions.assertThrows(
        Exception.class, () -> spark.sql("OPTIMIZE openhouse.db.not_openhouse").collect());
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
            "CREATE TABLE openhouse.db.table (id bigint, data string, `openhouse.tableId` string) "
                + "USING iceberg TBLPROPERTIES ('format-version' = '2')")
        .show();
    spark
        .sql("ALTER TABLE openhouse.db.table SET TBLPROPERTIES ('openhouse.tableId' = 'tableid')")
        .show();
    // Six single-row files in six snapshots: data sequence numbers 1 through 6.
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
