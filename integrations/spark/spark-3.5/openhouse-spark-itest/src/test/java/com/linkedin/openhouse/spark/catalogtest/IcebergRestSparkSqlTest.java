package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * What an engine really sends: Spark 3.5 SQL through Iceberg's own {@code SparkCatalog} over an
 * unmodified {@code RESTCatalog} ({@code type=rest}), no OpenHouse catalog class involved, against
 * the embedded tables service. Every statement that works is pinned with its result as the
 * OpenHouse client sees it; every statement that does not is pinned with the error it gets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestSparkSqlTest extends OpenHouseSparkITest {
  private static final String DB = "irc_sql";
  private static final String CATALOG = "irc";

  private SparkSession spark;
  private Catalog openhouse;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    openhouse = getOpenHouseCatalog(spark);
    String prefix = "spark.sql.catalog." + CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", getOpenHouseLocalServerURI() + "/iceberg");
    spark
        .conf()
        .set(
            prefix + ".token",
            new DummyTokenInterceptor.DummySecurityJWT("irc_sql").buildNoopJWT());
    for (String t : Arrays.asList("t", "ctas", "rtas", "renamed", "dropped", "nodrop", "mor")) {
      try {
        openhouse.dropTable(TableIdentifier.of(DB, t));
      } catch (RuntimeException ignored) {
        // not there
      }
    }
  }

  private static String t(String table) {
    return CATALOG + "." + DB + "." + table;
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  private Table viaOpenHouse(String table) {
    return openhouse.loadTable(TableIdentifier.of(DB, table));
  }

  @Test
  void createInsertSelectUpdateDeleteMergeAlter() {
    spark.sql("CREATE TABLE " + t("t") + " (id bigint, data string, p string) PARTITIONED BY (p)");
    spark.sql("INSERT INTO " + t("t") + " VALUES (1,'a','x'),(2,'b','y'),(3,'c','x')");
    Assertions.assertEquals(
        Arrays.asList("[1,a,x]", "[2,b,y]", "[3,c,x]"),
        rows("SELECT * FROM " + t("t") + " ORDER BY id"));
    // The OpenHouse client reads the same rows from the same table.
    Assertions.assertEquals(
        rows("SELECT * FROM " + t("t") + " ORDER BY id"),
        rows("SELECT * FROM openhouse." + DB + ".t ORDER BY id"));

    spark.sql("UPDATE " + t("t") + " SET data = 'bb' WHERE id = 2");
    spark.sql("DELETE FROM " + t("t") + " WHERE id = 3");
    spark.sql(
        "MERGE INTO "
            + t("t")
            + " target USING (SELECT 1 AS id, 'aa' AS data, 'x' AS p UNION ALL SELECT 4, 'd', 'y') src"
            + " ON target.id = src.id"
            + " WHEN MATCHED THEN UPDATE SET target.data = src.data"
            + " WHEN NOT MATCHED THEN INSERT *");
    Assertions.assertEquals(
        Arrays.asList("[1,aa,x]", "[2,bb,y]", "[4,d,y]"),
        rows("SELECT * FROM " + t("t") + " ORDER BY id"));

    spark.sql("ALTER TABLE " + t("t") + " ADD COLUMN extra int");
    // Renaming a column is refused by the native schema-evolution rule (columns match by name).
    Throwable rename =
        thrown(() -> spark.sql("ALTER TABLE " + t("t") + " RENAME COLUMN data TO payload"));
    Assertions.assertTrue(
        String.valueOf(rename.getMessage()).contains("invalid schema evolution"),
        rename.toString());
    spark.sql("ALTER TABLE " + t("t") + " SET TBLPROPERTIES ('user.k'='v')");
    Table table = viaOpenHouse("t");
    Assertions.assertNotNull(table.schema().findField("data"));
    Assertions.assertNotNull(table.schema().findField("extra"));
    Assertions.assertEquals("v", table.properties().get("user.k"));

    // Dropping a column is refused by the native schema-evolution rule.
    Throwable drop = thrown(() -> spark.sql("ALTER TABLE " + t("t") + " DROP COLUMN extra"));
    Assertions.assertTrue(
        String.valueOf(drop.getMessage()).contains("BadRequestException")
            || drop.getClass().getSimpleName().contains("BadRequest"),
        drop.toString());
    Assertions.assertNotNull(viaOpenHouse("t").schema().findField("extra"));
  }

  /**
   * Merge-on-read row-level changes write delete files through the client's default FileIO (no
   * FileIO is advertised by the server); both catalogs then read the same rows.
   */
  @Test
  void mergeOnReadDeleteAndUpdate() {
    spark.sql(
        "CREATE TABLE "
            + t("mor")
            + " (id bigint, data string) TBLPROPERTIES ('write.delete.mode'='merge-on-read',"
            + " 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read')");
    spark.sql("INSERT INTO " + t("mor") + " VALUES (1,'a'),(2,'b'),(3,'c')");
    spark.sql("DELETE FROM " + t("mor") + " WHERE id = 2");
    spark.sql("UPDATE " + t("mor") + " SET data = 'cc' WHERE id = 3");
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[3,cc]"), rows("SELECT * FROM " + t("mor") + " ORDER BY id"));
    Assertions.assertEquals(
        rows("SELECT * FROM " + t("mor") + " ORDER BY id"),
        rows("SELECT * FROM openhouse." + DB + ".mor ORDER BY id"));
    Assertions.assertTrue(
        Integer.parseInt(
                viaOpenHouse("mor")
                    .currentSnapshot()
                    .summary()
                    .getOrDefault("total-delete-files", "0"))
            > 0,
        viaOpenHouse("mor").currentSnapshot().summary().toString());
  }

  @Test
  void ctasRtasDropAndRename() {
    spark.sql("CREATE TABLE " + t("ctas") + " AS SELECT 1L AS id, 'a' AS data");
    Assertions.assertEquals(Arrays.asList("[1,a]"), rows("SELECT * FROM " + t("ctas")));
    Assertions.assertNotNull(viaOpenHouse("ctas").currentSnapshot());

    spark.sql(
        "CREATE TABLE " + t("rtas") + " (id bigint) TBLPROPERTIES ('replace.enabled'='true')");
    spark.sql("INSERT INTO " + t("rtas") + " VALUES (9)");
    spark.sql("REPLACE TABLE " + t("rtas") + " AS SELECT 1L AS id, 'r' AS data");
    Assertions.assertEquals(Arrays.asList("[1,r]"), rows("SELECT * FROM " + t("rtas")));
    Assertions.assertNotNull(viaOpenHouse("rtas").schema().findField("data"));

    // RTAS on a table that does not enable it gets the native refusal.
    Throwable refused =
        thrown(() -> spark.sql("REPLACE TABLE " + t("ctas") + " AS SELECT 2L AS id, 'b' AS data"));
    Assertions.assertTrue(
        String.valueOf(refused.getMessage()).contains("REPLACE TABLE AS SELECT is not enabled"),
        refused.toString());
    Assertions.assertEquals(Arrays.asList("[1,a]"), rows("SELECT * FROM " + t("ctas")));

    spark.sql("ALTER TABLE " + t("ctas") + " RENAME TO " + DB + ".renamed");
    Assertions.assertEquals(Arrays.asList("[1,a]"), rows("SELECT * FROM " + t("renamed")));
    Assertions.assertFalse(openhouse.tableExists(TableIdentifier.of(DB, "ctas")));

    spark.sql("CREATE TABLE " + t("dropped") + " (id bigint)");
    spark.sql("DROP TABLE " + t("dropped"));
    Assertions.assertFalse(openhouse.tableExists(TableIdentifier.of(DB, "dropped")));
  }

  /** "ok", or the failure's class and message. */
  private String outcome(String sql) {
    try {
      spark.sql(sql).collectAsList();
      return "ok";
    } catch (Throwable failure) {
      return failure.getClass().getName() + ": " + failure.getMessage();
    }
  }

  private boolean exists(String database, String table) {
    return openhouse.tableExists(TableIdentifier.of(database, table));
  }

  /**
   * {@code ALTER TABLE ... RENAME TO} with a target that names a catalog, through the OpenHouse
   * catalog, as the reference, and through Iceberg's own {@code RESTCatalog}. Spark hands the
   * target to the catalog as written. The OpenHouse catalog class reads a two-level namespace as
   * {@code <catalog>.<database>} ({@code OpenHouseCatalog.renameTable}), and over REST the SQL
   * extensions read it the same way ({@code ResolveRenameTableTarget}): each statement answers the
   * same on both, and the service has the same tables afterwards.
   */
  @Test
  void renameToATargetThatNamesACatalogAnswersAsThroughTheOpenHouseCatalog() {
    for (String catalog : Arrays.asList("openhouse", CATALOG)) {
      String otherCatalog = catalog.equals(CATALOG) ? "openhouse" : CATALOG;
      // One set of tables per catalog, in the test's database and in one named like the catalog.
      String t = "rn_" + catalog;
      String inDb = catalog + "." + DB + "." + t;
      String inNamesake = catalog + "." + catalog + "." + t;
      for (String table : Arrays.asList(t, t + "_2", t + "_3", t + "_4")) {
        for (String database : Arrays.asList(DB, catalog)) {
          try {
            openhouse.dropTable(TableIdentifier.of(database, table));
          } catch (RuntimeException ignored) {
            // not there
          }
        }
      }
      spark.sql("CREATE TABLE " + inDb + " (id bigint)");

      // The catalog's own name is not a namespace level.
      Assertions.assertEquals(
          "ok", outcome("ALTER TABLE " + inDb + " RENAME TO " + inDb + "_2"), catalog);
      Assertions.assertFalse(exists(DB, t), catalog);
      Assertions.assertTrue(exists(DB, t + "_2"), catalog);

      // Another name is another catalog: a name no catalog has, the session's other catalog (the
      // same service), and the own name in another case.
      for (String name : Arrays.asList("other_catalog", otherCatalog, catalog.toUpperCase())) {
        String target = name + "." + DB + "." + t + "_3";
        Assertions.assertEquals(
            "java.lang.UnsupportedOperationException: Cannot rename tables across catalogs: from="
                + inDb
                + "_2, to="
                + target,
            outcome("ALTER TABLE " + inDb + "_2 RENAME TO " + target),
            catalog);
      }

      // More than two levels, counted before the first one is looked at.
      for (String namespace :
          Arrays.asList(catalog + "." + DB + ".extra", "other_catalog." + DB + ".extra")) {
        Assertions.assertEquals(
            "org.apache.iceberg.exceptions.ValidationException: Namespace has unexpected levels "
                + namespace,
            outcome("ALTER TABLE " + inDb + "_2 RENAME TO " + namespace + "." + t + "_3"),
            catalog);
      }
      Assertions.assertTrue(exists(DB, t + "_2"), catalog);
      Assertions.assertFalse(exists(DB, t + "_3"), catalog);

      // A target that names no catalog, with and without a database.
      Assertions.assertEquals(
          "ok", outcome("ALTER TABLE " + inDb + "_2 RENAME TO " + DB + "." + t + "_3"), catalog);
      Assertions.assertEquals(
          "ok", outcome("ALTER TABLE " + inDb + "_3 RENAME TO " + t + "_4"), catalog);
      Assertions.assertTrue(exists(DB, t + "_4"), catalog);

      // A database named like the catalog: a one-level target namespace is a database, a
      // two-level one is <catalog>.<database>, of which one level goes.
      spark.sql("CREATE TABLE " + inNamesake + " (id bigint)");
      Assertions.assertEquals(
          "ok",
          outcome("ALTER TABLE " + inNamesake + " RENAME TO " + catalog + "." + t + "_2"),
          catalog);
      Assertions.assertTrue(exists(catalog, t + "_2"), catalog);
      Assertions.assertEquals(
          "ok",
          outcome("ALTER TABLE " + inNamesake + "_2 RENAME TO " + inNamesake + "_3"),
          catalog);
      Assertions.assertTrue(exists(catalog, t + "_3"), catalog);
      // From another database it is a rename into the database named like the catalog, which the
      // service refuses on both paths (each with its path's exception class).
      String acrossDatabases =
          outcome("ALTER TABLE " + inDb + "_4 RENAME TO " + catalog + "." + t + "_4");
      Assertions.assertTrue(
          acrossDatabases.contains(
              "Rename table across databases is not supported. Cannot rename table "
                  + t
                  + "_4 from "
                  + DB
                  + " to "
                  + catalog),
          acrossDatabases);
      Assertions.assertTrue(exists(DB, t + "_4"), catalog);
      Assertions.assertFalse(exists(catalog, t + "_4"), catalog);
    }
  }

  /**
   * CREATE NAMESPACE over the plain Iceberg REST path stores a namespace record, with the
   * properties Spark itself adds to the statement: its owner, the session's user.
   */
  @Test
  void createNamespaceStoresSparksOwnProperties() throws Exception {
    String ns = "irc_sql_ns_props";
    spark.sql("CREATE NAMESPACE " + CATALOG + "." + ns);
    java.util.Map<String, String> properties = new java.util.HashMap<>();
    properties.put("uri", getOpenHouseLocalServerURI() + "/iceberg");
    properties.put("token", new DummyTokenInterceptor.DummySecurityJWT("irc_sql").buildNoopJWT());
    try (org.apache.iceberg.rest.RESTCatalog rest = new org.apache.iceberg.rest.RESTCatalog()) {
      rest.initialize("probe", properties);
      Assertions.assertEquals(
          java.util.Collections.singletonMap("owner", spark.sparkContext().sparkUser()),
          rest.loadNamespaceMetadata(org.apache.iceberg.catalog.Namespace.of(ns)));
    } finally {
      spark.sql("DROP NAMESPACE " + CATALOG + "." + ns);
    }
  }
}
