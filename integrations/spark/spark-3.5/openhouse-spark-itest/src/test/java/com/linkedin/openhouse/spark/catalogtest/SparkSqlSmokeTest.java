package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Spark SQL smoke suite for the catalog path (work item T1). Each test records what Spark SQL does
 * today for one scenario; the expected values are observations, not judgements. Every action and
 * every assertion goes through {@code spark.sql}.
 *
 * <p>The catalog path is a parameter: a concrete subclass names its path in {@link #catalogPath()}
 * and {@link #sparkSession()} is the single place that turns the path into a session. Today the
 * only path is {@link #LEGACY}.
 */
public abstract class SparkSqlSmokeTest extends OpenHouseSparkITest {

  /** The catalog path in use today. */
  protected static final String LEGACY = "legacy";

  private static final String DB = "smoke_t1";
  private static final String CAT_DB = "openhouse." + DB;

  /** The catalog path under test. */
  protected abstract String catalogPath();

  /** The one place where the catalog path selects session and catalog configuration. */
  protected SparkSession sparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return getSparkSession();
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  // ---------- helpers ----------

  private static String table(String name) {
    return CAT_DB + "." + name;
  }

  /** Runs a query and renders each row as comma-joined values (null renders as "null"). */
  private static List<String> rows(SparkSession spark, String sql) {
    return spark.sql(sql).collectAsList().stream()
        .map(r -> r.mkString(","))
        .collect(Collectors.toList());
  }

  private static void fresh(SparkSession spark, String... tables) {
    for (String t : tables) {
      spark.sql("DROP TABLE IF EXISTS " + t);
    }
  }

  /** Runs a statement expected to fail and returns the exception. */
  private static Throwable failure(SparkSession spark, String sql) {
    return assertThrows(Throwable.class, () -> spark.sql(sql).collectAsList());
  }

  private static void assertFailure(
      SparkSession spark, String sql, String expectedClass, String messageFragment) {
    Throwable t = failure(spark, sql);
    assertEquals(expectedClass, t.getClass().getName());
    assertTrue(
        t.getMessage().contains(messageFragment),
        "Expected message to contain [" + messageFragment + "] but was: " + t.getMessage());
  }

  private static final String TABLE_NOT_FOUND_CLASS =
      "org.apache.spark.sql.catalyst.ExtendedAnalysisException";
  private static final String TABLE_NOT_FOUND = "[TABLE_OR_VIEW_NOT_FOUND] The table or view";

  // ---------- scenarios ----------

  @Test
  public void s01_create_partitionedByDayAndString() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s01_create");
    fresh(spark, t);
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, name string, ts timestamp) PARTITIONED BY (days(ts), name)");

    assertEquals(
        Arrays.asList(
            "id,bigint,null",
            "name,string,null",
            "ts,timestamp,null",
            ",,",
            "# Partitioning,,",
            "Part 0,days(ts),",
            "Part 1,name,"),
        rows(spark, "DESCRIBE " + t));
    assertEquals(
        Collections.singletonList(DB + ",s01_create,false"),
        rows(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's01_create'"));
    assertEquals(
        Collections.singletonList("openhouse.tableId,s01_create"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')"));
    assertEquals(
        Collections.singletonList("openhouse.databaseId," + DB),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.databaseId')"));
    assertEquals(
        Collections.singletonList("openhouse.tableType,PRIMARY_TABLE"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.tableType')"));
    assertEquals(
        Collections.singletonList("format-version,2"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('format-version')"));
    assertEquals(
        Collections.singletonList("write.format.default,orc"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('write.format.default')"));
    assertEquals(
        Collections.singletonList("policies,"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('policies')"));
  }

  @Test
  public void s02_insert_twice() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s02_insert");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    spark.sql("INSERT INTO " + t + " VALUES (3,'c')");

    assertEquals(
        Arrays.asList("1,a", "2,b", "3,c"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
    assertEquals(Collections.singletonList("3"), rows(spark, "SELECT count(*) FROM " + t));
    assertEquals(
        Collections.singletonList("2"), rows(spark, "SELECT count(*) FROM " + t + ".snapshots"));
    assertEquals(
        Arrays.asList("append", "append"),
        rows(spark, "SELECT operation FROM " + t + ".snapshots ORDER BY operation"));
  }

  @Test
  public void s03_overwrite_onePartition() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s03_overwrite");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y')");
    spark.sql("INSERT OVERWRITE " + t + " PARTITION (p='x') SELECT 10");

    assertEquals(Arrays.asList("2,y", "10,x"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
    assertEquals(
        Arrays.asList("append", "overwrite"),
        rows(spark, "SELECT operation FROM " + t + ".snapshots ORDER BY operation"));
  }

  @Test
  public void s04_ctas_schemaAndRows() throws Exception {
    SparkSession spark = sparkSession();
    String src = table("s04_ctas_src");
    String t = table("s04_ctas");
    fresh(spark, t, src);
    spark.sql("CREATE TABLE " + src + " (id bigint, name string)");
    spark.sql("INSERT INTO " + src + " VALUES (1,'a'),(2,'b')");
    spark.sql("CREATE TABLE " + t + " AS SELECT * FROM " + src);

    assertEquals(Arrays.asList("id,bigint,null", "name,string,null"), rows(spark, "DESCRIBE " + t));
    assertEquals(Arrays.asList("1,a", "2,b"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
  }

  @Test
  public void s05_replace_gatedByReplaceEnabled() throws Exception {
    SparkSession spark = sparkSession();
    String allowed = table("s05_replace_on");
    String gated = table("s05_replace_off");
    fresh(spark, allowed, gated);

    spark.sql("CREATE TABLE " + allowed + " (id bigint, name string)");
    spark.sql("INSERT INTO " + allowed + " VALUES (1,'a')");
    spark.sql("ALTER TABLE " + allowed + " SET TBLPROPERTIES ('replace.enabled'='true')");
    spark.sql("CREATE OR REPLACE TABLE " + allowed + " AS SELECT 7 AS id, 'z' AS name");
    assertEquals(Collections.singletonList("7,z"), rows(spark, "SELECT * FROM " + allowed));

    spark.sql("CREATE TABLE " + gated + " (id bigint, name string)");
    spark.sql("INSERT INTO " + gated + " VALUES (1,'a')");
    assertFailure(
        spark,
        "CREATE OR REPLACE TABLE " + gated + " AS SELECT 7 AS id, 'z' AS name",
        "org.apache.iceberg.exceptions.BadRequestException",
        "REPLACE TABLE AS SELECT is not enabled for table " + gated);
    assertEquals(Collections.singletonList("1,a"), rows(spark, "SELECT * FROM " + gated));
  }

  @Test
  public void s06_schema_addColumn() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s06_schema");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");
    spark.sql("ALTER TABLE " + t + " ADD COLUMN extra string");
    spark.sql("INSERT INTO " + t + " VALUES (2,'v')");

    assertEquals(
        Arrays.asList("1,null", "2,v"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
    assertEquals(
        Arrays.asList("id,bigint,null", "extra,string,null"), rows(spark, "DESCRIBE " + t));
  }

  @Test
  public void s07_properties_setUnsetAndReserved() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s07_props");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    spark.sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('team'='x')");
    assertEquals(
        Collections.singletonList("team,x"), rows(spark, "SHOW TBLPROPERTIES " + t + " ('team')"));

    spark.sql("ALTER TABLE " + t + " UNSET TBLPROPERTIES ('team')");
    assertEquals(
        0,
        rows(spark, "SHOW TBLPROPERTIES " + t).stream().filter(r -> r.startsWith("team,")).count());

    String restriction =
        "Bad tblproperties provided: Can't add, alter or drop table properties due to the"
            + " restriction: [table properties starting with `openhouse.` and the `policies` key"
            + " cannot be modified]";
    assertFailure(
        spark,
        "ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.foo'='hack')",
        "org.apache.iceberg.exceptions.BadRequestException",
        restriction);
    assertFailure(
        spark,
        "ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.tableId'='hack')",
        "org.apache.iceberg.exceptions.BadRequestException",
        restriction);
    assertEquals(
        Collections.singletonList("openhouse.tableId,s07_props"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')"));
  }

  @Test
  public void s08_delete_withPredicate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s08_delete");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b'),(3,'c')");
    spark.sql("DELETE FROM " + t + " WHERE id = 2");

    assertEquals(Arrays.asList("1,a", "3,c"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
    assertEquals(
        Arrays.asList("append", "overwrite"),
        rows(spark, "SELECT operation FROM " + t + ".snapshots ORDER BY operation"));
  }

  @Test
  public void s09_update_withPredicate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s09_update");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    spark.sql("UPDATE " + t + " SET name='B' WHERE id = 2");

    assertEquals(Arrays.asList("1,a", "2,B"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
  }

  @Test
  public void s10_merge_mergeOnRead() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s10_merge");
    String src = table("s10_merge_src");
    fresh(spark, t, src);
    spark.sql(
        "CREATE TABLE "
            + t
            + " (id bigint, name string) TBLPROPERTIES ("
            + "'write.merge.mode'='merge-on-read','write.delete.mode'='merge-on-read',"
            + "'write.update.mode'='merge-on-read')");
    spark.sql("CREATE TABLE " + src + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    spark.sql("INSERT INTO " + src + " VALUES (2,'B'),(3,'c')");
    spark.sql(
        "MERGE INTO "
            + t
            + " t USING "
            + src
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    // Delete files are present (content 1 = position deletes).
    assertEquals(
        Collections.singletonList("1,1"),
        rows(spark, "SELECT content, record_count FROM " + t + ".delete_files"));
    assertEquals(
        Arrays.asList("append", "overwrite"),
        rows(spark, "SELECT operation FROM " + t + ".snapshots ORDER BY operation"));
    // Today, reading the table back once delete files exist fails inside the test classpath
    // (shaded ORC classes cannot be cast to the unshaded ones). The merged row set can not be
    // observed through SELECT; the failure is what is recorded.
    Throwable read = failure(spark, "SELECT * FROM " + t + " ORDER BY id");
    assertEquals("org.apache.spark.SparkException", read.getClass().getName());
    assertTrue(read.getMessage().contains("Job aborted due to stage failure"), read.getMessage());
    assertTrue(
        read.getMessage()
            .contains(
                "java.lang.ClassCastException: class"
                    + " org.apache.iceberg.shaded.org.apache.orc.TypeDescription cannot be cast"),
        read.getMessage());
  }

  @Test
  public void s11_timeTravel_versionAsOf() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s11_timetravel");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");
    spark.sql("INSERT INTO " + t + " VALUES (2)");

    List<Row> first =
        spark
            .sql("SELECT snapshot_id FROM " + t + ".snapshots WHERE parent_id IS NULL")
            .collectAsList();
    assertEquals(1, first.size());
    long firstSnapshot = first.get(0).getLong(0);

    assertEquals(
        Collections.singletonList("1"),
        rows(spark, "SELECT * FROM " + t + " VERSION AS OF " + firstSnapshot));
    assertEquals(Arrays.asList("1", "2"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
  }

  @Test
  public void s12_branch_createAndInsert() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s12_branch");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");
    spark.sql("ALTER TABLE " + t + " CREATE BRANCH b1");
    spark.sql("INSERT INTO " + t + ".branch_b1 VALUES (2)");

    assertEquals(
        Collections.singletonList("1"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
    assertEquals(
        Arrays.asList("1", "2"), rows(spark, "SELECT * FROM " + t + ".branch_b1 ORDER BY id"));
    assertEquals(
        Arrays.asList("b1,BRANCH", "main,BRANCH"),
        rows(spark, "SELECT name, type FROM " + t + ".refs ORDER BY name"));
  }

  @Test
  public void s13_rename_table() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s13_rename");
    String renamed = table("s13_renamed");
    fresh(spark, t, renamed);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");
    spark.sql("ALTER TABLE " + t + " RENAME TO " + DB + ".s13_renamed");

    assertFailure(spark, "SELECT * FROM " + t, TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
    assertEquals(Collections.singletonList("1"), rows(spark, "SELECT * FROM " + renamed));
    assertEquals(
        Collections.singletonList(DB + ",s13_renamed,false"),
        rows(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's13_*'"));
  }

  @Test
  public void s14_drop_table() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s14_drop");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("DROP TABLE " + t);

    assertFailure(spark, "SELECT * FROM " + t, TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
    assertEquals(0, rows(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's14_drop'").size());
  }

  @Test
  public void s15_partitionEvolution_rejected() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s15_partevo");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, ts timestamp) PARTITIONED BY (days(ts))");

    assertFailure(
        spark,
        "ALTER TABLE " + t + " ADD PARTITION FIELD id",
        "org.apache.iceberg.exceptions.BadRequestException",
        "Evolution of table partitioning and clustering columns are not supported");
    assertEquals(
        Arrays.asList(
            "id,bigint,null", "ts,timestamp,null", ",,", "# Partitioning,,", "Part 0,days(ts),"),
        rows(spark, "DESCRIBE " + t));
  }

  @Test
  public void s16_policy_retention() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s16_policy");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, ts timestamp) PARTITIONED BY (days(ts))");
    spark.sql("ALTER TABLE " + t + " SET POLICY (RETENTION=30d)");

    assertEquals(
        Collections.singletonList(
            "policies,{\n  \"retention\": {\n    \"count\": 30,\n    \"granularity\": \"DAY\"\n  },\n"
                + "  \"sharingEnabled\": false\n}"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('policies')"));
  }

  @Test
  public void s17_grants_grantShowRevoke() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s17_grants");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    // Without the sharing policy a grant is rejected.
    assertFailure(
        spark,
        "GRANT SELECT ON TABLE " + t + " TO smoke_user",
        "java.lang.IllegalArgumentException",
        DB + ".s17_grants is not a shared table");

    spark.sql("ALTER TABLE " + t + " SET POLICY (SHARING=TRUE)");
    spark.sql("GRANT SELECT ON TABLE " + t + " TO smoke_user");
    // Today SHOW GRANTS returns no rows even right after a successful grant.
    assertEquals(0, rows(spark, "SHOW GRANTS ON TABLE " + t).size());
    spark.sql("REVOKE SELECT ON TABLE " + t + " FROM smoke_user");
    assertEquals(0, rows(spark, "SHOW GRANTS ON TABLE " + t).size());
  }

  @Test
  public void s18_views_createRejected() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s18_views");
    String v = table("s18_views_v");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");

    // Today views are not available in the default session: CREATE VIEW fails resolving the
    // session catalog's default schema.
    assertFailure(
        spark,
        "CREATE VIEW " + v + " AS SELECT id FROM " + t,
        "org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException",
        "[SCHEMA_NOT_FOUND] The schema `default` cannot be found");
    assertFailure(spark, "SELECT * FROM " + v, TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
    assertFailure(
        spark,
        "DROP VIEW " + v,
        "org.apache.spark.sql.catalyst.analysis.NoSuchViewException",
        "[VIEW_NOT_FOUND]");
    assertEquals(0, rows(spark, "SHOW VIEWS IN " + CAT_DB + " LIKE 's18_*'").size());
  }

  @Test
  public void s19_namespaces_showAndCreate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s19_namespaces");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    assertTrue(rows(spark, "SHOW DATABASES IN openhouse").contains(DB));
    assertFailure(
        spark,
        "CREATE NAMESPACE openhouse.smoke_t1_new_ns",
        "java.lang.UnsupportedOperationException",
        "Describing database is not supported");
    assertEquals(0, rows(spark, "SHOW DATABASES IN openhouse LIKE 'smoke_t1_new_ns'").size());
  }

  @Test
  public void s20_missingTable_select() throws Exception {
    SparkSession spark = sparkSession();
    assertFailure(
        spark,
        "SELECT * FROM " + table("s20_does_not_exist"),
        TABLE_NOT_FOUND_CLASS,
        TABLE_NOT_FOUND);
  }
}
