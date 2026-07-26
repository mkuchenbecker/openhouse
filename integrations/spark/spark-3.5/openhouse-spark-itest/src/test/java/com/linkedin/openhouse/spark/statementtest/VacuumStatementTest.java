package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException;
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
public class VacuumStatementTest {

  private static SparkSession spark = null;

  private long snapshotCount(String table) {
    return spark.sql("SELECT * FROM " + table + ".snapshots").count();
  }

  private long rowCount(String table) {
    return spark.sql("SELECT * FROM " + table).count();
  }

  /** The (metric, value) rows VACUUM reports, as a map. */
  private Map<String, String> vacuum(String statement) {
    Map<String, String> metrics = new HashMap<>();
    for (Row row : spark.sql(statement).collectAsList()) {
      metrics.put(row.getString(0), row.getString(1));
    }
    return metrics;
  }

  private void setProperties(String table, String properties) {
    spark.sql("ALTER TABLE " + table + " SET TBLPROPERTIES (" + properties + ")").show();
  }

  @Test
  public void testVacuumExpiresSnapshots() {
    // Three inserts create three snapshots.
    Assertions.assertEquals(3, snapshotCount("openhouse.db.table"));

    // RETAIN 0 HOURS expires everything but the current snapshot; the table stays readable.
    Map<String, String> metrics = vacuum("VACUUM openhouse.db.table RETAIN 0 HOURS");

    Assertions.assertEquals(1, snapshotCount("openhouse.db.table"));
    Assertions.assertEquals(3, rowCount("openhouse.db.table"));
    Assertions.assertEquals("0", metrics.get("snapshots_retain_hours"));
    Assertions.assertEquals("RETAIN", metrics.get("snapshots_retain_source"));
  }

  @Test
  public void testVacuumWithDefaultRetentionSucceeds() {
    // No RETAIN and no history policy: the snapshot-expiration job's own 3-day default applies.
    Map<String, String> metrics = vacuum("VACUUM openhouse.db.table");
    Assertions.assertEquals(3, rowCount("openhouse.db.table"));
    Assertions.assertEquals("72", metrics.get("snapshots_retain_hours"));
    Assertions.assertEquals("default (3 DAY)", metrics.get("snapshots_retain_source"));
  }

  @Test
  public void testVacuumDefaultRetentionComesFromTheHistoryPolicy() {
    // The window the scheduled snapshot-expiration job would have used for this table.
    setProperties(
        "openhouse.db.table",
        "'policies' = '{\"history\":{\"maxAge\":6,\"granularity\":\"HOUR\"}}'");

    Map<String, String> metrics = vacuum("VACUUM openhouse.db.table");

    Assertions.assertEquals("6", metrics.get("snapshots_retain_hours"));
    Assertions.assertEquals("policies.history (6 HOUR)", metrics.get("snapshots_retain_source"));
    // Nothing is older than six hours, so every snapshot survives.
    Assertions.assertEquals(3, snapshotCount("openhouse.db.table"));
  }

  @Test
  public void testVacuumAppliesTheHistoryPolicyVersionsCap() {
    // `versions` caps how many snapshots survive regardless of age, as the job applies it.
    setProperties(
        "openhouse.db.table",
        "'policies' = '{\"history\":{\"maxAge\":6,\"granularity\":\"HOUR\",\"versions\":2}}'");

    Map<String, String> metrics = vacuum("VACUUM openhouse.db.table");

    Assertions.assertEquals("2", metrics.get("snapshots_retain_last"));
    Assertions.assertEquals(2, snapshotCount("openhouse.db.table"));
    Assertions.assertEquals(3, rowCount("openhouse.db.table"));
  }

  @Test
  public void testVacuumRemoveOrphanFilesPreservesLiveData() {
    // A 24-hour window is safely above Iceberg's orphan-file removal floor and must not delete any
    // file the table references, so all rows survive.
    Map<String, String> metrics =
        vacuum("VACUUM openhouse.db.table REMOVE ORPHAN FILES RETAIN 24 HOURS");
    Assertions.assertEquals(3, rowCount("openhouse.db.table"));
    Assertions.assertEquals("24", metrics.get("orphan_files_retain_hours"));
    Assertions.assertEquals("RETAIN", metrics.get("orphan_files_retain_source"));
  }

  @Test
  public void testVacuumRemoveOrphanFilesDefaultWindowMatchesTheJob() {
    Map<String, String> metrics = vacuum("VACUUM openhouse.db.table REMOVE ORPHAN FILES");
    Assertions.assertEquals("168", metrics.get("orphan_files_retain_hours"));
    Assertions.assertEquals("default", metrics.get("orphan_files_retain_source"));

    setProperties("openhouse.db.table", "'ofd.one_day_ttl.enabled' = 'true'");
    metrics = vacuum("VACUUM openhouse.db.table REMOVE ORPHAN FILES");
    Assertions.assertEquals("24", metrics.get("orphan_files_retain_hours"));
    Assertions.assertEquals("ofd.one_day_ttl.enabled", metrics.get("orphan_files_retain_source"));
  }

  @Test
  public void testVacuumRemoveOrphanFilesOnBackupEnabledTableThrows() {
    // The scheduled job moves orphans into the backup directory instead of deleting them; the
    // stored procedure cannot, so it must not run here.
    setProperties("openhouse.db.table", "'retention.backup.enabled' = 'true'");

    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> spark.sql("VACUUM openhouse.db.table REMOVE ORPHAN FILES").collect());

    // Expiration alone is unaffected.
    vacuum("VACUUM openhouse.db.table RETAIN 0 HOURS");
    Assertions.assertEquals(1, snapshotCount("openhouse.db.table"));
  }

  @Test
  public void testVacuumOnReplicaTableThrows() {
    // The scheduled snapshot-expiration job runs on primary tables only.
    setProperties("openhouse.db.table", "'openhouse.tableType' = 'REPLICA_TABLE'");

    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> spark.sql("VACUUM openhouse.db.table").collect());
  }

  @Test
  public void testVacuumWhenMaintenanceIsDisabledThrows() {
    setProperties("openhouse.db.table", "'maintenance.disabled' = 'true'");
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> spark.sql("VACUUM openhouse.db.table").collect());
  }

  @Test
  public void testVacuumWhenOnlyOrphanFileDeletionIsDisabledThrowsOnlyForThatStep() {
    setProperties("openhouse.db.table", "'maintenance.ORPHAN_FILES_DELETION.disabled' = 'true'");

    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> spark.sql("VACUUM openhouse.db.table REMOVE ORPHAN FILES").collect());

    // Snapshot expiration is a different job type and still runs.
    Assertions.assertEquals(3, rowCount("openhouse.db.table"));
    vacuum("VACUUM openhouse.db.table RETAIN 0 HOURS");
    Assertions.assertEquals(1, snapshotCount("openhouse.db.table"));
  }

  @Test
  public void testVacuumLowerCase() {
    spark.sql("vacuum openhouse.db.table retain 0 hours").collect();
    Assertions.assertEquals(1, snapshotCount("openhouse.db.table"));
  }

  @Test
  public void testVacuumNonOpenhouseTableThrows() {
    Assertions.assertThrows(
        Exception.class, () -> spark.sql("VACUUM openhouse.db.not_openhouse").collect());
  }

  @Test
  public void testVacuumNotEnabledThrows() {
    // VACUUM is Alpha and opt-in: an OpenHouse table that has not set
    // maintenance.vacuum.enabled=true is rejected.
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> spark.sql("VACUUM openhouse.db.not_enabled").collect());
  }

  @Test
  public void testVacuumInvalidSyntaxThrows() {
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () -> spark.sql("VACUUM openhouse.db.table RETAIN 5 DAYS").collect());
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
        .sql(
            "ALTER TABLE openhouse.db.table SET TBLPROPERTIES ("
                + "'openhouse.tableId' = 'tableid', 'maintenance.vacuum.enabled' = 'true')")
        .show();
    spark.sql("INSERT INTO openhouse.db.table VALUES (1, 'a', 'tableid')").show();
    spark.sql("INSERT INTO openhouse.db.table VALUES (2, 'b', 'tableid')").show();
    spark.sql("INSERT INTO openhouse.db.table VALUES (3, 'c', 'tableid')").show();

    // OpenHouse table that has NOT opted into the Alpha VACUUM feature.
    spark
        .sql(
            "CREATE TABLE openhouse.db.not_enabled (id bigint, data string, `openhouse.tableId` string) USING iceberg")
        .show();
    spark
        .sql(
            "ALTER TABLE openhouse.db.not_enabled SET TBLPROPERTIES ('openhouse.tableId' = 'tableid')")
        .show();

    spark
        .sql("CREATE TABLE openhouse.db.not_openhouse (id bigint, data string) USING iceberg")
        .show();
  }

  @AfterEach
  public void tearDown() {
    spark.sql("DROP TABLE IF EXISTS openhouse.db.table").show();
    spark.sql("DROP TABLE IF EXISTS openhouse.db.not_enabled").show();
    spark.sql("DROP TABLE IF EXISTS openhouse.db.not_openhouse").show();
  }

  @AfterAll
  public void tearDownSpark() {
    spark.close();
  }
}
