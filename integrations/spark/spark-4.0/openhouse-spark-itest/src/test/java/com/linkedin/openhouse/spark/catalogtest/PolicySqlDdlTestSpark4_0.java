package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first END-TO-END verification of the ported OpenHouse Spark SQL
 * extension's {@code SET POLICY} DDL.
 *
 * <p>Unlike {@link PolicyPropertyTestSpark4_0} (which sets the {@code updated.openhouse.policy}
 * carrier property directly via the Iceberg Java {@code updateProperties()} API), this test drives
 * the full custom-DDL chain: the {@code ALTER TABLE ... SET POLICY (...)} SQL is parsed by the
 * ported {@code OpenhouseSparkSessionExtensions} (registered in {@code
 * OpenHouseRestSparkITest.getBuilder}), lowered by {@code Set*PolicyExec} onto the {@code
 * updated.openhouse.policy} property on the stock {@code RESTCatalog}, translated by the OpenHouse
 * {@code /iceberg} server into the structured {@code Policies} model, and read back from the
 * reserved {@code policies} property via {@code SHOW TBLPROPERTIES}. This is the DDL path the
 * legacy Spark-3.x lane exercised, now working on the Spark-4.0 REST lane.
 *
 * <p>See {@code docs/spark4-iceberg-upgrade/spark4-e2e-tests/policy-sql-extension-spark4.md}.
 */
public class PolicySqlDdlTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "policy_sql_ddl";

  @Test
  public void testSetRetentionPolicyViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".retention_ddl";
      spark.sql("CREATE TABLE " + t + " (name string)");
      spark.sql("INSERT INTO " + t + " VALUES ('foo')");
      spark.sql(
          "ALTER TABLE "
              + t
              + " SET POLICY (RETENTION = 30D ON COLUMN name WHERE PATTERN = 'yyyy-MM-dd')");

      String policies = getPoliciesProperty(t, spark);
      Assertions.assertNotNull(policies, "policies should be set after SET POLICY (RETENTION ...)");
      Assertions.assertTrue(policies.contains("retention"), policies);
      Assertions.assertTrue(policies.contains("DAY"), policies);
      Assertions.assertTrue(policies.contains("yyyy-MM-dd"), policies);
    }
  }

  @Test
  public void testSetReplicationPolicyViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".replication_ddl";
      spark.sql("CREATE TABLE " + t + " (name string)");
      spark.sql("INSERT INTO " + t + " VALUES ('foo')");
      spark.sql(
          "ALTER TABLE " + t + " SET POLICY (REPLICATION=({destination:'WAR', interval:12h}))");

      String policies = getPoliciesProperty(t, spark);
      Assertions.assertNotNull(
          policies, "policies should be set after SET POLICY (REPLICATION ...)");
      Assertions.assertTrue(policies.contains("replication"), policies);
      Assertions.assertTrue(policies.contains("WAR"), policies);
    }
  }

  @Test
  public void testSetSharingPolicyViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".sharing_ddl";
      spark.sql("CREATE TABLE " + t + " (name string)");
      spark.sql("INSERT INTO " + t + " VALUES ('foo')");
      spark.sql("ALTER TABLE " + t + " SET POLICY (SHARING=TRUE)");

      String policies = getPoliciesProperty(t, spark);
      Assertions.assertNotNull(policies, "policies should be set after SET POLICY (SHARING=TRUE)");
      Assertions.assertTrue(
          policies.contains("\"sharingEnabled\": true")
              || policies.contains("\"sharingEnabled\":true"),
          policies);
    }
  }

  @Test
  public void testSetHistoryPolicyViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".history_ddl";
      spark.sql("CREATE TABLE " + t + " (name string)");
      spark.sql("INSERT INTO " + t + " VALUES ('foo')");
      spark.sql("ALTER TABLE " + t + " SET POLICY (HISTORY MAX_AGE=24H)");

      String policies = getPoliciesProperty(t, spark);
      Assertions.assertNotNull(policies, "policies should be set after SET POLICY (HISTORY ...)");
      Assertions.assertTrue(policies.contains("history"), policies);
    }
  }

  /** Reads the raw {@code policies} table property string via {@code SHOW TBLPROPERTIES}. */
  private String getPoliciesProperty(String tableName, SparkSession spark) {
    List<Row> propsRows =
        spark.sql(String.format("SHOW TBLPROPERTIES %s", tableName)).collectAsList();
    Map<String, String> collect =
        propsRows.stream().collect(Collectors.toMap(r -> r.getString(0), r -> r.getString(1)));
    return collect.get("policies");
  }
}
