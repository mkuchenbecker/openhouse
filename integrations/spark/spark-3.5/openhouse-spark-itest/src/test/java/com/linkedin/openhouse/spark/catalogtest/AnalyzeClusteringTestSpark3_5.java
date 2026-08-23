package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.List;
import java.util.Optional;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end integration tests for the Spark SQL {@code ANALYZE TABLE ... COMPUTE CLUSTERING
 * QUALITY} command against a real OpenHouse catalog (embedded {@code OpenHouseLocalServer},
 * authenticated via the {@code spark.sql.catalog.openhouse.auth-token} catalog property configured
 * by the test harness).
 *
 * <p>Unlike {@code VACUUM} / {@code OPTIMIZE} (thin {@code CALL} passthroughs), this command reads
 * the table's {@code .files} / {@code .partitions} metadata tables and runs distributed aggregation
 * SQL over them, then returns scalar quality metrics -- it never collects per-file rows to the
 * driver. This suite is therefore the one that specifically exercises OpenHouse's metadata-table
 * surface and catalog resolution through the read path (not just the procedure/auth path). The
 * exhaustive metric matrix (coverage math, depth math, per-dimension breakdowns) lives in the
 * Hadoop-catalog {@code OptimizeClusteringIcebergSuite} in the Spark repo; here we verify the
 * command runs end-to-end through OpenHouse and returns coherent metrics.
 *
 * <p>Output schema is three string columns: {@code (metric, dimension, value)}. Table-level metrics
 * carry a null {@code dimension}; per-key metrics (e.g. {@code depth_avg}) carry the key name.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
public class AnalyzeClusteringTestSpark3_5 extends OpenHouseSparkITest {

  private static final String DATABASE = "d1_analyze_spark";
  private static final String ANALYZE_TEST_PREFIX = "analyze_test_";

  private static String clustered(String keys) {
    return "'optimize.cluster.keys'='"
        + keys
        + "', 'optimize.cluster.sort-mode'='zorder', "
        + "'optimize.cluster.min-snapshot-age-minutes'='0'";
  }

  /** Value of a table-level metric (null dimension), or empty if absent. */
  private static Optional<String> metric(List<Row> rows, String name) {
    return rows.stream()
        .filter(r -> name.equals(r.getString(0)) && r.isNullAt(1))
        .map(r -> r.getString(2))
        .findFirst();
  }

  /** Value of a per-dimension metric, or empty if absent. */
  private static Optional<String> dim(List<Row> rows, String name, String dimension) {
    return rows.stream()
        .filter(r -> name.equals(r.getString(0)) && dimension.equals(r.getString(1)))
        .map(r -> r.getString(2))
        .findFirst();
  }

  @Test
  public void testAnalyzeClusteringQualityRunsOnOpenHouseTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = ANALYZE_TEST_PREFIX + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      spark.sql(
          "CREATE TABLE "
              + tableName
              + " (ts int, val int) TBLPROPERTIES ("
              + clustered("ts")
              + ")");
      // Interleaved files (each spans the key range) -> measurable overlap depth > 1.
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 1), (6, 6)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 2), (6, 7)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 3), (6, 8)");

      long snapsBefore =
          spark.sql("SELECT * FROM " + tableName + ".snapshots").collectAsList().size();

      List<Row> rows =
          spark.sql("ANALYZE TABLE " + tableName + " COMPUTE CLUSTERING QUALITY").collectAsList();
      assertTrue(rows.size() > 0, "ANALYZE must return metric rows");

      // The command read the OpenHouse table's metadata + configuration and reported it coherently.
      assertEquals(
          "true",
          metric(rows, "clustering_configured").orElse(null),
          "table is configured for clustering");
      assertEquals("ts", metric(rows, "keys").orElse(null), "configured clustering key");

      // Coverage is a table-level percentage produced by a distributed aggregate over .files.
      String coverage = metric(rows, "coverage_bytes_pct").orElse(null);
      assertNotNull(coverage, "coverage_bytes_pct must be reported");
      double coveragePct = Double.parseDouble(coverage);
      assertTrue(
          coveragePct >= 0.0 && coveragePct <= 100.0,
          "coverage_bytes_pct must be a valid percentage, got " + coveragePct);

      // Depth is the windowed stabbing-sweep over the key's intervals; interleaved data -> depth >
      // 1.
      String depthAvg = dim(rows, "depth_avg", "ts").orElse(null);
      assertNotNull(depthAvg, "depth_avg for key 'ts' must be reported");
      assertTrue(
          Double.parseDouble(depthAvg) >= 2.0,
          "interleaved data should have overlap depth well above 1, got " + depthAvg);

      // ANALYZE is read-only: it must not commit a snapshot to the OpenHouse table.
      long snapsAfter =
          spark.sql("SELECT * FROM " + tableName + ".snapshots").collectAsList().size();
      assertEquals(snapsBefore, snapsAfter, "ANALYZE must not commit a snapshot");

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testAnalyzeClusteringQualityOnUnclusteredTableIsGraceful() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = ANALYZE_TEST_PREFIX + "unclustered_" + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      // No optimize.cluster.* properties -> the table is not configured for clustering.
      spark.sql("CREATE TABLE " + tableName + " (ts int, val int)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2, 2)");

      // Must not error on a non-clustered OpenHouse table; it should report clustering as
      // unconfigured.
      List<Row> rows =
          spark.sql("ANALYZE TABLE " + tableName + " COMPUTE CLUSTERING QUALITY").collectAsList();
      assertTrue(rows.size() > 0, "ANALYZE must return a result even for unclustered tables");
      assertEquals(
          "false",
          metric(rows, "clustering_configured").orElse(null),
          "table is not configured for clustering");

      // Table remains fully readable.
      List<Row> data = spark.sql("SELECT ts FROM " + tableName + " ORDER BY ts").collectAsList();
      assertEquals(2, data.size());

      spark.sql("DROP TABLE " + tableName);
    }
  }
}
