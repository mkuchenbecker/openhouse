package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * End-to-end integration tests for the Spark SQL {@code VACUUM} extension against a real OpenHouse
 * catalog (embedded {@code OpenHouseLocalServer}, authenticated via the {@code
 * spark.sql.catalog.openhouse.auth-token} catalog property configured by the test harness).
 *
 * <p>{@code VACUUM} resolves the target catalog and issues the equivalent {@code CALL
 * openhouse.system.expire_snapshots(...)} / {@code remove_orphan_files(...)} statements. These are
 * the same procedure CALLs already exercised against OpenHouse by {@code BranchTestSpark3_5} and by
 * the jobs app ({@code Operations.rewriteDataFiles}); this suite verifies that the {@code VACUUM}
 * verb drives them end-to-end through the OpenHouse catalog and its auth path.
 *
 * <p>It also pins the parts of the property contract that only a real server can exercise: the
 * {@code maintenance.vacuum.enabled} opt-in has to be settable (an {@code openhouse.}-prefixed one
 * would be rejected as a reserved key), and the default retention has to come from the {@code
 * policies.history} the server persists, which is what the scheduled snapshot-expiration job reads.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
public class VacuumTestSpark3_5 extends OpenHouseSparkITest {

  private static final String DATABASE = "d1_vacuum_spark";
  private static final String VACUUM_TEST_PREFIX = "vacuum_test_";

  private static String createEnabledTable(SparkSession spark, String suffix) {
    String tableName = "openhouse." + DATABASE + "." + VACUUM_TEST_PREFIX + suffix;
    spark.sql("CREATE TABLE " + tableName + " (id int)");
    // The Alpha opt-in. This ALTER is itself part of what is under test: the property has to live
    // outside the reserved `openhouse.` namespace for a user to be able to set it at all.
    spark.sql(
        "ALTER TABLE " + tableName + " SET TBLPROPERTIES ('maintenance.vacuum.enabled' = 'true')");
    return tableName;
  }

  private static Map<String, String> vacuum(SparkSession spark, String statement) {
    Map<String, String> metrics = new HashMap<>();
    for (Row row : spark.sql(statement).collectAsList()) {
      metrics.put(row.getString(0), row.getString(1));
    }
    return metrics;
  }

  private static long snapshotCount(SparkSession spark, String tableName) {
    return spark.sql("SELECT snapshot_id FROM " + tableName + ".snapshots").count();
  }

  @Test
  public void testVacuumExpiresSnapshotsOnOpenHouseTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableName = createEnabledTable(spark, "" + System.currentTimeMillis());

      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2)");
      spark.sql("INSERT INTO " + tableName + " VALUES (3)");

      assertEquals(
          3, snapshotCount(spark, tableName), "three inserts should produce three snapshots");

      // Snapshot expiration always runs; RETAIN 0 HOURS bounds older_than to the VACUUM instant so
      // every non-current snapshot is expirable.
      Map<String, String> metrics = vacuum(spark, "VACUUM " + tableName + " RETAIN 0 HOURS");

      assertEquals("RETAIN", metrics.get("snapshots_retain_source"));
      assertEquals(
          1, snapshotCount(spark, tableName), "expiration should retain only the current snapshot");

      List<Row> rows = spark.sql("SELECT id FROM " + tableName + " ORDER BY id").collectAsList();
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
      String tableName = createEnabledTable(spark, "ofd_" + System.currentTimeMillis());

      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      spark.sql("INSERT INTO " + tableName + " VALUES (2)");

      // Exercise the full VACUUM surface end-to-end against OpenHouse: orphan-file deletion
      // followed by expiration. RETAIN 24 HOURS satisfies Iceberg's OFD safety window. This asserts
      // the command drives both CALLs through the OpenHouse catalog + auth path without error and
      // that referenced data survives (correct-files-preserved). Planting an aged orphan file to
      // assert selective deletion requires direct access to the table's storage location, which the
      // OpenHouse-managed layout does not expose to the client.
      Map<String, String> metrics =
          vacuum(spark, "VACUUM " + tableName + " REMOVE ORPHAN FILES RETAIN 24 HOURS");
      assertEquals("24", metrics.get("orphan_files_retain_hours"));

      List<Row> rows = spark.sql("SELECT id FROM " + tableName + " ORDER BY id").collectAsList();
      assertEquals(2, rows.size(), "table must remain fully readable after orphan-file deletion");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("2", rows.get(1).mkString());

      List<Row> files = spark.sql("SELECT file_path FROM " + tableName + ".files").collectAsList();
      assertTrue(files.size() >= 1, "referenced data files must be preserved after VACUUM");

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testVacuumDefaultRetentionComesFromTheServerPersistedHistoryPolicy()
      throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableName = createEnabledTable(spark, "policy_" + System.currentTimeMillis());
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");

      // The same knob the scheduled snapshot-expiration job reads back off the table.
      spark.sql("ALTER TABLE " + tableName + " SET POLICY (HISTORY MAX_AGE=2D)");

      Map<String, String> metrics = vacuum(spark, "VACUUM " + tableName);

      assertEquals(
          "48",
          metrics.get("snapshots_retain_hours"),
          "the default window must be the table's history policy, not the procedure default");
      assertTrue(
          metrics.get("snapshots_retain_source").startsWith("policies.history"),
          "resolved window should be reported as coming from the history policy, was "
              + metrics.get("snapshots_retain_source"));

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testVacuumDefaultRetentionFallsBackToTheJobDefault() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableName = createEnabledTable(spark, "nopolicy_" + System.currentTimeMillis());
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");

      Map<String, String> metrics = vacuum(spark, "VACUUM " + tableName);

      assertEquals("72", metrics.get("snapshots_retain_hours"));
      assertEquals("default (3 DAY)", metrics.get("snapshots_retain_source"));

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testVacuumOptInPropertyMustBeOutsideTheReservedNamespace() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableName =
          "openhouse." + DATABASE + "." + VACUUM_TEST_PREFIX + "gate_" + System.currentTimeMillis();
      spark.sql("CREATE TABLE " + tableName + " (id int)");

      // Reserved: the /tables service rejects any attempt to set an `openhouse.`-prefixed property,
      // so a gate in that namespace could never be turned on.
      assertThrows(
          Exception.class,
          () ->
              spark.sql(
                  "ALTER TABLE "
                      + tableName
                      + " SET TBLPROPERTIES ('openhouse.vacuum.enabled' = 'true')"));

      // Not yet opted in.
      assertThrows(
          UnsupportedOperationException.class, () -> spark.sql("VACUUM " + tableName).collect());

      spark.sql(
          "ALTER TABLE "
              + tableName
              + " SET TBLPROPERTIES ('maintenance.vacuum.enabled' = 'true')");
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      vacuum(spark, "VACUUM " + tableName);

      spark.sql("DROP TABLE " + tableName);
    }
  }

  @Test
  public void testVacuumRefusesWhenMaintenanceIsDisabled() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableName = createEnabledTable(spark, "disabled_" + System.currentTimeMillis());
      spark.sql("INSERT INTO " + tableName + " VALUES (1)");
      spark.sql(
          "ALTER TABLE " + tableName + " SET TBLPROPERTIES ('maintenance.disabled' = 'true')");

      assertThrows(
          UnsupportedOperationException.class, () -> spark.sql("VACUUM " + tableName).collect());

      spark.sql("DROP TABLE " + tableName);
    }
  }
}
