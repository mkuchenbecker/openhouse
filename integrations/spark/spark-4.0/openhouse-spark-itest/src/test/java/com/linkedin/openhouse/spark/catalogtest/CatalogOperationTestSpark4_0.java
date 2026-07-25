package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first port of the pure-SQL catalog-operation e2e tests (the 3.5
 * lane's {@code CatalogOperationTest}). Runs against the embedded OpenHouse server through the stock
 * {@code RESTCatalog}. Tests that drove the custom {@code OpenHouseCatalog} Java client or the
 * {@code Policies} model are ported separately where they translate to the REST lane.
 */
public class CatalogOperationTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "d1_catalog";

  @Test
  public void testCasingWithCTAS() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      // Casing-preserving table via backtick.
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".`tT1` (name string)");
      // Write with intentionally different casing.
      spark.sql("INSERT INTO openhouse." + DATABASE + ".Tt1 VALUES ('foo')");

      Assertions.assertEquals(
          1, spark.sql("SELECT * from openhouse." + DATABASE + ".tt1").collectAsList().size());
      // CTAS referring with lower-cased name.
      spark.sql(
          "CREATE TABLE openhouse."
              + DATABASE
              + ".t2 AS SELECT * from openhouse."
              + DATABASE
              + ".tt1");
      Assertions.assertEquals(
          1, spark.sql("SELECT * FROM openhouse." + DATABASE + ".t2").collectAsList().size());
    }
  }

  @Test
  public void testCreateTablePartitionedByDate() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String quotedFqtn = "openhouse." + DATABASE + ".tpartionedbydate";
      spark.sql(
          String.format(
              "CREATE TABLE %s (data string) PARTITIONED BY (datefield DATE)", quotedFqtn));
      spark
          .sql(String.format("INSERT INTO %s SELECT 'a', to_date('2024-06-21')", quotedFqtn))
          .show();

      StructType schema = spark.table(quotedFqtn).schema();
      StructField dateField = schema.fields()[1];
      Assertions.assertEquals("datefield", dateField.name());
      Assertions.assertTrue(
          dateField.dataType() instanceof DateType, "The 'datefield' column should be of DateType");
    }
  }

  @Test
  public void testRenameTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".rename_src (name string)");
      spark.sql("INSERT INTO openhouse." + DATABASE + ".rename_src VALUES ('a')");
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".rename_src RENAME TO openhouse."
              + DATABASE
              + ".rename_dst");
      Assertions.assertEquals(
          1,
          spark.sql("SELECT * FROM openhouse." + DATABASE + ".rename_dst").collectAsList().size());
    }
  }
}
