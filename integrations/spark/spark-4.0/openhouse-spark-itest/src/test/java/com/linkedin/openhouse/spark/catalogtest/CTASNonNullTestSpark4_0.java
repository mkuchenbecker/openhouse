package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.*;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first port of {@code CTASNonNullTest} / {@code
 * CTASNonNullTestSpark3_5} (both source classes were identical). Pure Spark SQL; the target schema
 * nullability after CTAS is asserted empirically on the Spark-4.0 connector.
 */
public class CTASNonNullTestSpark4_0 extends OpenHouseRestSparkITest {
  @Test
  public void testCTASPreservesNonNull() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      // Create source table with NOT NULL column
      spark.sql(
          "CREATE TABLE openhouse.ctasNonNull.test_table (id INT NOT NULL, name STRING NOT NULL, value DOUBLE NOT NULL)");
      // Create target table using CTAS, OpenHouse catalog
      spark.sql(
          "CREATE TABLE openhouse.ctasNonNull.test_tableCtas USING iceberg AS SELECT * FROM openhouse.ctasNonNull.test_table");

      // Get schemas for both tables
      StructType sourceSchema = spark.table("openhouse.ctasNonNull.test_table").schema();
      StructType targetSchema = spark.table("openhouse.ctasNonNull.test_tableCtas").schema();

      // Verify spark catalogs have correct classes configured
      assertEquals(
          "org.apache.iceberg.spark.SparkCatalog", spark.conf().get("spark.sql.catalog.openhouse"));

      // Source table retains its NOT NULL constraint.
      assertFalse(sourceSchema.apply("id").nullable(), "Source table id column should be required");
      // CTAS non-null preservation is off by default, so the target column is nullable.
      assertTrue(
          targetSchema.apply("id").nullable(),
          "Target table id column required should not be preserved -- CTAS non-nullable preservation is off by default");

      // Clean up
      spark.sql("DROP TABLE openhouse.ctasNonNull.test_table");
      spark.sql("DROP TABLE openhouse.ctasNonNull.test_tableCtas");
    }
  }
}
