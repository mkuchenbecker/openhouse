package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.List;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end integration tests for the Spark SQL {@code OPTIMIZE} command against a real OpenHouse
 * catalog (embedded {@code OpenHouseLocalServer}, authenticated via the {@code
 * spark.sql.catalog.openhouse.auth-token} catalog property configured by the test harness).
 *
 * <p>{@code OPTIMIZE} is thin sugar that resolves the target catalog and issues the equivalent
 * {@code CALL openhouse.system.rewrite_data_files(...)} (and, under {@code REWRITE MANIFESTS}, {@code
 * rewrite_manifests(...)}). These are the same procedure CALLs already exercised against OpenHouse
 * by the jobs app ({@code Operations.rewriteDataFiles}); this suite verifies that the {@code
 * OPTIMIZE} verb drives them end-to-end through the OpenHouse catalog and its auth path, that the
 * clustering configuration carried on {@code optimize.cluster.*} table properties survives an
 * OpenHouse round-trip, and that data is preserved. The exhaustive clustering correctness matrix
 * (partition transforms, key types, sort modes, delete modes, incremental watermark) lives in the
 * Hadoop-catalog {@code OptimizeClusteringIcebergSuite} in the Spark repo.
 *
 * <p>RUNTIME REQUIREMENT: {@code OPTIMIZE} is a command in a patched Spark parser. This module must
 * be built/run against a Spark 3.5 build that carries the OPTIMIZE grammar (branch {@code
 * claude/iceberg-optimize-semantics-43tl45-spark3.5}); against stock spark-sql it fails at parse
 * time with a ParseException. The test compiles against stock Spark because the command is passed as
 * a SQL string.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
@Disabled(
    "Requires a Spark 3.5 build that carries the OPTIMIZE grammar (fork branch "
        + "claude/iceberg-optimize-semantics-43tl45-spark3.5). Against stock spark-sql this fails at "
        + "parse time with a ParseException. Remove this annotation once the itest module's "
        + "sparkVersion points at an OPTIMIZE-carrying build. See .agent-notes/vacuum-optimize-handoff.md.")
public class OptimizeTestSpark3_5 extends OpenHouseSparkITest {

  private static final String DATABASE = "d1_optimize_spark";
  private static final String OPTIMIZE_TEST_PREFIX = "optimize_test_";

  // 'optimize.cluster.min-snapshot-age-minutes'='0' disables the conflict hold-back so a freshly
  // inserted table is fully eligible for rewrite within the test (no real-time settle wait).
  private static String clustered(String keys) {
    return "'optimize.cluster.keys'='"
        + keys
        + "', 'optimize.cluster.sort-mode'='zorder', "
        + "'optimize.cluster.min-snapshot-age-minutes'='0'";
  }

  private static long snapshotCount(SparkSession spark, String tableName) {
    return spark.sql("SELECT snapshot_id FROM " + tableName + ".snapshots").collectAsList().size();
  }

  @Test
  public void testOptimizeFullClustersAndPreservesDataOnOpenHouseTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = OPTIMIZE_TEST_PREFIX + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      spark.sql(
          "CREATE TABLE " + tableName + " (ts int, val int) TBLPROPERTIES (" + clustered("ts") + ")");

      // Assert the clustering configuration survived the OpenHouse create round-trip -- if OpenHouse
      // strips unknown table properties, OPTIMIZE would silently degrade to plain compaction, so pin
      // it explicitly here.
      List<Row> keysProp =
          spark
              .sql("SHOW TBLPROPERTIES " + tableName + " ('optimize.cluster.keys')")
              .collectAsList();
      assertEquals(1, keysProp.size(), "optimize.cluster.keys must persist on the OpenHouse table");
      assertEquals("ts", keysProp.get(0).getString(1));

      // Several files, each spanning the whole key range -> interleaved -> a real rewrite to do.
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 1), (6, 6)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 2), (6, 7)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 3), (6, 8)");

      long before = snapshotCount(spark, tableName);
      spark.sql("OPTIMIZE " + tableName + " FULL");
      long after = snapshotCount(spark, tableName);

      assertTrue(
          after > before,
          "OPTIMIZE FULL should commit at least one rewrite snapshot (before=" + before + ", after="
              + after + ")");

      List<Row> rows =
          spark.sql("SELECT val FROM " + tableName + " ORDER BY val").collectAsList();
      assertEquals(6, rows.size(), "all data rows must remain readable after OPTIMIZE");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("8", rows.get(5).mkString());

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testOptimizeRewriteManifestsRunsAndKeepsTableReadable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = OPTIMIZE_TEST_PREFIX + "manifests_" + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      spark.sql(
          "CREATE TABLE " + tableName + " (ts int, val int) TBLPROPERTIES (" + clustered("ts") + ")");
      spark.sql("INSERT INTO " + tableName + " VALUES (1, 1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2, 2)");
      spark.sql("INSERT INTO " + tableName + " VALUES (3, 3)");

      // Exercise the full OPTIMIZE surface end-to-end against OpenHouse: rewrite_data_files followed
      // by rewrite_manifests (two commits). Asserts the command drives both CALLs through the
      // OpenHouse catalog + auth path without error and that data survives.
      spark.sql("OPTIMIZE " + tableName + " REWRITE MANIFESTS");

      List<Row> rows =
          spark.sql("SELECT ts FROM " + tableName + " ORDER BY ts").collectAsList();
      assertEquals(3, rows.size(), "table must remain fully readable after REWRITE MANIFESTS");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("3", rows.get(2).mkString());

      List<Row> manifests =
          spark.sql("SELECT path FROM " + tableName + ".manifests").collectAsList();
      assertTrue(manifests.size() >= 1, "table must have at least one manifest after OPTIMIZE");

      spark.sql("DROP TABLE " + tableName);
    }
  }
}
