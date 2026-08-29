package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.*;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;

/**
 * Spark-3.5-only end-to-end test for the ENABLED client view gate ({@code
 * spark.sql.catalog.openhouse.iceberg-views-enabled=true}) against the real embedded tables
 * service, which serves the Iceberg REST views surface ({@code GET /v1/config} + {@code
 * /v1/namespaces/{ns}/views...}) with the stubbed views backend: every view operation answers a
 * views-disabled 404 with the spec's per-route error type ({@code NoSuchNamespaceException} on
 * create/list, {@code NoSuchViewException} on load/replace/drop/HEAD).
 *
 * <p>This is the cross-lane integration proof neither lane can run alone: with the gate ON, every
 * view operation crosses the real wire — {@code SparkCatalog.loadView} probe → embedded {@code
 * RESTCatalog} ({@code /v1/config} bootstrap, then {@code GET .../views/{view}}) → 404 spec
 * envelope → typed Iceberg exception — and the observable behavior is REQUIRED to be
 * indistinguishable from the gate-off default (the designed posture; see
 * views-client-plugin-plan.md §4.3). A gate-off control in the same class pins that
 * indistinguishability.
 *
 * <p>Wire-crossing is pinned non-hollowly: the server's fixed views-disabled message ({@code "Views
 * are disabled"}) is asserted on the gate-on {@code loadView} failure, while the gate-off control
 * asserts the client-local message ({@code "View does not exist"}) — same exception type, provably
 * different origin.
 *
 * <p>Each test stops any session left active by earlier test classes before building its own:
 * catalog plugins are instantiated once per Spark session, so a pre-existing session would
 * otherwise serve a catalog initialized without (or with) the gate regardless of this test's
 * overrides.
 */
public class OpenHouseViewGateOnTestSpark3_5 extends OpenHouseSparkITest {

  private static final String GATE_PROPERTY = "spark.sql.catalog.openhouse.iceberg-views-enabled";

  private static final String ON_DB = "viewgateon_db";
  private static final String OFF_DB = "viewgateoff_db";

  /** Views-disabled 404s from the real server carry this fixed message in the spec envelope. */
  private static final String SERVER_DISABLED_MESSAGE = "Views are disabled";

  /** The client's disabled-state loadView answers locally with this message — no wire involved. */
  private static final String CLIENT_LOCAL_MESSAGE = "View does not exist";

  /**
   * Stops any Spark session (and its context) left behind by other test classes, so the session
   * built next instantiates the OpenHouse catalog fresh from this test's configuration. Closing is
   * what the sibling catalogtest classes already do via try-with-resources; this only makes the
   * starting state deterministic regardless of class ordering.
   */
  private static void stopLingeringSparkSession() {
    if (SparkSession.getDefaultSession().isDefined()) {
      SparkSession.getDefaultSession().get().close();
    }
    SparkSession.clearDefaultSession();
    SparkSession.clearActiveSession();
  }

  private SparkSession gateOnSession() throws Exception {
    stopLingeringSparkSession();
    Map<String, String> overrides = new HashMap<>();
    overrides.put(GATE_PROPERTY, "true");
    SparkSession spark = getSparkSession("openhouse", overrides);
    // Pin that the override actually reached the session this test runs against; without this the
    // gate-on suite could silently run gate-off (whose observables are identical by design).
    assertEquals("true", spark.conf().get(GATE_PROPERTY));
    return spark;
  }

  private SparkSession gateOffSession() throws Exception {
    stopLingeringSparkSession();
    SparkSession spark = getSparkSession();
    assertFalse(
        spark.conf().contains(GATE_PROPERTY),
        "Control session must not carry the views gate override");
    return spark;
  }

  // ================================ Gate ON, over the real wire ================================

  /**
   * SELECT from a real table with the gate ON: Spark's identifier resolution probes {@code
   * SparkCatalog.loadView}, which crosses the wire (lazy {@code /v1/config} bootstrap + {@code GET
   * .../views/{table}}), gets the views-disabled 404 rendered as a {@code NoSuchViewException}
   * envelope, and falls through to {@code loadTable}. Table reads must succeed end to end.
   */
  @Test
  public void testGateOnSelectFromRealTableFallsThroughToTable() throws Exception {
    try (SparkSession spark = gateOnSession()) {
      spark.sql("CREATE TABLE openhouse." + ON_DB + ".t_select (id INT, name STRING)");
      spark.sql("INSERT INTO openhouse." + ON_DB + ".t_select VALUES (1, 'a'), (2, 'b')");

      List<Row> rows =
          spark
              .sql("SELECT id, name FROM openhouse." + ON_DB + ".t_select ORDER BY id")
              .collectAsList();
      assertEquals(2, rows.size());
      assertEquals(1, rows.get(0).getInt(0));
      assertEquals("a", rows.get(0).getString(1));
      assertEquals(2, rows.get(1).getInt(0));

      spark.sql("DROP TABLE openhouse." + ON_DB + ".t_select");
    }
  }

  /**
   * SHOW VIEWS with the gate ON: {@code SparkCatalog.listViews} delegates a single {@code GET
   * .../views}; the list-route 404 ({@code NoSuchNamespaceException} type per spec) is caught by
   * the plugin and answered as an empty list — never an error (the F1 catch: Spark's {@code
   * SparkCatalog.listViews} catches nothing itself).
   */
  @Test
  public void testGateOnShowViewsReturnsEmptyNotError() throws Exception {
    try (SparkSession spark = gateOnSession()) {
      spark.sql("CREATE TABLE openhouse." + ON_DB + ".t_showviews (id INT)");
      List<Row> views = spark.sql("SHOW VIEWS IN openhouse." + ON_DB).collectAsList();
      assertTrue(
          views.isEmpty(), "SHOW VIEWS must be empty against the stubbed server, got " + views);
      spark.sql("DROP TABLE openhouse." + ON_DB + ".t_showviews");
    }
  }

  /**
   * CREATE VIEW with the gate ON: the create-route 404 is rendered as {@code
   * NoSuchNamespaceException} (spec's create-route 404 type), which {@code
   * SparkCatalog.createView}'s existing handling normalizes to a Spark {@link AnalysisException} —
   * not a raw runtime error.
   */
  @Test
  public void testGateOnCreateViewFailsAsAnalysisException() throws Exception {
    try (SparkSession spark = gateOnSession()) {
      spark.sql("CREATE TABLE openhouse." + ON_DB + ".t_viewbase (id INT)");
      assertThrows(
          AnalysisException.class,
          () ->
              spark.sql(
                  "CREATE VIEW openhouse."
                      + ON_DB
                      + ".v_create AS SELECT * FROM openhouse."
                      + ON_DB
                      + ".t_viewbase"));
      spark.sql("DROP TABLE openhouse." + ON_DB + ".t_viewbase");
    }
  }

  /**
   * Programmatic {@link ViewCatalog} contract with the gate ON, against the real wire: {@code
   * viewExists} is false (GET load-and-catch in iceberg 1.5.2.17, not HEAD), {@code dropView} is
   * false (DELETE → 404 → caught by {@code RESTCatalog.dropView}), and {@code loadView} throws
   * {@link NoSuchViewException} carrying the SERVER's fixed views-disabled message — the pin that
   * these answers came over the wire and not from the client's disabled-state short-circuits.
   */
  @Test
  public void testGateOnProgrammaticViewCatalogAnswersOverTheWire() throws Exception {
    try (SparkSession spark = gateOnSession()) {
      spark.sql("CREATE TABLE openhouse." + ON_DB + ".t_programmatic (id INT)");
      Catalog catalog = getOpenHouseCatalog(spark);
      try {
        ViewCatalog viewCatalog = (ViewCatalog) catalog;
        // Against an identifier whose TABLE exists: the view route must still answer 404.
        TableIdentifier existingTableId = TableIdentifier.of(ON_DB, "t_programmatic");
        TableIdentifier missingId = TableIdentifier.of(ON_DB, "v_missing");

        assertFalse(viewCatalog.viewExists(existingTableId));
        assertFalse(viewCatalog.viewExists(missingId));
        assertFalse(viewCatalog.dropView(missingId));
        assertTrue(viewCatalog.listViews(Namespace.of(ON_DB)).isEmpty());

        NoSuchViewException thrown =
            assertThrows(NoSuchViewException.class, () -> viewCatalog.loadView(missingId));
        assertTrue(
            thrown.getMessage().contains(SERVER_DISABLED_MESSAGE),
            "Expected the server's views-disabled message (proof the failure crossed the real"
                + " wire), got: "
                + thrown.getMessage());
      } finally {
        if (catalog instanceof AutoCloseable) {
          ((AutoCloseable) catalog).close();
        }
        spark.sql("DROP TABLE openhouse." + ON_DB + ".t_programmatic");
      }
    }
  }

  // ============================ Gate OFF control: same observables ============================

  /**
   * The gate-off control (fresh session, no override): every observable above is identical — tables
   * read fine, SHOW VIEWS is empty, CREATE VIEW is an {@link AnalysisException}, and the
   * programmatic answers are false/false/{@link NoSuchViewException} — except the {@code loadView}
   * message is the CLIENT-local one, proving no wire was involved. Gate-on + stubbed server being
   * indistinguishable from gate-off is the designed posture this class exists to pin.
   */
  @Test
  public void testGateOffControlSameObservableBehavior() throws Exception {
    try (SparkSession spark = gateOffSession()) {
      // 1. Table create/read works.
      spark.sql("CREATE TABLE openhouse." + OFF_DB + ".t_control (id INT, name STRING)");
      spark.sql("INSERT INTO openhouse." + OFF_DB + ".t_control VALUES (1, 'a')");
      List<Row> rows =
          spark.sql("SELECT id, name FROM openhouse." + OFF_DB + ".t_control").collectAsList();
      assertEquals(1, rows.size());
      assertEquals(1, rows.get(0).getInt(0));

      // 2. SHOW VIEWS: empty, not an error.
      assertTrue(spark.sql("SHOW VIEWS IN openhouse." + OFF_DB).collectAsList().isEmpty());

      // 3. CREATE VIEW: AnalysisException, not a raw runtime error.
      assertThrows(
          AnalysisException.class,
          () ->
              spark.sql(
                  "CREATE VIEW openhouse."
                      + OFF_DB
                      + ".v_control AS SELECT * FROM openhouse."
                      + OFF_DB
                      + ".t_control"));

      // 4. Programmatic ViewCatalog answers, locally (no REST call is ever made when disabled).
      Catalog catalog = getOpenHouseCatalog(spark);
      try {
        ViewCatalog viewCatalog = (ViewCatalog) catalog;
        TableIdentifier missingId = TableIdentifier.of(OFF_DB, "v_missing");
        assertFalse(viewCatalog.viewExists(missingId));
        assertFalse(viewCatalog.dropView(missingId));
        assertTrue(viewCatalog.listViews(Namespace.of(OFF_DB)).isEmpty());
        NoSuchViewException thrown =
            assertThrows(NoSuchViewException.class, () -> viewCatalog.loadView(missingId));
        assertTrue(
            thrown.getMessage().contains(CLIENT_LOCAL_MESSAGE),
            "Expected the client-local disabled-state message, got: " + thrown.getMessage());
      } finally {
        if (catalog instanceof AutoCloseable) {
          ((AutoCloseable) catalog).close();
        }
        spark.sql("DROP TABLE openhouse." + OFF_DB + ".t_control");
      }
    }
  }
}
