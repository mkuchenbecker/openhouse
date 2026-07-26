package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.*;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.exceptions.DuplicateWAPCommitException;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first port of the 3.1 lane's {@code WapIdTest}. WAP staging,
 * cherry-pick and expire_snapshots are all stock Iceberg (SQL + the {@code openhouse.system.*}
 * procedures registered by the stock Iceberg Spark extension) and port verbatim. In {@code
 * testWapWorkflowWithVariousOperations} the custom OpenHouse {@code SET POLICY (SHARING=TRUE)} is
 * RESTORED (the OpenHouse Spark SQL extension is now ported + registered on this lane and the
 * policy is asserted persisted); only the {@code GRANT SELECT ... TO ...} statement remains dropped
 * — GRANT has no server ACL endpoint on the REST lane (see 10-RESIDUALS.md /
 * policy-sql-extension-spark4.md).
 */
public class WapIdTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "d1_wap";
  private static final String TABLE_IDENTIFIER = DATABASE + ".t1";
  private static final String FULL_TABLE_NAME = "openhouse." + TABLE_IDENTIFIER;

  @Test
  public void testWriteWapToEmptyTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");

      // snapshot added but no data inserted
      assertEquals(0, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());
      assertEquals(
          0, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".refs").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testWriteWapToNonEmptyTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");

      // snapshot added but no data inserted
      assertEquals(1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());
      assertEquals(
          1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".refs").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testCherryPickBaseUnchanged() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");
      String snapshotIdWap1 =
          spark
              .sql(
                  "SELECT snapshot_id FROM "
                      + FULL_TABLE_NAME
                      + ".snapshots WHERE summary['wap.id'] = 'wap1'")
              .first()
              .mkString();
      spark.sql(
          String.format(
              "CALL openhouse.system.cherrypick_snapshot('%s', %s)",
              TABLE_IDENTIFIER, snapshotIdWap1));

      // snapshot committed and data inserted
      assertEquals(1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());
      assertEquals(
          snapshotIdWap1,
          spark
              .sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".refs WHERE name == 'main'")
              .first()
              .mkString());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testCherryPickBaseChanged() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");
      spark.conf().unset("spark.wap.id");

      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')");
      String snapshotIdWap1 =
          spark
              .sql(
                  "SELECT snapshot_id FROM "
                      + FULL_TABLE_NAME
                      + ".snapshots WHERE summary['wap.id'] = 'wap1'")
              .first()
              .mkString();
      spark.sql(
          String.format(
              "CALL openhouse.system.cherrypick_snapshot('%s', %s)",
              TABLE_IDENTIFIER, snapshotIdWap1));
      String snapshotIdPublished =
          spark
              .sql(
                  "SELECT snapshot_id FROM "
                      + FULL_TABLE_NAME
                      + ".snapshots WHERE summary['published-wap-id'] = 'wap1'")
              .first()
              .mkString();

      // a new snapshot is added and committed
      assertEquals(2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          3, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());
      assertEquals(
          snapshotIdPublished,
          spark
              .sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".refs WHERE name == 'main'")
              .first()
              .mkString());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testCherryPickPublishedWap() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.b')");
      List<String> snapshotList =
          spark
              .sql(
                  "SELECT snapshot_id FROM "
                      + FULL_TABLE_NAME
                      + ".snapshots WHERE summary['wap.id'] = 'wap1'")
              .collectAsList().stream()
              .map(Row::mkString)
              .collect(Collectors.toList());
      spark.sql(
          String.format(
              "CALL openhouse.system.cherrypick_snapshot('%s', %s)",
              TABLE_IDENTIFIER, snapshotList.get(0)));

      // a wap id cannot be picked up more than once
      assertThrows(
          DuplicateWAPCommitException.class,
          () ->
              spark.sql(
                  String.format(
                      "CALL openhouse.system.cherrypick_snapshot('%s', %s)",
                      TABLE_IDENTIFIER, snapshotList.get(1))));

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testExpireSnapshotReferenced() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')");

      spark.conf().set("spark.wap.id", "wap1");
      String snapshotId =
          spark.sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".snapshots").first().mkString();

      // cannot expire snapshot that is still referenced
      assertThrows(
          IllegalArgumentException.class,
          () ->
              spark.sql(
                  String.format(
                      "CALL openhouse.system.expire_snapshots(table => '%s', snapshot_ids => Array(%s))",
                      TABLE_IDENTIFIER, snapshotId)));

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testExpireSnapshotUnreferenced() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.b')");

      spark.conf().set("spark.wap.id", "wap1");
      String snapshotIdHead =
          spark
              .sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".refs WHERE name == 'main'")
              .first()
              .mkString();
      String snapshotIdToExpire =
          spark
              .sql(
                  String.format(
                      "SELECT snapshot_id FROM "
                          + FULL_TABLE_NAME
                          + ".snapshots WHERE 'snapshot-id' != '%s'",
                      snapshotIdHead))
              .first()
              .mkString();
      spark.sql(
          String.format(
              "CALL openhouse.system.expire_snapshots(table => '%s', snapshot_ids => Array(%s))",
              TABLE_IDENTIFIER, snapshotIdToExpire));

      // can expire snapshot that is not referenced in wap mode
      assertEquals(2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testExpireSnapshotsWithUnpublishedWap() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");
      spark.conf().unset("spark.wap.id");

      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')");
      String snapshotIdToExpire =
          spark
              .sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".refs WHERE name == 'main'")
              .first()
              .mkString();
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.b')");
      spark.sql(
          String.format(
              "CALL openhouse.system.expire_snapshots(table => '%s', snapshot_ids => Array(%s))",
              TABLE_IDENTIFIER, snapshotIdToExpire));

      // expiring a specific snapshot won't affect its ancestor or unpublished wap snapshots
      assertEquals(2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testExpireSnapshotsWithEmptyRefs() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sparkContext().setLogLevel("WARN");
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)");
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");

      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')");

      String snapshotIdToExpire =
          spark.sql("SELECT snapshot_id FROM " + FULL_TABLE_NAME + ".snapshots").first().mkString();
      spark.sql(
          String.format(
              "CALL openhouse.system.expire_snapshots(table => '%s', snapshot_ids => Array(%s))",
              TABLE_IDENTIFIER, snapshotIdToExpire));

      // Behavioral change vs the 3.1 lane: on Iceberg 1.11 the single unpublished WAP snapshot has
      // no ref pointing at it, so stock expire_snapshots actually removes it (0 snapshots left),
      // whereas the 3.1 lane left it in place ("did nothing"). Data is still 0 rows either way.
      // See 10-RESIDUALS.md.
      assertEquals(0, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          0, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }

  @Test
  public void testWapWorkflowWithVariousOperations() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sparkContext().setLogLevel("WARN");
      spark.sql("CREATE TABLE " + FULL_TABLE_NAME + " (name string)"); // create
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('main.a')"); // insert
      spark.sql(
          "ALTER TABLE " + FULL_TABLE_NAME + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
      // Restored custom OpenHouse SET POLICY DDL (now available via the ported extension): set a
      // sharing policy inside the WAP workflow, exactly as the 3.1 source did, and confirm it
      // persisted. NOTE: the 3.1 source ALSO ran `GRANT SELECT ... TO lejiang` here; GRANT is still
      // dropped because the REST lane has no server ACL endpoint (see 10-RESIDUALS.md /
      // policy-sql-extension-spark4.md).
      spark.sql("ALTER TABLE " + FULL_TABLE_NAME + " SET POLICY (SHARING=TRUE)");
      String sharingPolicies =
          spark.sql("SHOW TBLPROPERTIES " + FULL_TABLE_NAME).collectAsList().stream()
              .filter(r -> "policies".equals(r.getString(0)))
              .map(r -> r.getString(1))
              .findFirst()
              .orElse(null);
      assertNotNull(sharingPolicies, "sharing policy should be set via SET POLICY (SHARING=TRUE)");
      assertTrue(
          sharingPolicies.contains("\"sharingEnabled\": true")
              || sharingPolicies.contains("\"sharingEnabled\":true"),
          sharingPolicies);
      spark.conf().set("spark.wap.id", "wap1");
      spark.sql("INSERT INTO " + FULL_TABLE_NAME + " VALUES ('wap1.a')"); // wap insert
      spark.conf().unset("spark.wap.id");
      String snapshotId =
          spark
              .sql(
                  "SELECT snapshot_id FROM "
                      + FULL_TABLE_NAME
                      + ".snapshots WHERE summary['wap.id'] = 'wap1'")
              .first()
              .mkString(); // select
      spark.sql(
          String.format(
              "CALL openhouse.system.cherrypick_snapshot('%s', %s)",
              TABLE_IDENTIFIER, snapshotId)); // cherry-pick
      spark.sql("DELETE FROM " + FULL_TABLE_NAME + " WHERE name = 'wap1.a'"); // delete
      spark.sql(
          String.format(
              "CALL openhouse.system.expire_snapshots(table => '%s', snapshot_ids => Array(%s))",
              TABLE_IDENTIFIER, snapshotId)); // expire

      assertEquals(1, spark.sql("SELECT * FROM " + FULL_TABLE_NAME).collectAsList().size());
      assertEquals(
          2, spark.sql("SELECT * FROM " + FULL_TABLE_NAME + ".snapshots").collectAsList().size());

      spark.sql("DROP TABLE " + FULL_TABLE_NAME);
    }
  }
}
