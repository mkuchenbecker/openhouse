package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.tables.exception.ViewExceptionHandler;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

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

  /**
   * Iceberg's {@code RESTCatalog} over an in-process REST-shaped client that dispatches to today's
   * {@code OpenHouseCatalog} (Spark 3.5 / Iceberg 1.5 only).
   */
  protected static final String REST_SHIM = "rest-shim";

  private static final String LEGACY_CATALOG_IMPL = "com.linkedin.openhouse.spark.OpenHouseCatalog";
  private static final String REST_SHIM_CATALOG_IMPL =
      "com.linkedin.openhouse.spark.OpenHouseRestCatalog";

  private static SparkSession restShimSession;

  private static final String DB = "smoke_t1";
  private static final String CAT_DB = "openhouse." + DB;

  /** The catalog path under test. */
  protected abstract String catalogPath();

  /** The one place where the catalog path selects session and catalog configuration. */
  protected SparkSession sparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return getSparkSession();
      case REST_SHIM:
        return restShimSession();
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /**
   * A session over the shared Spark context whose {@code openhouse} catalog is built by the
   * REST-shaped catalog class; everything else matches the legacy session.
   */
  private SparkSession restShimSession() throws Exception {
    SparkSession base = getSparkSession();
    synchronized (SparkSqlSmokeTest.class) {
      if (restShimSession == null || restShimSession.sparkContext() != base.sparkContext()) {
        SparkSession spark = base.newSession();
        spark.conf().set("spark.sql.catalog.openhouse.catalog-impl", REST_SHIM_CATALOG_IMPL);
        restShimSession = spark;
      }
      return restShimSession;
    }
  }

  private static final String VIEWS_DB = "smoke_t1_views";
  private static final String VIEWS_CATALOG = "openhouse_views";
  private static ConfigurableApplicationContext viewsServer;

  /**
   * Session for scenarios that need views. Views are off in the default fixture, so these scenarios
   * run against a second embedded tables service with the views feature gate open and a catalog
   * with {@code iceberg-views-enabled} (the setup used by OpenHouseViewEnabledTestSpark3_5). The
   * catalog path still selects the configuration here.
   */
  protected SparkSession viewsSparkSession() throws Exception {
    switch (catalogPath()) {
      case LEGACY:
        return viewsSession(LEGACY_CATALOG_IMPL);
      case REST_SHIM:
        return viewsSession(REST_SHIM_CATALOG_IMPL);
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  private SparkSession viewsSession(String catalogImpl) throws Exception {
    SparkSession base = getSparkSession();
    synchronized (SparkSqlSmokeTest.class) {
      if (viewsServer == null) {
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        ConfigurableApplicationContext server =
            new SpringApplication(SpringH2TestApplication.class, ViewExceptionHandler.class)
                .run("--server.port=0");
        server
            .getBean(ToggleStatusesRepository.class)
            .save(
                TableToggleStatus.builder()
                    .databaseId(VIEWS_DB)
                    .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
                    .featureId("views")
                    .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
                    .build());
        viewsServer = server;
      }
    }
    String uri =
        "http://localhost:" + ((WebServerApplicationContext) viewsServer).getWebServer().getPort();
    SparkSession spark = base.newSession();
    String prefix = "spark.sql.catalog." + VIEWS_CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".catalog-impl", catalogImpl);
    spark.conf().set(prefix + ".uri", uri);
    spark.conf().set(prefix + ".cluster", "local-cluster");
    spark
        .conf()
        .set(prefix + ".auth-token", base.conf().get("spark.sql.catalog.openhouse.auth-token"));
    spark.conf().set(prefix + ".iceberg-views-enabled", "true");
    spark.conf().set(prefix + ".cache-enabled", "false");
    spark.conf().set("spark.sql.iceberg.rewriteViewCommands.enabled", "true");
    return spark;
  }

  /**
   * Existing catalogtest classes close the shared session when they finish, so the next class
   * builds its own. Do the same, and stop the views server, so this class leaves no session behind
   * for classes (such as OpenHouseViewEnabledTestSpark3_5) that expect to create their own.
   */
  @AfterAll
  static void releaseSessionAndViewsServer() {
    scala.Option<SparkSession> active = SparkSession.getDefaultSession();
    if (active.isDefined()) {
      active.get().close();
    }
    synchronized (SparkSqlSmokeTest.class) {
      restShimSession = null;
      if (viewsServer != null) {
        viewsServer.close();
        viewsServer = null;
      }
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
    assertEquals(
        Arrays.asList("1,a", "2,B", "3,c"), rows(spark, "SELECT * FROM " + t + " ORDER BY id"));
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
  public void s13b_rename_catalogQualifiedTarget() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s13b_rename");
    String renamed = table("s13b_renamed");
    fresh(spark, t, renamed);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1)");

    // Observed today: a target qualified with the catalog name is accepted (it does not fail).
    spark.sql("ALTER TABLE " + t + " RENAME TO " + renamed);

    assertFailure(spark, "SELECT * FROM " + t, TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
    assertEquals(Collections.singletonList("1"), rows(spark, "SELECT * FROM " + renamed));
    assertEquals(
        Collections.singletonList(DB + ",s13b_renamed,false"),
        rows(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's13b_*'"));
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
    // The fixture cannot observe grants: with no OPA base URI configured, OpaAuthorizationHandler
    // (the AuthorizationHandler the embedded server uses) logs "Skipping grant role" and its list
    // methods return nothing. No fixture in the repo (including the enforcing one in
    // UnlockAuthorizationTest, which subclasses it without overriding grants) stores grants, so
    // SHOW GRANTS is empty even right after a successful GRANT.
    assertEquals(0, rows(spark, "SHOW GRANTS ON TABLE " + t).size());
    spark.sql("REVOKE SELECT ON TABLE " + t + " FROM smoke_user");
    assertEquals(0, rows(spark, "SHOW GRANTS ON TABLE " + t).size());
  }

  @Test
  public void s18_views_createSelectDrop() throws Exception {
    SparkSession spark = viewsSparkSession();
    String t = VIEWS_CATALOG + "." + VIEWS_DB + ".s18_views";
    String v = VIEWS_CATALOG + "." + VIEWS_DB + ".s18_views_v";
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.sql("INSERT INTO " + t + " VALUES (1),(2)");

    // Views work on the view-enabled fixture; the default fixture session rejects CREATE VIEW.
    spark.sql("CREATE VIEW " + v + " AS SELECT id FROM " + t);
    assertEquals(Arrays.asList("1", "2"), rows(spark, "SELECT * FROM " + v + " ORDER BY id"));
    assertEquals(
        Collections.singletonList(VIEWS_DB + ",s18_views_v,false"),
        rows(spark, "SHOW VIEWS IN " + VIEWS_CATALOG + "." + VIEWS_DB + " LIKE 's18_views_v'"));

    spark.sql("DROP VIEW " + v);
    assertEquals(
        0,
        rows(spark, "SHOW VIEWS IN " + VIEWS_CATALOG + "." + VIEWS_DB + " LIKE 's18_views_v'")
            .size());
    assertFailure(spark, "SELECT * FROM " + v, TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
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

  // ---------- scenarios added with the REST-shaped path (work item C1) ----------
  // Each records today's behavior for something the scenarios above do not pin and that the REST
  // client handles differently from today's catalog unless the seam keeps today's behavior.

  /**
   * Property keys every new table gets today; values that vary (ids, times, paths) are not pinned.
   */
  private static final List<String> NEW_TABLE_PROPERTY_KEYS =
      Arrays.asList(
          "current-snapshot-id",
          "format",
          "format-version",
          "history.expire.max-ref-age-ms",
          "openhouse.clusterId",
          "openhouse.creationTime",
          "openhouse.databaseId",
          "openhouse.isTableReplicated",
          "openhouse.lastModifiedTime",
          "openhouse.tableCreator",
          "openhouse.tableId",
          "openhouse.tableLocation",
          "openhouse.tableType",
          "openhouse.tableUUID",
          "openhouse.tableUri",
          "openhouse.tableVersion",
          "policies",
          "write.format.default",
          "write.metadata.delete-after-commit.enabled",
          "write.metadata.previous-versions-max",
          "write.parquet.compression-codec");

  private static List<String> propertyKeys(SparkSession spark, String table) {
    return spark.sql("SHOW TBLPROPERTIES " + table).collectAsList().stream()
        .map(r -> r.getString(0))
        .sorted()
        .collect(Collectors.toList());
  }

  @Test
  public void s21_ctas_tablePropertiesMatchCreate() throws Exception {
    SparkSession spark = sparkSession();
    String src = table("s21_ctas_src");
    String t = table("s21_ctas");
    fresh(spark, t, src);
    spark.sql("CREATE TABLE " + src + " (id bigint, name string)");
    spark.sql("INSERT INTO " + src + " VALUES (1,'a')");
    spark.sql("CREATE TABLE " + t + " AS SELECT * FROM " + src);

    // CTAS gets exactly the properties a plain CREATE TABLE gets (no client-added keys such as
    // created-at), from a single create: the table version is still the initial one.
    assertEquals(NEW_TABLE_PROPERTY_KEYS, propertyKeys(spark, t));
    assertEquals(NEW_TABLE_PROPERTY_KEYS, propertyKeys(spark, src));
    assertEquals(
        Collections.singletonList("openhouse.tableVersion,INITIAL_VERSION"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.tableVersion')"));
    assertEquals(
        Collections.singletonList("openhouse.tableId,s21_ctas"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')"));
    assertEquals(
        Collections.singletonList("1"), rows(spark, "SELECT count(*) FROM " + t + ".snapshots"));
  }

  @Test
  public void s22_rtas_keepsPropertiesAndHistory() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s22_rtas");
    String created = table("s22_rtas_new");
    fresh(spark, t, created);
    spark.sql("CREATE TABLE " + t + " (id bigint, name string) TBLPROPERTIES ('team'='x')");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a')");
    spark.sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    spark.sql(
        "CREATE OR REPLACE TABLE "
            + t
            + " TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more");

    assertEquals(Collections.singletonList("7,z,q"), rows(spark, "SELECT * FROM " + t));
    assertEquals(
        Arrays.asList("id,bigint,null", "name,string,null", "more,string,null"),
        rows(spark, "DESCRIBE " + t));
    // Existing properties survive a replace; the replace adds its own.
    assertEquals(
        Collections.singletonList("team,x"), rows(spark, "SHOW TBLPROPERTIES " + t + " ('team')"));
    assertEquals(
        Collections.singletonList("extra,y"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('extra')"));
    assertEquals(
        Collections.singletonList("replace.enabled,true"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('replace.enabled')"));
    assertEquals(
        Collections.singletonList("openhouse.creationTime,0"),
        rows(spark, "SHOW TBLPROPERTIES " + t + " ('openhouse.creationTime')"));
    // The snapshot history is kept.
    assertEquals(
        Arrays.asList("append", "append"),
        rows(spark, "SELECT operation FROM " + t + ".snapshots ORDER BY committed_at"));

    // CREATE OR REPLACE of a missing table creates it.
    spark.sql("CREATE OR REPLACE TABLE " + created + " AS SELECT 1L AS id");
    assertEquals(Collections.singletonList("1"), rows(spark, "SELECT * FROM " + created));
  }

  @Test
  public void s23_replaceTable_gatedByReplaceEnabled() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s23_replace");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    // REPLACE TABLE without AS SELECT goes through the same gate.
    assertFailure(
        spark,
        "REPLACE TABLE " + t + " (id bigint, x string)",
        "org.apache.iceberg.exceptions.BadRequestException",
        "REPLACE TABLE AS SELECT is not enabled for table " + t);
    assertEquals(Collections.singletonList("id,bigint,null"), rows(spark, "DESCRIBE " + t));

    spark.sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    spark.sql("REPLACE TABLE " + t + " (id bigint, x string)");
    assertEquals(Arrays.asList("id,bigint,null", "x,string,null"), rows(spark, "DESCRIBE " + t));
  }

  @Test
  public void s24_namespaces_otherStatementsUnsupported() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s24_namespaces");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    assertFailure(
        spark,
        "DESCRIBE NAMESPACE " + CAT_DB,
        "java.lang.UnsupportedOperationException",
        "Describing database is not supported");
    assertFailure(
        spark,
        "ALTER NAMESPACE " + CAT_DB + " SET DBPROPERTIES ('k'='v')",
        "java.lang.UnsupportedOperationException",
        "Set properties on a database is not supported");
    assertFailure(
        spark,
        "DROP NAMESPACE " + CAT_DB,
        "java.lang.UnsupportedOperationException",
        "Describing database is not supported");
    assertFailure(
        spark,
        "SHOW NAMESPACES IN " + CAT_DB,
        "java.lang.UnsupportedOperationException",
        "Openhouse supports 2-lvl namespace <schema>.<table>");
    assertEquals(
        Collections.singletonList(DB + ",s24_namespaces,false"),
        rows(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's24_namespaces'"));
  }

  @Test
  public void s25_showTables_withoutDatabase() throws Exception {
    SparkSession spark = sparkSession();
    String noDatabase =
        "DatabaseId was not provided, for SQL please run \"SHOW TABLES IN <databaseId>\" instead";
    assertFailure(
        spark,
        "SHOW TABLES IN openhouse",
        "org.apache.iceberg.exceptions.ValidationException",
        noDatabase);
    try {
      spark.sql("USE openhouse");
      assertFailure(
          spark, "SHOW TABLES", "org.apache.iceberg.exceptions.ValidationException", noDatabase);
    } finally {
      spark.sql("USE spark_catalog");
    }
  }

  @Test
  public void s26_describeExtended_nameTypeProvider() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s26_describe");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    List<String> detail = rows(spark, "DESCRIBE TABLE EXTENDED " + t);
    assertTrue(detail.contains("Name," + t + ","), detail.toString());
    assertTrue(detail.contains("Type,MANAGED,"), detail.toString());
    assertTrue(detail.contains("Provider,iceberg,"), detail.toString());
    assertTrue(
        detail.stream().anyMatch(r -> r.startsWith("Location,/tmp/" + DB + "/s26_describe-")),
        detail.toString());
  }

  @Test
  public void s27_unlock_unlockedAndMissingTable() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s27_unlock");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");
    spark.conf().set("spark.sql.openhouse.unlock.enabled", "true");
    try {
      // Unlocking a table that holds no lock is a no-op.
      spark.sql("ALTER TABLE " + t + " UNLOCK");
      assertFailure(
          spark,
          "ALTER TABLE " + table("s27_missing") + " UNLOCK",
          "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException",
          "User table " + DB + ".s27_missing cannot be found");
    } finally {
      spark.conf().unset("spark.sql.openhouse.unlock.enabled");
    }
  }

  @Test
  public void s28_views_disabledByDefault() throws Exception {
    SparkSession spark = sparkSession();
    assertFailure(
        spark,
        "CREATE VIEW " + table("s28_view") + " AS SELECT 1",
        "org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException",
        "[SCHEMA_NOT_FOUND]");
    assertEquals(0, rows(spark, "SHOW VIEWS IN " + CAT_DB).size());
    spark.sql("DROP VIEW IF EXISTS " + table("s28_view"));
  }

  @Test
  public void s29_rename_rejected() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s29_rename");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint)");

    assertFailure(
        spark,
        "ALTER TABLE " + t + " RENAME TO " + DB + ".s29_rename",
        "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException",
        "Cannot rename a table to the same current db name and table name: s29_rename");
    assertFailure(
        spark,
        "ALTER TABLE " + t + " RENAME TO other_catalog." + DB + ".s29_renamed",
        "java.lang.UnsupportedOperationException",
        "Cannot rename tables across catalogs: from="
            + t
            + ", to=other_catalog."
            + DB
            + ".s29_renamed");
    assertFailure(
        spark,
        "ALTER TABLE " + table("s29_missing") + " RENAME TO " + DB + ".s29_missing2",
        TABLE_NOT_FOUND_CLASS,
        TABLE_NOT_FOUND);
  }

  @Test
  public void s30_metadataTablesAndProcedure() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s30_meta");
    fresh(spark, t);
    spark.sql("CREATE TABLE " + t + " (id bigint, name string)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a')");
    spark.sql("ALTER TABLE " + t + " ALTER COLUMN name COMMENT 'hello'");

    assertEquals(
        Collections.singletonList("1"), rows(spark, "SELECT count(*) FROM " + t + ".history"));
    assertEquals(
        Collections.singletonList("1"), rows(spark, "SELECT count(*) FROM " + t + ".files"));
    assertEquals(
        Arrays.asList("id,bigint,null", "name,string,hello"), rows(spark, "DESCRIBE " + t));
    assertEquals(
        Collections.singletonList("0,0,0,0,0,0"),
        rows(
            spark,
            "CALL openhouse.system.expire_snapshots(table => '"
                + DB
                + ".s30_meta', retain_last => 1)"));
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
