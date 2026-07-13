package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.List;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end integration tests for the Spark SQL {@code VACUUM} command against a real OpenHouse
 * catalog (embedded {@code OpenHouseLocalServer}, authenticated via the {@code
 * spark.sql.catalog.openhouse.auth-token} catalog property configured by the test harness).
 *
 * <p>{@code VACUUM} is thin sugar that resolves the target catalog and issues the equivalent {@code
 * CALL openhouse.system.expire_snapshots(...)} / {@code remove_orphan_files(...)} statements. These
 * are the same procedure CALLs already exercised against OpenHouse by {@code BranchTestSpark3_5} and
 * by the jobs app ({@code Operations.rewriteDataFiles}); this suite verifies that the {@code VACUUM}
 * verb drives them end-to-end through the OpenHouse catalog and its auth path.
 *
 * <p>RUNTIME REQUIREMENT: {@code VACUUM} is a command in a patched Spark parser. This module must be
 * built/run against a Spark 3.5 build that carries the VACUUM grammar (branch
 * {@code claude/iceberg-vacuum-semantics-43tl45-spark3.5}); against stock spark-sql it fails at
 * parse time with a ParseException. The test compiles against stock Spark because the command is
 * passed as a SQL string.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
public class VacuumTestSpark3_5 extends OpenHouseSparkITest {

  private static final String DATABASE = "d1_vacuum_spark";
  private static final String VACUUM_TEST_PREFIX = "vacuum_test_";

  @Test
  public void testVacuumExpiresSnapshotsOnOpenHouseTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = VACUUM_TEST_PREFIX + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      spark.sql("CREATE TABLE " + tableName + " (id int)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2)");
      spark.sql("INSERT INTO " + tableName + " VALUES (3)");

      assertEquals(
          3,
          spark.sql("SELECT snapshot_id FROM " + tableName + ".snapshots").collectAsList().size(),
          "three inserts should produce three snapshots");

      // Snapshot expiration always runs; RETAIN 0 HOURS bounds older_than to the VACUUM instant so
      // every non-current snapshot is expirable.
      spark.sql("VACUUM " + tableName + " RETAIN 0 HOURS");

      assertEquals(
          1,
          spark.sql("SELECT snapshot_id FROM " + tableName + ".snapshots").collectAsList().size(),
          "expiration should retain only the current snapshot");

      List<Row> rows =
          spark.sql("SELECT id FROM " + tableName + " ORDER BY id").collectAsList();
      assertEquals(3, rows.size(), "all data rows should remain readable after VACUUM");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("2", rows.get(1).mkString());
      assertEquals("3", rows.get(2).mkString());

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testVacuumRemoveOrphanFilesRunsAndKeepsTableReadable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = VACUUM_TEST_PREFIX + "ofd_" + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;

      spark.sql("CREATE TABLE " + tableName + " (id int)");
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2)");

      // Exercise the full VACUUM surface end-to-end against OpenHouse: expiration followed by
      // orphan-file deletion. RETAIN 24 HOURS satisfies Iceberg's OFD safety window. This asserts
      // the command drives both CALLs through the OpenHouse catalog + auth path without error and
      // that referenced data survives (correct-files-preserved). Planting an aged orphan file to
      // assert selective deletion requires direct access to the table's storage location, which the
      // OpenHouse-managed layout does not expose to the client; that assertion is covered by the
      // Hadoop-catalog VacuumIcebergSuite in the Spark repo.
      spark.sql("VACUUM " + tableName + " REMOVE ORPHAN FILES RETAIN 24 HOURS");

      List<Row> rows =
          spark.sql("SELECT id FROM " + tableName + " ORDER BY id").collectAsList();
      assertEquals(2, rows.size(), "table must remain fully readable after orphan-file deletion");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("2", rows.get(1).mkString());

      List<Row> files = spark.sql("SELECT file_path FROM " + tableName + ".files").collectAsList();
      assertTrue(files.size() >= 1, "referenced data files must be preserved after VACUUM");

      spark.sql("DROP TABLE " + tableName);
    }
  }
}
