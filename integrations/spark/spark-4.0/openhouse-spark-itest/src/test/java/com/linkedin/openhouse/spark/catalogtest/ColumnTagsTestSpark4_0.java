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
 * extension's column policy-tag DDL: {@code ALTER TABLE <t> MODIFY COLUMN <col> SET TAG = (...)}.
 *
 * <p>This drives the full custom-DDL chain on the REST lane: the {@code ALTER TABLE ... MODIFY
 * COLUMN ... SET TAG = (PII, HC)} SQL is parsed by the ported {@code
 * OpenhouseSparkSessionExtensions} (registered in {@code OpenHouseRestSparkITest.getBuilder}),
 * lowered by {@code SetColumnPolicyTagExec} onto the reserved {@code updated.openhouse.policy}
 * carrier property on the stock {@code RESTCatalog} as {@code
 * {"columnTags":{"<col>":{"tags":[<PII|HC>, ...]}}}}, merged by the OpenHouse {@code /iceberg}
 * server's {@code translatePolicyPatch} ({@code patch.getColumnTags()} branch) into the structured
 * {@code Policies.columnTags} model, and read back from the reserved {@code policies} property via
 * {@code SHOW TBLPROPERTIES}.
 *
 * <p>Valid tags are {@code PII} and {@code HC} only. The clear form {@code SET TAG = (NONE)} is
 * also exercised: {@code SetColumnPolicyTagExec} emits an empty tags array ({@code
 * {"columnTags":{"<col>":{"tags":[]}}}}) for it, which the server persists as the column present
 * with an empty tag set (the previously-set tags are gone). See {@code
 * docs/spark4-iceberg-upgrade/spark4-e2e-tests/column-tags-verification.md}.
 */
public class ColumnTagsTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "column_tags_ddl";

  @Test
  public void testSetSingleColumnTagViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".single_tag";
      try {
        spark.sql("CREATE TABLE " + t + " (id string, ssn string)");
        spark.sql("INSERT INTO " + t + " VALUES ('a', '123')");
        spark.sql("ALTER TABLE " + t + " MODIFY COLUMN ssn SET TAG = (PII)");

        String policies = getPoliciesProperty(t, spark);
        Assertions.assertNotNull(
            policies, "policies should be set after MODIFY COLUMN ... SET TAG = (PII)");
        Assertions.assertTrue(policies.contains("columnTags"), policies);
        Assertions.assertTrue(policies.contains("ssn"), policies);
        Assertions.assertTrue(policies.contains("PII"), policies);
      } finally {
        spark.sql("DROP TABLE IF EXISTS " + t);
      }
    }
  }

  @Test
  public void testSetMultipleColumnTagsViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".multi_tag";
      try {
        spark.sql("CREATE TABLE " + t + " (id string, ssn string)");
        spark.sql("INSERT INTO " + t + " VALUES ('a', '123')");
        spark.sql("ALTER TABLE " + t + " MODIFY COLUMN ssn SET TAG = (PII, HC)");

        String policies = getPoliciesProperty(t, spark);
        Assertions.assertNotNull(
            policies, "policies should be set after MODIFY COLUMN ... SET TAG = (PII, HC)");
        Assertions.assertTrue(policies.contains("columnTags"), policies);
        Assertions.assertTrue(policies.contains("ssn"), policies);
        Assertions.assertTrue(policies.contains("PII"), policies);
        Assertions.assertTrue(policies.contains("HC"), policies);
      } finally {
        spark.sql("DROP TABLE IF EXISTS " + t);
      }
    }
  }

  /**
   * Verifies the clear form {@code SET TAG = (NONE)}: after tagging a column {@code (PII)},
   * applying {@code (NONE)} removes the tags for that column. {@code SetColumnPolicyTagExec} emits
   * an empty tags array for NONE, so the column is persisted with no tags (the {@code PII} tag is
   * gone).
   */
  @Test
  public void testClearColumnTagViaSqlDdl() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String t = "openhouse." + DATABASE + ".clear_tag";
      try {
        spark.sql("CREATE TABLE " + t + " (id string, ssn string)");
        spark.sql("INSERT INTO " + t + " VALUES ('a', '123')");
        spark.sql("ALTER TABLE " + t + " MODIFY COLUMN ssn SET TAG = (PII)");

        String policiesAfterSet = getPoliciesProperty(t, spark);
        Assertions.assertNotNull(policiesAfterSet);
        Assertions.assertTrue(policiesAfterSet.contains("PII"), policiesAfterSet);

        spark.sql("ALTER TABLE " + t + " MODIFY COLUMN ssn SET TAG = (NONE)");

        String policiesAfterClear = getPoliciesProperty(t, spark);
        Assertions.assertNotNull(policiesAfterClear);
        Assertions.assertFalse(
            policiesAfterClear.contains("PII"),
            "PII tag should be removed after SET TAG = (NONE): " + policiesAfterClear);
      } finally {
        spark.sql("DROP TABLE IF EXISTS " + t);
      }
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
