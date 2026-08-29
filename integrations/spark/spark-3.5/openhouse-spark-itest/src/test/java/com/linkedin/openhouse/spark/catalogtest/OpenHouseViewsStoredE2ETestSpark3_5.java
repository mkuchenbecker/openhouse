package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.*;

import com.linkedin.openhouse.tablestest.OpenHouseLocalServer;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.TestSparkSessionUtil;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.View;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spark DDL against a server that actually stores views: {@code CREATE VIEW}, {@code SELECT} from
 * it, {@code SHOW VIEWS}, {@code DROP VIEW}.
 *
 * <p>This is the end of the wire. Every earlier views test proved one segment of it — the service
 * stores and returns metadata, the controller renders the spec's envelopes, the client plugin
 * delegates to a REST catalog — but each stopped at its own boundary, and the one test that did
 * cross the whole wire ({@link OpenHouseViewGateOnTestSpark3_5}) ran against a server whose views
 * backend refused. What it could prove was that a refusal travels correctly. Nothing until here
 * showed a query written in Spark surviving the round trip and coming back as rows.
 *
 * <p><b>Why this class runs its own server.</b> {@code OpenHouseSparkITest} shares one embedded
 * server across every test in the module, and that one has views switched off — which is the
 * default, and which {@link OpenHouseViewGateOnTestSpark3_5} exists to pin. Turning views on for
 * the shared server would delete that coverage in order to add this. So this class starts a second
 * instance with {@code cluster.tables.views.enabled=true} and points its own Spark session at it.
 * The two servers take different ports and different in-memory databases; neither sees the other.
 *
 * <p>It extends {@code OpenHouseSparkITest} only for {@code getOpenHouseCatalog}, which rebuilds
 * the plugin from a session's own configuration. None of that base class's session or server
 * methods are called, so the shared server is never started on this class's account.
 *
 * <p><b>The client gate is on too.</b> Both switches have to be on for a view to work end to end:
 * without the client's {@code iceberg-views-enabled} Spark never asks the server about views, and
 * without the server's {@code cluster.tables.views.enabled} the server answers that it has none. A
 * test that set only one would pass its DDL through to a table and look like it had proved
 * something.
 */
public class OpenHouseViewsStoredE2ETestSpark3_5 extends OpenHouseSparkITest {

  private static final String DB = "viewstored_db";
  private static final String LOCAL_FS = "file:///";
  private static final String CLIENT_GATE = "spark.sql.catalog.openhouse.iceberg-views-enabled";

  private static OpenHouseLocalServer viewsEnabledServer;

  @BeforeAll
  static void startServerWithViewsEnabled() {
    viewsEnabledServer =
        new OpenHouseLocalServer(Collections.singletonMap("cluster.tables.views.enabled", "true"));
    viewsEnabledServer.start();
  }

  @AfterAll
  static void stopServer() {
    if (viewsEnabledServer != null) {
      viewsEnabledServer.stop();
    }
  }

  /**
   * Catalog plugins are built once per Spark session, so a session another test class left running
   * would serve a catalog pointed at the shared server with the gate off — and the DDL below would
   * then be answered by the wrong server entirely.
   */
  private static void stopLingeringSparkSession() {
    if (SparkSession.getDefaultSession().isDefined()) {
      SparkSession.getDefaultSession().get().close();
    }
    SparkSession.clearDefaultSession();
    SparkSession.clearActiveSession();
  }

  private SparkSession session() {
    stopLingeringSparkSession();
    URI serverUri = URI.create("http://localhost:" + viewsEnabledServer.getPort());
    SparkSession.Builder builder = TestSparkSessionUtil.getBaseBuilder(URI.create(LOCAL_FS));
    TestSparkSessionUtil.configureCatalogs(builder, "openhouse", serverUri);
    TestSparkSessionUtil.configureCatalogs(builder, "default_iceberg", serverUri);
    builder.config(CLIENT_GATE, "true");
    SparkSession spark = TestSparkSessionUtil.createSparkSession(builder);
    // Both halves of the gate, asserted rather than assumed: a session that silently came back
    // without the override would run every assertion below against the table path.
    assertEquals("true", spark.conf().get(CLIENT_GATE));
    return spark;
  }

  @Test
  public void aViewCreatedInSparkIsQueryableAndListableAndDroppable() throws Exception {
    try (SparkSession spark = session()) {
      spark.sql("CREATE TABLE openhouse." + DB + ".t_base (id INT, name STRING)");
      spark.sql("INSERT INTO openhouse." + DB + ".t_base VALUES (1, 'a'), (2, 'b'), (3, 'c')");

      spark.sql(
          "CREATE VIEW openhouse."
              + DB
              + ".v_over_base AS SELECT id, name FROM openhouse."
              + DB
              + ".t_base WHERE id > 1");

      List<Row> rows =
          spark
              .sql("SELECT id, name FROM openhouse." + DB + ".v_over_base ORDER BY id")
              .collectAsList();
      assertEquals(2, rows.size(), "the view's predicate must be the one that ran");
      assertEquals(2, rows.get(0).getInt(0));
      assertEquals("b", rows.get(0).getString(1));
      assertEquals(3, rows.get(1).getInt(0));

      List<String> views =
          spark.sql("SHOW VIEWS IN openhouse." + DB).collectAsList().stream()
              .map(row -> row.getString(1))
              .collect(Collectors.toList());
      assertTrue(views.contains("v_over_base"), "the stored view must be listed, got " + views);

      spark.sql("DROP VIEW openhouse." + DB + ".v_over_base");
      assertThrows(
          AnalysisException.class,
          () -> spark.sql("SELECT * FROM openhouse." + DB + ".v_over_base").collectAsList(),
          "a dropped view must stop resolving");

      spark.sql("DROP TABLE openhouse." + DB + ".t_base");
    }
  }

  /**
   * The view outlives the session that made it.
   *
   * <p>Everything above could pass against a catalog that cached the view in the driver and never
   * stored it. A second session, built from scratch against the same server, can only see what the
   * server actually persisted.
   */
  @Test
  public void aViewSurvivesTheSessionThatCreatedIt() throws Exception {
    try (SparkSession creating = session()) {
      creating.sql("CREATE TABLE openhouse." + DB + ".t_persist (id INT)");
      creating.sql("INSERT INTO openhouse." + DB + ".t_persist VALUES (7)");
      creating.sql(
          "CREATE VIEW openhouse."
              + DB
              + ".v_persist AS SELECT id FROM openhouse."
              + DB
              + ".t_persist");
    }

    try (SparkSession reading = session()) {
      List<Row> rows = reading.sql("SELECT id FROM openhouse." + DB + ".v_persist").collectAsList();
      assertEquals(1, rows.size());
      assertEquals(7, rows.get(0).getInt(0));

      reading.sql("DROP VIEW openhouse." + DB + ".v_persist");
      reading.sql("DROP TABLE openhouse." + DB + ".t_persist");
    }
  }

  /**
   * The stored definition, read through Iceberg's own view API rather than through Spark.
   *
   * <p>Spark can answer a {@code SELECT} correctly from a definition that lost detail on the way
   * down — the SQL is all it needs. This reads the metadata document the server returned and checks
   * the parts a REST client actually contracts on: the dialect, the query text, and the schema.
   */
  @Test
  public void theStoredMetadataCarriesTheQueryAndSchemaSparkSent() throws Exception {
    try (SparkSession spark = session()) {
      spark.sql("CREATE TABLE openhouse." + DB + ".t_meta (id INT, name STRING)");
      spark.sql(
          "CREATE VIEW openhouse." + DB + ".v_meta AS SELECT id FROM openhouse." + DB + ".t_meta");

      Catalog catalog = getOpenHouseCatalog(spark);
      try {
        ViewCatalog viewCatalog = (ViewCatalog) catalog;
        TableIdentifier identifier = TableIdentifier.of(DB, "v_meta");

        assertTrue(viewCatalog.viewExists(identifier), "the created view must exist");
        assertTrue(
            viewCatalog.listViews(Namespace.of(DB)).contains(identifier),
            "and it must be in its namespace's listing");

        View view = viewCatalog.loadView(identifier);
        SQLViewRepresentation representation =
            (SQLViewRepresentation) view.currentVersion().representations().get(0);
        assertEquals("spark", representation.dialect());
        assertTrue(
            representation.sql().contains("t_meta"),
            "the stored query must be the one Spark sent, got: " + representation.sql());
        assertEquals(
            Collections.singletonList("id"),
            view.schema().columns().stream()
                .map(field -> field.name())
                .collect(Collectors.toList()),
            "the view's schema is its projection, not the base table's");

        assertTrue(viewCatalog.dropView(identifier), "the first drop removes it");
        assertFalse(viewCatalog.dropView(identifier), "a second drop reports false");
      } finally {
        if (catalog instanceof AutoCloseable) {
          ((AutoCloseable) catalog).close();
        }
        spark.sql("DROP TABLE openhouse." + DB + ".t_meta");
      }
    }
  }
}
