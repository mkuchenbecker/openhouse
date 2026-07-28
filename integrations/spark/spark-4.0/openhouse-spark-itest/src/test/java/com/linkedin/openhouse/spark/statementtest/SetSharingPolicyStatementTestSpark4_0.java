package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
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
 * Spark-4.0 / Scala-2.13 port of the legacy {@code SetSharingPolicyStatementTest} (spark-3.1).
 * Verifies {@code ALTER TABLE ... SET POLICY (SHARING=...)} parses (incl. comment stripping and
 * catalog/database resolution) to a valid plan (local hadoop catalog, no server).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SetSharingPolicyStatementTestSpark4_0 {

  private static SparkSession spark = null;

  @Test
  public void testPolicySuccess() {
    Dataset<Row> df = spark.sql("ALTER TABLE openhouse.db.table SET POLICY (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "db.table", "TRUE");

    Dataset<Row> df1 = spark.sql("ALTER TABLE openhouse.db.table SET POLICY (SHARING=FALSE)");
    assert isPlanValid(df1, "openhouse", "db.table", "FALSE");
  }

  @Test
  public void testPolicyLowerCase() {
    Dataset<Row> df = spark.sql("ALTER TABLE openhouse.db.table SET policy (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "db.table", "TRUE");
  }

  @Test
  public void testPolicyAfterUseCatalog() {
    spark.sql("use openhouse").show();
    Dataset<Row> df = spark.sql("ALTER TABLE db.table SET policy (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "db.table", "TRUE");
  }

  @Test
  public void testPolicyAfterUseCatalogAndDatabase() {
    spark.sql("use openhouse.db").show();
    Dataset<Row> df = spark.sql("ALTER TABLE table SET policy (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "db.table", "TRUE");
  }

  @Test
  public void testPolicyWithQuotedTableIdentifier() {
    Dataset<Row> df = spark.sql("ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "db.table", "TRUE");
  }

  @Test
  public void testPolicyIdentifierWithLeadingDigits() {
    // Spark 4.0's Identifier.toString backtick-quotes leading-digit parts (`0_`.`0_`) where Spark
    // 3.1 rendered 0_.0_. Parse/plan intent identical; only the rendering differs (engine delta).
    Dataset<Row> df = spark.sql("ALTER TABLE openhouse.0_.0_ SET policy (SHARING=TRUE)");
    assert isPlanValid(df, "openhouse", "`0_`.`0_`", "TRUE");
  }

  @Test
  public void testSharingWithMultiLineComments() {
    List<String> statementsWithComments =
        Arrays.asList(
            "/* bracketed comment */  ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "/**/  ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "-- single line comment \n ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "-- multiple \n-- single line \n-- comments \n ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "/* select * from multiline_comment \n where x like '%sql%'; */ ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "/* {\"app\": \"dbt\", \"dbt_version\": \"1.0.1\", \"profile_name\": \"profile1\", \"target_name\": \"dev\", "
                + "\"node_id\": \"model.profile1.stg_users\"} \n*/ ALTER TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)",
            "/* Some multi-line comment \n"
                + "*/ ALTER /* inline comment */ TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE) -- ending comment",
            "ALTER -- a line ending comment\n"
                + "TABLE openhouse.`db`.`table` SET policy (SHARING=TRUE)");
    for (String statement : statementsWithComments) {
      assert isPlanValid(spark.sql(statement), "openhouse", "db.table", "TRUE");
    }
  }

  @Test
  public void testPolicyWithoutProperSyntax() {
    Assertions.assertThrows(
        OpenhouseParseException.class,
        () -> spark.sql("ALTER TABLE openhouse.db.table SET policy (SHARING=TRU)").show());
  }

  @BeforeAll
  public void setupSpark() throws Exception {
    Path unittest = new Path(Files.createTempDirectory("unittest_setsharingpolicy").toString());
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
            "CREATE TABLE openhouse.0_.0_ (id bigint, 0_ string, `openhouse.tableId` string) USING iceberg")
        .show();
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
      Dataset<Row> dataframe, String catalogName, String dbTable, String sharingEnabled) {
    String queryStr = dataframe.queryExecution().explainString(ExplainMode.fromString("simple"));
    return queryStr.contains(sharingEnabled) && queryStr.contains(dbTable);
  }
}
