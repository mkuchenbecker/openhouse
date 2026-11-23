package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.view.View;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Views through Spark 3.5 SQL with Iceberg's own {@code SparkCatalog} over an unmodified {@code
 * RESTCatalog} ({@code type=rest}), no OpenHouse catalog class involved, against an embedded tables
 * service with views enabled for {@link #DB}. The only Spark configuration views need is Iceberg's
 * SQL extensions ({@code IcebergSparkSessionExtensions}, which the base session already has); the
 * view commands are rewritten by them ({@code spark.sql.iceberg.rewriteViewCommands.enabled},
 * default true). Every statement that works is pinned with its result as the OpenHouse client sees
 * it; every statement that does not is pinned with the error it gets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestSparkSqlViewTest extends OpenHouseSparkITest {
  private static final String DB = "irc_sql_views";
  private static final String OFF_DB = "irc_sql_views_off";
  private static final String CATALOG = "irc_v";

  private IrcViewsServer server;
  private SparkSession spark;
  private ViewCatalog openhouse;

  @BeforeAll
  void start() throws Exception {
    SparkSession base = getSparkSession();
    server = new IrcViewsServer(DB);
    spark = base.newSession();
    String prefix = "spark.sql.catalog." + CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", server.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", IrcViewsServer.token(IrcViewsServer.OWNER));
    spark.conf().set(prefix + ".cache-enabled", "false");
    openhouse =
        (ViewCatalog)
            server.openhouse(IrcViewsServer.OWNER, base.sparkContext().hadoopConfiguration());
    spark.sql("CREATE TABLE " + t("t") + " (id bigint, data string)");
    spark.sql("INSERT INTO " + t("t") + " VALUES (1,'a'),(2,'b')");
    spark.sql("CREATE TABLE " + CATALOG + "." + OFF_DB + ".t (id bigint)");
    spark.sql("INSERT INTO " + CATALOG + "." + OFF_DB + ".t VALUES (7)");
  }

  @AfterAll
  void stop() {
    server.close();
  }

  private static String t(String name) {
    return CATALOG + "." + DB + "." + name;
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

  private View viaOpenHouse(String view) {
    return openhouse.loadView(TableIdentifier.of(DB, view));
  }

  @Test
  void createSelectReplaceAlterShowDescribeDrop() {
    spark.sql("CREATE VIEW " + t("v") + " AS SELECT id FROM " + t("t"));
    Assertions.assertEquals(
        Arrays.asList("[1]", "[2]"), rows("SELECT * FROM " + t("v") + " ORDER BY id"));
    View stored = viaOpenHouse("v");
    Assertions.assertEquals("spark", stored.properties().get("openhouse.view.sourceDialect"));
    Assertions.assertTrue(stored.sqlFor("spark").sql().contains("SELECT id FROM"));

    Assertions.assertEquals(
        Collections.singletonList("[" + DB + ",v,false]"),
        rows("SHOW VIEWS IN " + CATALOG + "." + DB));
    // Tables and views are listed apart.
    Assertions.assertEquals(
        Collections.singletonList("[" + DB + ",t,false]"),
        rows("SHOW TABLES IN " + CATALOG + "." + DB));
    List<String> described = rows("DESCRIBE " + t("v"));
    Assertions.assertTrue(described.get(0).startsWith("[id,bigint"), described.toString());

    spark.sql("CREATE OR REPLACE VIEW " + t("v") + " AS SELECT id, id * 2 AS twice FROM " + t("t"));
    Assertions.assertEquals(
        Arrays.asList("[1,2]", "[2,4]"), rows("SELECT * FROM " + t("v") + " ORDER BY id"));
    Assertions.assertEquals(2, viaOpenHouse("v").currentVersion().versionId());
    Assertions.assertNotNull(viaOpenHouse("v").schema().findField("twice"));

    spark.sql("ALTER VIEW " + t("v") + " SET TBLPROPERTIES ('user.k'='1')");
    Assertions.assertEquals("1", viaOpenHouse("v").properties().get("user.k"));
    spark.sql("ALTER VIEW " + t("v") + " UNSET TBLPROPERTIES ('user.k')");
    Assertions.assertNull(viaOpenHouse("v").properties().get("user.k"));

    // A view over a view.
    spark.sql("CREATE VIEW " + t("vv") + " AS SELECT twice FROM " + t("v"));
    Assertions.assertEquals(
        Arrays.asList("[2]", "[4]"), rows("SELECT * FROM " + t("vv") + " ORDER BY twice"));

    // The native view API has no rename: the server's 406 on Iceberg 1.5.2; on 1.12 the client
    // refuses first, since the config's endpoint list omits the route.
    Throwable rename = thrown(() -> spark.sql("ALTER VIEW " + t("vv") + " RENAME TO " + t("vv2")));
    Assertions.assertTrue(
        String.valueOf(rename.getMessage())
            .contains(
                IcebergLine.current() == IcebergLine.LEGACY
                    ? "do not support rename"
                    : "Server does not support endpoint: POST /v1/{prefix}/views/rename"),
        rename.toString());
    Assertions.assertEquals(
        Arrays.asList("[2]", "[4]"), rows("SELECT * FROM " + t("vv") + " ORDER BY twice"));

    spark.sql("DROP VIEW " + t("vv"));
    spark.sql("DROP VIEW " + t("v"));
    Assertions.assertEquals(Collections.emptyList(), rows("SHOW VIEWS IN " + CATALOG + "." + DB));
    Throwable gone = thrown(() -> spark.sql("SELECT * FROM " + t("v")).collectAsList());
    Assertions.assertTrue(
        String.valueOf(gone.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND"), gone.toString());
    Assertions.assertFalse(openhouse.viewExists(TableIdentifier.of(DB, "v")));
    // The table is untouched.
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[2,b]"), rows("SELECT * FROM " + t("t") + " ORDER BY id"));
  }

  @Test
  void aViewWhoseBaseTableWasDroppedFailsToResolve() {
    spark.sql("CREATE TABLE " + t("gone") + " (id bigint)");
    spark.sql("CREATE VIEW " + t("orphan") + " AS SELECT id FROM " + t("gone"));
    spark.sql("DROP TABLE " + t("gone"));
    Throwable orphan = thrown(() -> spark.sql("SELECT * FROM " + t("orphan")).collectAsList());
    Assertions.assertTrue(
        String.valueOf(orphan.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND"), orphan.toString());
    // The view itself is still there and can be dropped.
    Assertions.assertTrue(openhouse.viewExists(TableIdentifier.of(DB, "orphan")));
    spark.sql("DROP VIEW " + t("orphan"));
  }

  /**
   * With views off for a database (the default), tables read as before: Spark's view probe gets
   * {@code NoSuchViewException} and falls back to the table. Creating a view there fails, and SHOW
   * VIEWS shows none.
   */
  @Test
  void withViewsDisabledTablesReadAndViewStatementsFail() {
    String off = CATALOG + "." + OFF_DB;
    Assertions.assertEquals(Collections.singletonList("[7]"), rows("SELECT * FROM " + off + ".t"));
    spark.sql("DROP VIEW IF EXISTS " + off + ".v");
    Throwable create = thrown(() -> spark.sql("CREATE VIEW " + off + ".v AS SELECT 1"));
    Assertions.assertTrue(
        String.valueOf(create.getMessage()).contains("SCHEMA_NOT_FOUND"), create.toString());
    // No views to show: the database holds none while its views are disabled.
    Assertions.assertEquals(Collections.emptyList(), rows("SHOW VIEWS IN " + off));
    Assertions.assertEquals(
        Collections.singletonList("[" + OFF_DB + ",t,false]"), rows("SHOW TABLES IN " + off));
  }

  /** Reading a table through a view-enabled catalog is unchanged by the view probe. */
  @Test
  void tablesStillReadWhereViewsAreEnabled() {
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[2,b]"), rows("SELECT * FROM " + t("t") + " ORDER BY id"));
    Catalog tables = (Catalog) openhouse;
    Assertions.assertTrue(tables.tableExists(TableIdentifier.of(DB, "t")));
  }
}
