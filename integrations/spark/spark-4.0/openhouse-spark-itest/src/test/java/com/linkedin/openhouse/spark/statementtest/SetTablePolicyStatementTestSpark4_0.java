package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException;
import java.nio.file.Files;
import java.util.Optional;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.execution.ExplainMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Spark-4.0 / Scala-2.13 port of the legacy {@code SetTablePolicyStatementTest} (spark-3.1).
 * Verifies the OpenHouse {@code ALTER TABLE ... SET POLICY (RETENTION ...)} DDL parses and lowers
 * to a valid plan through the ported {@code OpenhouseSparkSessionExtensions}. Self-contained: it
 * drives a local hadoop-backed Iceberg catalog (no OpenHouse server), so it exercises the grammar /
 * AST builder / logical plan / strategy / exec purely.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SetTablePolicyStatementTestSpark4_0 {
  private static SparkSession spark = null;

  @BeforeAll
  public void setupSpark() throws Exception {
    Path unittest = new Path(Files.createTempDirectory("unittest_settablepolicy").toString());
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

  @Test
  public void testSimpleSetPolicyWithPattern() {
    Dataset<Row> ds =
        spark.sql(
            "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d"
                + " ON COLUMN ts WHERE PATTERN = 'yyyy')");
    assert isPlanValid(ds, "db.table", "30", "DAY", Optional.of("ts"), Optional.of("'yyyy'"));

    // lowercased scenario
    ds =
        spark.sql(
            "ALTER TABLE openhouse.db.table SET policy (RETENTION = 30d"
                + " ON COLUMN ts WHERE pattern = 'yyyy')");
    assert isPlanValid(ds, "db.table", "30", "DAY", Optional.of("ts"), Optional.of("'yyyy'"));

    // identifiers with leading digits. Spark 4.0's Identifier.toString backtick-quotes parts that
    // need quoting (a leading digit), so the physical plan renders `0_`.`0_` where Spark 3.1
    // rendered
    // 0_.0_. Parse/plan intent is identical; only the rendering differs (engine delta).
    ds =
        spark.sql(
            "ALTER TABLE openhouse.0_.0_ SET policy (RETENTION = 30d"
                + " ON COLUMN ts WHERE pattern = 'yyyy')");
    assert isPlanValid(ds, "`0_`.`0_`", "30", "DAY", Optional.of("ts"), Optional.of("'yyyy'"));
  }

  @Test
  public void testSimpleSetPolicyNoPattern() {
    Dataset<Row> ds = spark.sql("ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d)");
    assert isPlanValid(ds, "db.table", "30", "DAY", Optional.empty(), Optional.empty());
  }

  @Test
  public void testSimpleSetPolicyWithColumnNoPattern() {
    Dataset<Row> ds =
        spark.sql("ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d ON COLUMN ts)");
    assert isPlanValid(ds, "db.table", "30", "DAY", Optional.of("ts"), Optional.empty());
  }

  @Test
  public void testPolicyWithoutProperSyntax() {
    // missing colName value
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql("ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d ON COLUMN)")
                .show());

    // Missing colName
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql(
                    "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d WHERE pattern = 'yyyy')")
                .show());

    // Typo in keyword spelling
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql(
                    "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d ON COLUMN ts WHERE patern = 'yyyy')")
                .show());

    // Missing quota in pattern
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql(
                    "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d WHERE pattern = yyyy)")
                .show());

    // Missing keyword
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql(
                    "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d ON ts WHERE pattern = 'yyyy')")
                .show());

    // Missing pattern value
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () ->
            spark
                .sql(
                    "ALTER TABLE openhouse.db.table SET POLICY (RETENTION = 30d ON COLUMN ts WHERE patern)")
                .show());
  }

  @BeforeEach
  public void setup() {
    spark.sql("CREATE TABLE openhouse.db.table (id bigint, data string) USING iceberg").show();
    spark.sql("CREATE TABLE openhouse.0_.0_ (id bigint, data string) USING iceberg").show();
    spark
        .sql("ALTER TABLE openhouse.db.table SET TBLPROPERTIES ('openhouse.tableId' = 'tableid')")
        .show();
    spark
        .sql("ALTER TABLE openhouse.0_.0_ SET TBLPROPERTIES ('openhouse.tableId' = 'tableid')")
        .show();
  }

  @AfterEach
  public void tearDown() {
    spark.sql("DROP TABLE openhouse.db.table").show();
    spark.sql("DROP TABLE openhouse.0_.0_").show();
  }

  @AfterAll
  public void tearDownSpark() throws java.io.IOException {
    spark.close();
  }

  private boolean isPlanValid(
      Dataset<Row> dataframe,
      String dbTable,
      String count,
      String granularity,
      Optional<String> colName,
      Optional<String> colPattern) {
    String queryStr = dataframe.queryExecution().explainString(ExplainMode.fromString("simple"));
    return queryStr.contains(count)
        && queryStr.contains(dbTable)
        && queryStr.contains(granularity)
        && (!colName.isPresent() || queryStr.contains(colName.get()))
        && (!colPattern.isPresent() || queryStr.contains(colPattern.get()));
  }
}
